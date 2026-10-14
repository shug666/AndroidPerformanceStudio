package com.androidperformancestudio.perfetto.capture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoCaptureCommandTest {
    @Test
    fun `capture writes traces to the Android Perfetto trace directory`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        assertTrue(source.contains("/data/misc/perfetto-traces/aps-perfetto-trace.pftrace"))
        assertFalse(source.contains("/data/local/tmp/aps-perfetto-trace.pftrace"))
    }

    @Test
    fun `capture runs perfetto in the foreground without background-wait`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        // Official command shape: cat config | perfetto -c - --txt -o <trace>
        assertTrue(source.contains("perfetto -c - --txt -o"))
        assertFalse(source.contains("--background-wait"))
    }

    @Test
    fun `capture does not rely on background PID polling`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        assertFalse(source.contains("waitForRemoteStop"))
        assertFalse(source.contains("stopRemoteCapture"))
        assertFalse(source.contains("kill -0"))
        assertFalse(source.contains("activePerfettoPid"))
    }

    @Test
    fun `capture timeout is derived from the capture duration`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        // The shell timeout must scale with the capture duration rather than a fixed 60s.
        assertTrue(source.contains("CAPTURE_TIMEOUT_MARGIN_SECONDS"))
        assertTrue(source.contains("config.durationSeconds"))
    }
}
