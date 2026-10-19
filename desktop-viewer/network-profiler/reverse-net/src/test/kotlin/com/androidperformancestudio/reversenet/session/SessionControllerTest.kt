@file:Suppress("EmptyFunctionBlock", "MagicNumber")

package com.androidperformancestudio.reversenet.session

import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

private class FakeAdbOperations(
    private val failReverse: Boolean = false,
    private val failInstall: Boolean = false,
) : ReverseNetAdbOperations {
    public val installed: Boolean = false
    public val reverseCalls = mutableListOf<String>()
    public val stopClientCalls = mutableListOf<String>()

    override suspend fun isClientInstalled(serial: String): Boolean = installed

    override suspend fun installClient(serial: String, apkPath: Path) {
        if (failInstall) {
            error("install failed")
        }
    }

    override suspend fun addReverseTunnel(serial: String) {
        if (failReverse) {
            error("reverse failed: device offline")
        }
        reverseCalls.add(serial)
    }

    override suspend fun removeReverseTunnel(serial: String) {
        reverseCalls.remove(serial)
    }

    override suspend fun listReverseTunnels(serial: String): List<String> = reverseCalls.map { "localabstract:gnirehtet tcp:31416" }

    override suspend fun startClientOnDevice(serial: String) {}

    override suspend fun stopClientOnDevice(serial: String) {
        stopClientCalls.add(serial)
    }
}

private const val SERIAL = "FAKE_SERIAL"
private val APK = Path.of("build/tmp/fake-gnirehtet.apk")

/** Relay engine stand-in that never binds a socket; sessions drive it through the factory. */
private class FakeRelayEngine(
    listener: com.androidperformancestudio.reversenet.relay.RelayListener,
) : com.androidperformancestudio.reversenet.relay.RelayEngine(0, listener) {
    private var running = false

    override fun start() {
        running = true
    }

    override fun runLoop() {
        // no-op: never binds or selects
    }

    override fun stop() {
        running = false
    }
}

public class SessionControllerTest {
    @Test
    public fun `happy path transitions to awaiting authorization then sharing on tunnel connect`() {
        val adb = FakeAdbOperations()
        val controller = SessionController(adb, APK, ReverseNetEventBus(), relayPort = 0, relayFactory = { _, listener ->
            FakeRelayEngine(listener)
        })
        runBlocking {
            controller.start(SERIAL, DnsFilterMode.RELAXED)
        }
        assertEquals(ReverseNetSessionState.Starting, controller.state.value)
        controller.onTunnelConnected()
        assertEquals(ReverseNetSessionState.Sharing, controller.state.value)
        runBlocking { controller.stop() }
        assertEquals(ReverseNetSessionState.Idle, controller.state.value)
        assertTrue(adb.stopClientCalls.contains(SERIAL))
        assertTrue(adb.reverseCalls.isEmpty())
    }

    @Test
    public fun `reverse failure maps to error state`() {
        val adb = FakeAdbOperations(failReverse = true)
        val controller = SessionController(adb, APK, ReverseNetEventBus(), relayPort = 0, relayFactory = { _, listener ->
            FakeRelayEngine(listener)
        })
        runBlocking {
            controller.start(SERIAL, DnsFilterMode.RELAXED)
        }
        assertTrue(controller.state.value is ReverseNetSessionState.Error)
        assertEquals("reverse failed: device offline", (controller.state.value as ReverseNetSessionState.Error).message)
    }

    @Test
    public fun `installation failure maps to error state`() {
        val adb = FakeAdbOperations(failInstall = true)
        val controller = SessionController(adb, APK, ReverseNetEventBus(), relayPort = 0, relayFactory = { _, listener ->
            FakeRelayEngine(listener)
        })
        runBlocking {
            controller.start(SERIAL, DnsFilterMode.RELAXED)
        }
        assertTrue(controller.state.value is ReverseNetSessionState.Error)
    }

    @Test
    public fun `authorization timeout transitions to awaiting vpn authorization`() {
        val adb = FakeAdbOperations()
        val controller = SessionController(adb, APK, ReverseNetEventBus(), relayPort = 0, relayFactory = { _, listener ->
            FakeRelayEngine(listener)
        })
        val latch = CountDownLatch(1)
        runBlocking {
            controller.start(SERIAL, DnsFilterMode.RELAXED)
            // do not connect the tunnel; wait past the (shortened) timeout by observing state
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (controller.state.value is ReverseNetSessionState.AwaitingVpnAuthorization) {
                    break
                }
                kotlinx.coroutines.delay(200)
            }
        }
        assertTrue(controller.state.value is ReverseNetSessionState.AwaitingVpnAuthorization)
        runBlocking { controller.stop() }
        latch.countDown()
    }

    @Test
    public fun `leftover tunnel detection recovers state`() {
        val adb = FakeAdbOperations()
        runBlocking {
            adb.addReverseTunnel(SERIAL)
        }
        val controller = SessionController(adb, APK, ReverseNetEventBus(), relayPort = 0, relayFactory = { _, listener ->
            FakeRelayEngine(listener)
        })
        val recovered = runBlocking { controller.recoverLeftovers(SERIAL) }
        assertTrue(recovered)
        assertTrue(adb.reverseCalls.isEmpty())
        val none = runBlocking { controller.recoverLeftovers(SERIAL) }
        assertTrue(!none)
    }
}
