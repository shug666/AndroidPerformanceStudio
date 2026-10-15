package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FramePerfettoNavigationWiringTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
        )

    @Test
    fun `frame correlation forwards intended vsync timestamp to Perfetto`() {
        val frameHandlerStart = source.indexOf("onOpenPerfetto = { request ->")
        val frameHandlerEnd = source.indexOf("AppDestination.STARTUP_PROFILER", frameHandlerStart)
        val frameHandler = source.substring(frameHandlerStart, frameHandlerEnd)

        assertTrue(frameHandler.contains("request.traceFile"))
        assertTrue(frameHandler.contains("request.intendedVsyncNs"))
        assertTrue(
            Regex(
                "navigator\\.openPerfettoTrace\\([\\s\\S]*request\\.traceFile,[\\s\\S]*request\\.intendedVsyncNs,[\\s\\S]*\\)",
            ).containsMatchIn(frameHandler),
        )
    }
}
