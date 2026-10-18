package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.frame.model.JankType
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FrameProfilerVisualCaptureTest {
    @Test
    fun `workspace renders a dense dark frame analysis layout`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val analysis = FrameJankAnalyzer().analyze(sampleFrames())

            setContent {
                ViewerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().testTag("frame-profiler-visual-capture")) {
                        FrameProfilerScreen(
                            state = FrameProfilerState(analysis = analysis, selectedFrameId = 42),
                            actions = FrameProfilerActions(),
                            language = UiLanguage.SIMPLIFIED_CHINESE,
                        )
                    }
                }
            }

            onNodeWithTag("frame-timeline-canvas").performMouseInput {
                moveTo(Offset(1f, center.y))
                moveTo(Offset(center.x * 17f / 72f, center.y))
            }
            onNodeWithTag("frame-timeline-caption").assertTextContains(
                "顶部标记表示截止时间未命中区间  |  帧 #9 · 34.00 ms · MISSED",
            )
            waitForIdle()
            val screenshot = onNodeWithTag("frame-profiler-visual-capture").captureToImage()
            assertEquals(1440, screenshot.width)
            assertEquals(860, screenshot.height)

            System.getenv("APS_FRAME_PROFILER_SCREENSHOT")?.let { output ->
                ImageIO.write(screenshot.toBufferedImage(), "png", Path.of(output).toFile())
            }

            val pixels = screenshot.toPixelMap()
            val distinctColors =
                listOf(
                    pixels[8, 8],
                    pixels[screenshot.width / 4, screenshot.height / 2],
                    pixels[screenshot.width / 2, screenshot.height / 2],
                    pixels[screenshot.width * 3 / 4, screenshot.height / 2],
                    pixels[screenshot.width / 2, screenshot.height - 8],
                ).distinct()
            assertTrue(distinctColors.size > 1, "Frame Profiler workspace should render visible content")
        }

    private fun sampleFrames(): List<FrameSample> =
        (0L until 72L).map { frameId ->
            val isProblem = frameId % 18L == 8L || frameId % 18L == 9L
            FrameSample(
                frameId = frameId + 1,
                sessionId = "visual-session",
                source = if (frameId % 3L == 0L) FrameSource.FRAME_METRICS else FrameSource.PERFETTO,
                packageName = "com.example.gallery",
                activityName = "com.example.gallery.GalleryActivity",
                windowId = "SurfaceView - GalleryActivity",
                intendedVsyncNs = frameId * 16_666_667L,
                actualVsyncNs = frameId * 16_666_667L,
                presentNs = frameId * 16_666_667L + if (isProblem) 32_000_000L else 11_000_000L,
                expectedDurationNs = 16_666_667L,
                expectedDurationSource = ExpectedDurationSource.REFRESH_RATE,
                refreshRateHz = 60.0,
                totalDurationNs = if (isProblem) 34_000_000L else 12_000_000L,
                stages =
                    FrameStages(
                        inputNs = 500_000L,
                        animationNs = 1_000_000L,
                        layoutMeasureNs = 1_400_000L,
                        drawNs = if (isProblem) 8_000_000L else 3_000_000L,
                        syncNs = 2_000_000L,
                        commandIssueNs = if (isProblem) 11_000_000L else 3_000_000L,
                        swapBuffersNs = 1_200_000L,
                        gpuNs = if (isProblem) 15_000_000L else 1_000_000L,
                    ),
                platformJank = isProblem,
                platformJankTypes = if (isProblem) setOf(JankType.SLOW_DRAW, JankType.SLOW_COMMAND) else emptySet(),
                states = mapOf("screen" to "Gallery", "gesture" to if (isProblem) "Scroll" else "Idle"),
            )
        }
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[x, y]
                val argb =
                    (color.alpha.alphaByte() shl 24) or
                        (color.redByte() shl 16) or
                        (color.greenByte() shl 8) or
                        color.blueByte()
                image.setRGB(x, y, argb)
            }
        }
    }
}

private fun Float.alphaByte(): Int = (this * 255f).toInt().coerceIn(0, 255)

private fun androidx.compose.ui.graphics.Color.redByte(): Int = (red * 255f).toInt().coerceIn(0, 255)

private fun androidx.compose.ui.graphics.Color.greenByte(): Int = (green * 255f).toInt().coerceIn(0, 255)

private fun androidx.compose.ui.graphics.Color.blueByte(): Int = (blue * 255f).toInt().coerceIn(0, 255)
