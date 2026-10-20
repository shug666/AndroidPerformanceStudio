package com.androidperformancestudio.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorEvictionTest {
    @Test
    fun `closing an inactive module keeps the active destination`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.PERFETTO)
        navigator.open(AppDestination.MEMORY_PROFILER)

        navigator.evict(AppDestination.PERFETTO)

        assertEquals(AppDestination.MEMORY_PROFILER, navigator.destination)
        assertFalse(navigator.retainedDestinations.contains(AppDestination.PERFETTO))
        assertEquals(
            listOf(AppDestination.HOME, AppDestination.MEMORY_PROFILER),
            navigator.retainedDestinations,
        )
    }

    @Test
    fun `closing the active module returns to home`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.FRAME_PROFILER)

        navigator.evict(AppDestination.FRAME_PROFILER)

        assertEquals(AppDestination.HOME, navigator.destination)
        assertFalse(navigator.retainedDestinations.contains(AppDestination.FRAME_PROFILER))
    }

    @Test
    fun `home cannot be evicted`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.PERFETTO)

        navigator.evict(AppDestination.HOME)

        assertEquals(AppDestination.PERFETTO, navigator.destination)
        assertTrue(navigator.retainedDestinations.contains(AppDestination.HOME))
    }

    @Test
    fun `evictAll keeps home and the active module`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.PERFETTO)
        navigator.open(AppDestination.MEMORY_PROFILER)
        navigator.open(AppDestination.FRAME_PROFILER)

        navigator.evictAll()

        assertEquals(AppDestination.FRAME_PROFILER, navigator.destination)
        assertEquals(
            listOf(AppDestination.HOME, AppDestination.FRAME_PROFILER),
            navigator.retainedDestinations,
        )
    }

    @Test
    fun `evicting perfetto clears only its pending trace intent`() {
        val navigator = AppNavigator()
        navigator.openPerfettoTrace(Path.of("capture.perfetto-trace"), notice = "from frame")
        navigator.openMemoryProfiler(Path.of("capture.hprof"))

        navigator.evict(AppDestination.PERFETTO)

        assertNull(navigator.perfettoTraceFile)
        assertNull(navigator.perfettoTraceNotice)
        // The memory import intent targeting a different module is untouched.
        assertEquals(Path.of("capture.hprof"), navigator.memoryImportFile)
    }

    @Test
    fun `evicting one module does not clear another module handoff`() {
        val navigator = AppNavigator()
        val trace = Path.of("capture.perfetto-trace")
        navigator.openPerfettoTrace(trace, "from frame correlation")

        navigator.evict(AppDestination.BATTERY_NETWORK)

        assertEquals(trace, navigator.perfettoTraceFile)
        assertEquals("from frame correlation", navigator.perfettoTraceNotice)
    }

    @Test
    fun `evicting layout inspector clears its archive request and correlation hint`() {
        val navigator = AppNavigator()
        val hint =
            InspectorCorrelationHint(
                deviceSerial = "device",
                targetPackageName = "dev.example",
                message = "Frame #7",
                correlationNotice = "correlation only",
                foregroundMismatchPrefix = "package differs",
            )
        navigator.openLayoutInspector(hint)
        navigator.open(AppDestination.HOME)

        navigator.evict(AppDestination.LAYOUT_INSPECTOR)

        assertNull(navigator.layoutArchiveRequest)
        assertNull(navigator.inspectorCorrelationHint)
    }

    @Test
    fun `a closed module can be reopened fresh`() {
        val navigator = AppNavigator()
        navigator.open(AppDestination.PERFETTO)
        navigator.evict(AppDestination.PERFETTO)

        navigator.open(AppDestination.PERFETTO)

        assertEquals(AppDestination.PERFETTO, navigator.destination)
        assertTrue(navigator.retainedDestinations.contains(AppDestination.PERFETTO))
    }
}
