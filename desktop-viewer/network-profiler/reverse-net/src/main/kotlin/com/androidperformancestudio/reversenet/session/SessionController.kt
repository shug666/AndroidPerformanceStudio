/*
 * Copyright (C) 2026 Android Performance Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("SwallowedException", "UnusedParameter")

package com.androidperformancestudio.reversenet.session

import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import com.androidperformancestudio.reversenet.relay.RelayEngine
import com.androidperformancestudio.reversenet.relay.RelayListener
import com.androidperformancestudio.reversenet.relay.TapEventBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Orchestrates the reverse-tethering session for a single device:
 * install vendored APK -> adb reverse -> start relay -> start device client,
 * with reverse-order cleanup on stop and VPN-authorization detection derived
 * from relay tunnel connections.
 */
public class SessionController(
    private val adbOperations: ReverseNetAdbOperations,
    private val vendoredApk: Path,
    private val eventBus: ReverseNetEventBus,
    private val relayListenerOverride: RelayListener? = null,
    private val relayPort: Int = com.androidperformancestudio.reversenet.relay.RelayEngine.DEFAULT_PORT,
    private val relayFactory: ((port: Int, listener: RelayListener) -> RelayEngine)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow<ReverseNetSessionState>(ReverseNetSessionState.Idle)

    public val state: StateFlow<ReverseNetSessionState> = stateFlow.asStateFlow()

    private var relayEngine: RelayEngine? = null
    private var relayThread: Thread? = null
    private var authorizationTimeoutJob: Job? = null
    private var activeSerial: String? = null

    public fun isSessionActive(): Boolean = activeSerial != null

    /**
     * Attempt to detect leftover state from a crashed session and clean it up.
     * Called on APS startup; returns true when leftovers were found.
     */
    public suspend fun recoverLeftovers(serial: String): Boolean {
        val tunnels = adbOperations.listReverseTunnels(serial)
        val leftover = tunnels.any { it.contains("localabstract:gnirehtet") }
        if (!leftover) {
            return false
        }
        adbOperations.stopClientOnDevice(serial)
        adbOperations.removeReverseTunnel(serial)
        return true
    }

    public suspend fun start(serial: String, isolationMode: DnsFilterMode) {
        check(activeSerial == null) { "A session is already active" }
        activeSerial = serial
        stateFlow.value = ReverseNetSessionState.Starting
        try {
            if (!adbOperations.isClientInstalled(serial)) {
                adbOperations.installClient(serial, vendoredApk)
            }
            adbOperations.addReverseTunnel(serial)
            startRelay(isolationMode)
            adbOperations.startClientOnDevice(serial)
            armAuthorizationTimeout()
        } catch (t: Throwable) {
            stopInternal(reportError = true, error = t.message ?: t.javaClass.simpleName)
        }
    }

    public suspend fun stop() {
        stopInternal(reportError = false)
    }

    /**
     * Non-suspend release for page disposal (eviction/deactivation): best-effort runs the full
     * [stop] sequence on the session scope so the relay thread stops, the adb reverse tunnel is
     * removed, and the device client is stopped — without blocking the composition thread. Safe
     * to call with no active session (no-op).
     */
    public fun close() {
        if (activeSerial == null && relayEngine == null) return
        scope.launch {
            try {
                stop()
            } catch (_: Throwable) {
                // best-effort cleanup during dispose
            }
        }
    }

    /** Called by the tap listener when the device tunnel connects (client id handshake done). */
    public fun onTunnelConnected() {
        if (stateFlow.value is ReverseNetSessionState.Starting ||
            stateFlow.value is ReverseNetSessionState.AwaitingVpnAuthorization
        ) {
            authorizationTimeoutJob?.cancel()
            stateFlow.value = ReverseNetSessionState.Sharing
        }
    }

    /** Called when the device tunnel disconnects. */
    public fun onTunnelDisconnected() {
        if (stateFlow.value == ReverseNetSessionState.Sharing) {
            stateFlow.value = ReverseNetSessionState.AwaitingVpnAuthorization
            armAuthorizationTimeout()
        }
    }

    private fun startRelay(isolationMode: DnsFilterMode) {
        val listener = relayListenerOverride ?: TapEventBridge(eventBus)
        val engine = relayFactory?.invoke(relayPort, WrapListener(this, listener))
            ?: RelayEngine(relayPort, WrapListener(this, listener))
        engine.start()
        relayEngine = engine
        val thread = Thread({ engine.runLoop() }, "aps-reverse-net-relay")
        thread.isDaemon = false // keep the process alive while sharing; cleared on stop
        thread.start()
        relayThread = thread
    }

    private fun armAuthorizationTimeout() {
        authorizationTimeoutJob?.cancel()
        authorizationTimeoutJob = scope.launch {
            delay(ReverseNetSessionConfig.DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS)
            if (stateFlow.value == ReverseNetSessionState.Starting) {
                stateFlow.value = ReverseNetSessionState.AwaitingVpnAuthorization
            }
        }
    }

    private suspend fun stopInternal(reportError: Boolean, error: String? = null) {
        stateFlow.value = ReverseNetSessionState.Stopping
        authorizationTimeoutJob?.cancel()
        val serial = activeSerial
        if (serial != null) {
            adbOperations.stopClientOnDevice(serial)
            adbOperations.removeReverseTunnel(serial)
        }
        relayEngine?.stop()
        relayEngine = null
        relayThread = null
        activeSerial = null
        stateFlow.value = if (reportError && error != null) {
            ReverseNetSessionState.Error(error)
        } else {
            ReverseNetSessionState.Idle
        }
    }

    /** JVM shutdown hook: best-effort cleanup of relay and device state. */
    public fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                try {
                    kotlinx.coroutines.runBlocking { stop() }
                } catch (t: Throwable) {
                    // best effort during JVM shutdown
                }
            }, "aps-reverse-net-shutdown"),
        )
    }

    /** Forwards relay listener events to both the tap bridge and session state tracking. */
    private class WrapListener(
        private val controller: SessionController,
        private val delegate: RelayListener,
    ) : RelayListener {
        override fun onClientConnected(clientId: Int) {
            controller.onTunnelConnected()
            delegate.onClientConnected(clientId)
        }

        override fun onClientDisconnected(clientId: Int) {
            controller.onTunnelDisconnected()
            delegate.onClientDisconnected(clientId)
        }

        override fun onConnectionOpened(connection: com.androidperformancestudio.reversenet.relay.Connection, id: com.androidperformancestudio.reversenet.relay.ConnectionId) =
            delegate.onConnectionOpened(connection, id)

        override fun onConnectionClosed(connection: com.androidperformancestudio.reversenet.relay.Connection, id: com.androidperformancestudio.reversenet.relay.ConnectionId) =
            delegate.onConnectionClosed(connection, id)

        override fun onPacketDropped(packet: com.androidperformancestudio.reversenet.relay.IPv4Packet) =
            delegate.onPacketDropped(packet)
    }
}
