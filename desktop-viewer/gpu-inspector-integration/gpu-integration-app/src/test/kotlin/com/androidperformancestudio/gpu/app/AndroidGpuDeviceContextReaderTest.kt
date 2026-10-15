package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandCancelledException
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AndroidGpuDeviceContextReaderTest {
    @Test
    fun `inspects only the selected serial with bounded shared adb shell commands`() = runBlocking {
        val client = RecordingAdbClient { arguments ->
            success(
                if (arguments == listOf("getprop")) {
                    "[ro.product.model]: [Current model]\n[ro.build.version.sdk]: [36]"
                } else {
                    "GLES: Vendor, Renderer, OpenGL ES 3.2 V@0800.20"
                },
            )
        }

        val context = reader(client).inspect(device())

        assertEquals(listOf("serial-1", "serial-1"), client.calls.map { it.serial })
        assertEquals(listOf(listOf("getprop"), listOf("dumpsys", "SurfaceFlinger")), client.calls.map { it.arguments })
        assertEquals(listOf(12.seconds, 12.seconds), client.calls.map { it.timeout })
        assertEquals("Current model", context.model)
        assertEquals("Renderer", context.gpuRenderer)
        assertEquals("V@0800.20", context.driverVersion)
    }

    @Test
    fun `device discovery uses the shared monitor and excludes offline unauthorized devices`() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val monitor = AndroidTargetMonitors.create(Path.of("adb")) { request, _ ->
            requests += request.arguments
            completed(
                request.command,
                if (request.arguments == listOf("devices", "-l")) {
                    "List of devices attached\nserial-1 device model:Device\nserial-2 offline\nserial-3 unauthorized\n"
                } else {
                    "[ro.product.model]: [Connected device]"
                },
            )
        }

        val contexts = AndroidGpuDeviceContextReader(Path.of("adb"), monitor).discoverDevices()

        assertEquals(listOf("serial-1"), contexts.map { it.serial })
        assertEquals("Connected device", contexts.single().model)
        assertNull(contexts.single().gpuRenderer)
        assertTrue(contexts.single().graphicsApis.isEmpty())
        assertEquals(listOf("devices", "-l"), requests.first())
        assertEquals(listOf("-s", "serial-1", "shell", "getprop"), requests.last())
    }

    @Test
    fun `discovery failure remains an error instead of an empty list`() = runBlocking {
        val monitor = AndroidTargetMonitors.create(Path.of("adb")) { _, _ ->
            HostCommandResult.Failed(
                StudioError(ErrorCategory.PROCESS_TIMEOUT, "PROCESS_TIMED_OUT", "ADB device list timed out"),
            )
        }
        val failure = assertFailsWith<IllegalStateException> {
            AndroidGpuDeviceContextReader(Path.of("adb"), monitor).discoverDevices()
        }

        assertTrue(failure.message.orEmpty().contains("device discovery failed"))
        assertTrue(failure.message.orEmpty().contains("timed out"))
    }

    @Test
    fun `getprop failure aborts inspection and does not fetch SurfaceFlinger`() = runBlocking {
        val client = RecordingAdbClient { AdbTextResult(1, "", "device offline", Duration.ZERO) }
        val failure = assertFailsWith<IllegalStateException> { reader(client).inspect(device()) }

        assertTrue(failure.message.orEmpty().contains("serial-1 (getprop)"))
        assertTrue(failure.message.orEmpty().contains("device offline"))
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `SurfaceFlinger timeout does not become a successful partial observation`() = runBlocking {
        val client = RecordingAdbClient { arguments ->
            if (arguments == listOf("getprop")) {
                success("[ro.build.version.sdk]: [36]")
            } else {
                throw AdbCommandTimeoutException(listOf("adb", "-s", "serial-1", "shell", "dumpsys", "SurfaceFlinger"), 12.seconds)
            }
        }
        val failure = assertFailsWith<IllegalStateException> { reader(client).inspect(device()) }

        assertTrue(failure.message.orEmpty().contains("dumpsys SurfaceFlinger"))
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertIs<AdbCommandTimeoutException>(failure.cause)
    }

    @Test
    fun `truncated evidence is rejected rather than parsed as complete`() = runBlocking {
        val client = RecordingAdbClient { success("[ro.build.version.sdk]: [36]").copy(stdoutTruncated = true) }
        val failure = assertFailsWith<IllegalStateException> { reader(client).inspect(device()) }

        assertTrue(failure.message.orEmpty().contains("truncated"))
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `cancellation is propagated unchanged`() = runBlocking {
        val cancellation = AdbCommandCancelledException(listOf("adb", "shell", "getprop"))
        val client = RecordingAdbClient { throw cancellation }
        val failure = assertFailsWith<AdbCommandCancelledException> { reader(client).inspect(device()) }

        assertSame(cancellation, failure)
    }

    @Test
    fun `missing serial is rejected before any adb command`() = runBlocking {
        val client = RecordingAdbClient { success("") }

        assertFailsWith<IllegalArgumentException> { reader(client).inspect(device().copy(serial = null)) }
        assertFailsWith<IllegalArgumentException> { reader(client).inspect(device().copy(serial = " ")) }
        assertTrue(client.calls.isEmpty())
    }

    private fun reader(client: AdbClient) = AndroidGpuDeviceContextReader(Path.of("adb"), adbClient = client)

    private fun device() = GpuDeviceContext("serial-1", "Device", null, null, null, null)

    private fun success(stdout: String) = AdbTextResult(0, stdout, "", Duration.ZERO)

    private fun completed(command: List<String>, stdout: String) = HostCommandResult.Completed(
        HostCommandOutput(
            pid = 1,
            command = command,
            exitCode = 0,
            stdout = HostCapturedText(stdout, false),
            stderr = HostCapturedText("", false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        ),
    )

    private data class ShellCall(
        val serial: String,
        val arguments: List<String>,
        val timeout: Duration,
    )

    private class RecordingAdbClient(
        private val shellResult: (List<String>) -> AdbTextResult,
    ) : AdbClient by DefaultAdbClient(Path.of("unused")) {
        val calls = mutableListOf<ShellCall>()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            calls += ShellCall(serial, arguments, timeout)
            return shellResult(arguments)
        }
    }
}
