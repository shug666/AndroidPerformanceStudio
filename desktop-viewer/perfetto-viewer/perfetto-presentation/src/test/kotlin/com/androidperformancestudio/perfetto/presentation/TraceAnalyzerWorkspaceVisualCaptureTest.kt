@file:Suppress("MagicNumber")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TraceAnalyzerWorkspaceVisualCaptureTest {
    @Test
    fun `wide dark Chinese workspace can be captured for reference review`() =
        captureWorkspace(
            width = 1280,
            height = 800,
            darkTheme = true,
            language = UiLanguage.SIMPLIFIED_CHINESE,
            outputEnvironmentVariable = "APS_TRACE_ANALYZER_DARK_SCREENSHOT",
        )

    @Test
    fun `wide light English workspace can be captured for reference review`() =
        captureWorkspace(
            width = 1280,
            height = 800,
            darkTheme = false,
            language = UiLanguage.ENGLISH,
            outputEnvironmentVariable = "APS_TRACE_ANALYZER_LIGHT_SCREENSHOT",
        )

    @Test
    fun `narrow workspace can be captured for adaptive review`() =
        captureWorkspace(
            width = 900,
            height = 720,
            darkTheme = true,
            language = UiLanguage.SIMPLIFIED_CHINESE,
            outputEnvironmentVariable = "APS_TRACE_ANALYZER_NARROW_SCREENSHOT",
        )

    private fun captureWorkspace(
        width: Int,
        height: Int,
        darkTheme: Boolean,
        language: UiLanguage,
        outputEnvironmentVariable: String,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            ViewerTheme(darkTheme = darkTheme) {
                TraceAnalyzerWorkspace(
                    state = traceWorkspaceState(),
                    actions = TraceAnalyzerWorkspaceActions(),
                    language = language,
                )
            }
        }

        waitForIdle()
        val screenshot = onNodeWithTag("trace-analyzer-workspace").captureToImage()
        assertEquals(width, screenshot.width)
        assertEquals(height, screenshot.height)
        System.getenv(outputEnvironmentVariable)?.let { output ->
            val path = Path.of(output)
            path.parent?.let(Files::createDirectories)
            ImageIO.write(screenshot.toBufferedImage(), "png", path.toFile())
        }
    }
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[x, y]
                image.setRGB(
                    x,
                    y,
                    ((color.alpha * 255).toInt() shl 24) or
                        ((color.red * 255).toInt() shl 16) or
                        ((color.green * 255).toInt() shl 8) or
                        (color.blue * 255).toInt(),
                )
            }
        }
    }
}
