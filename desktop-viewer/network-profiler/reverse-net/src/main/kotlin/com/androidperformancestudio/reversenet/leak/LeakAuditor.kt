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
@file:Suppress("SwallowedException", "ReturnCount")

package com.androidperformancestudio.reversenet.leak

import com.androidperformancestudio.reversenet.events.ReverseNetAlertKind
import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Shell probe executed on the device, isolated for testability. */
public interface DeviceProbe {
    /** Runs a shell command on the device, returning trimmed stdout or null on failure. */
    public suspend fun shell(serial: String, command: List<String>): String?
}

/** Audit result for one probe cycle. */
public enum class AuditOutcome { LEAK, NO_V6_EXIT, NO_NON_TUNNEL_NETWORK, PROBE_DEGRADED }

/**
 * Periodically audits whether the device can still reach the internet over IPv6 while a
 * reverse-tethering session is active. Only runs when the device has a non-tunnel network;
 * probe failures degrade silently (counted, never interpreted as a leak).
 */
public class LeakAuditor(
    private val eventBus: ReverseNetEventBus,
    private val probe: DeviceProbe,
    private val intervalMs: Long = AUDIT_INTERVAL_MS,
) {
    private var auditJob: Job? = null
    private val probeFailureCount = AtomicLong()

    public fun start(scope: CoroutineScope, serial: String) {
        stop()
        auditJob = scope.launch {
            while (isActive) {
                try {
                    auditOnce(serial)
                } catch (t: Throwable) {
                    probeFailureCount.incrementAndGet()
                }
                delay(intervalMs)
            }
        }
    }

    public fun stop() {
        auditJob?.cancel()
        auditJob = null
    }

    public fun probeFailures(): Long = probeFailureCount.get()

    /** One audit cycle; visible for testing. */
    public suspend fun auditOnce(serial: String): AuditOutcome {
        val connectivity = probe.shell(serial, listOf("dumpsys", "connectivity")) ?: return degraded()
        if (!hasNonTunnelNetwork(connectivity)) {
            return AuditOutcome.NO_NON_TUNNEL_NETWORK
        }
        // Main probe: can the device ping an external IPv6 address?
        val ping = probe.shell(serial, listOf("ping6", "-c", "1", "-W", "2", PROBE_TARGET_V6))
        if (ping != null && ping.contains("1 received", ignoreCase = true)) {
            emitLeak()
            return AuditOutcome.LEAK
        }
        // Fallback probe: does a v6 route to the default gateway exist?
        val route = probe.shell(serial, listOf("ip", "-6", "route", "get", PROBE_TARGET_V6))
        if (route != null && route.contains("via") && !route.contains("unreachable")) {
            emitLeak()
            return AuditOutcome.LEAK
        }
        if (ping == null && route == null) {
            // Both probes unavailable: degrade silently
            return degraded()
        }
        return AuditOutcome.NO_V6_EXIT
    }

    private fun degraded(): AuditOutcome {
        probeFailureCount.incrementAndGet()
        return AuditOutcome.PROBE_DEGRADED
    }

    private fun emitLeak() {
        eventBus.emit(
            ReverseNetEvent.Alert(
                alertKind = ReverseNetAlertKind.V6_LEAK,
                message = LEAK_MESSAGE,
                monotonicNs = System.nanoTime(),
            ),
        )
    }

    public companion object {
        public const val AUDIT_INTERVAL_MS: Long = 30_000
        public const val PROBE_TARGET_V6: String = "2001:4860:4860::8888"
        public const val LEAK_MESSAGE: String =
            "设备仍可通过 IPv6 直接访问互联网（未走供网隧道）。建议关闭设备 WiFi 或开启飞行模式。"

        /** True when dumpsys connectivity shows an active network besides the VPN tunnel. */
        public fun hasNonTunnelNetwork(dumpsysOutput: String): Boolean {
            // "Active default network: X" with X != -1 indicates a non-tunnel default network
            val regex = Regex("Active default network:\\s*(-?\\d+)")
            val match = regex.find(dumpsysOutput) ?: return false
            return match.groupValues[1] != "-1"
        }
    }
}
