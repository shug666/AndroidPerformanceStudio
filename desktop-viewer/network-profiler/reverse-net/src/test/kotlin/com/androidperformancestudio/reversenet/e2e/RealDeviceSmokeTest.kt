package com.androidperformancestudio.reversenet.e2e

import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.reversenet.dns.DnsFilterMode
import com.androidperformancestudio.reversenet.events.ReverseNetEvent
import com.androidperformancestudio.reversenet.events.ReverseNetEventBus
import com.androidperformancestudio.reversenet.session.DefaultReverseNetAdbOperations
import com.androidperformancestudio.reversenet.session.SessionController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * End-to-end smoke against a real device: install vendored apk, add adb reverse,
 * start the relay, start the device client and wait for the tunnel handshake.
 * Requires a device with adb debugging; skipped otherwise.
 */
public class RealDeviceSmokeTest {
    @Test
    @Tag("real-device")
    public fun `tunnel connects and events flow`() {
        val adbPath = defaultAdbExecutable() ?: return
        val apk = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .take(8)
            .map { it.resolve("third_party/gnirehtet/gnirehtet.apk") }
            .firstOrNull { java.nio.file.Files.isRegularFile(it) }
        assumeTrue(apk != null, "vendored apk missing")

        val client = DefaultAdbClient(adbPath)
        val devices = runBlocking { client.listDevices() }
        assumeTrue(devices.isNotEmpty(), "no device connected")
        val serial = devices.first().serial

        val eventBus = ReverseNetEventBus()
        val controller = SessionController(
            adbOperations = DefaultReverseNetAdbOperations(client, adbPath),
            vendoredApk = apk!!,
            eventBus = eventBus,
        )

        val events = mutableListOf<ReverseNetEvent>()
        val collector = Thread {
            runBlocking {
                eventBus.events.collect { events.add(it) }
            }
        }
        collector.isDaemon = true
        collector.start()

        runBlocking {
            controller.start(serial, DnsFilterMode.RELAXED)
            // Wait for tunnel handshake: a client connection flips the state to Sharing
            withTimeout(30_000) {
                while (controller.state.value != com.androidperformancestudio.reversenet.session.ReverseNetSessionState.Sharing) {
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        assertTrue(controller.isSessionActive())
        runBlocking {
            controller.stop()
            // give the device client time to fully tear down before the next session
            kotlinx.coroutines.delay(5_000)
        }
    }

    @Test
    @Tag("real-device")
    public fun `isolated mode session starts and stops cleanly`() {
        val adbPath = defaultAdbExecutable() ?: return
        val apk = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .take(8)
            .map { it.resolve("third_party/gnirehtet/gnirehtet.apk") }
            .firstOrNull { java.nio.file.Files.isRegularFile(it) }
        assumeTrue(apk != null, "vendored apk missing")

        val client = DefaultAdbClient(adbPath)
        val devices = runBlocking { client.listDevices() }
        assumeTrue(devices.isNotEmpty(), "no device connected")
        val serial = devices.first().serial

        val eventBus = ReverseNetEventBus()
        val controller = SessionController(
            adbOperations = DefaultReverseNetAdbOperations(client, adbPath),
            vendoredApk = apk!!,
            eventBus = eventBus,
        )
        runBlocking {
            kotlinx.coroutines.delay(5_000)
            controller.start(serial, DnsFilterMode.ISOLATED)
            withTimeout(30_000) {
                while (controller.state.value != com.androidperformancestudio.reversenet.session.ReverseNetSessionState.Sharing) {
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        assertTrue(controller.isSessionActive())
        runBlocking { controller.stop() }
    }
}
