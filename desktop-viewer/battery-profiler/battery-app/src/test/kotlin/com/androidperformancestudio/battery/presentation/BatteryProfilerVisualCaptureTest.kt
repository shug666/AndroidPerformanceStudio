@file:Suppress("MagicNumber", "LongMethod")

package com.androidperformancestudio.battery.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.battery.app.BatterySessionWorkspace
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BatteryProfilerVisualCaptureTest {
    @Test
    fun `battery evidence workspace renders real snapshots history tracks and resource inspector`() =
        runDesktopComposeUiTest(width = 1440, height = 1120) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().testTag("battery-visual-capture")) {
                        val state = batteryVisualState()
                        BatterySessionWorkspace(
                            state = state,
                            language = UiLanguage.SIMPLIFIED_CHINESE,
                            onOpenSession = {},
                            onNewExperiment = {},
                            onRefreshSessions = {},
                        ) {
                            BatteryProfilerScreen(state, BatteryProfilerActions(), UiLanguage.SIMPLIFIED_CHINESE)
                        }
                    }
                }
            }
            onNodeWithTag("battery-session-visual-session").assertExists()
            onNodeWithTag("battery-snapshot-chart").assertExists()
            BatteryHistoryEventKind.entries.forEach { kind ->
                onNodeWithTag("battery-track-${kind.name.lowercase()}").assertExists()
            }
            onNodeWithTag("battery-row-wakelock-0").assertExists()
            onNodeWithTag("battery-evidence-inspector").assertExists()
            onNodeWithText("系统能耗估算").assertDoesNotExist()
            waitForIdle()
            val screenshot = onNodeWithTag("battery-visual-capture").captureToImage()
            assertEquals(1440, screenshot.width)
            assertEquals(1120, screenshot.height)
            val pixels = screenshot.toPixelMap()
            assertTrue(listOf(pixels[8, 8], pixels[400, 60], pixels[800, 300], pixels[1300, 600]).distinct().size > 1)
            System.getenv("APS_BATTERY_PROFILER_SCREENSHOT")?.let { output ->
                ImageIO.write(screenshot.toBufferedImage(), "png", Path.of(output).toFile())
            }
        }

    @Test
    fun `resource selection filtering network counters and untampered history are interactive`() =
        runDesktopComposeUiTest(width = 1100, height = 1000) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        BatteryRunWorkspace(batteryVisualDelta(), batteryVisualRun(), UiLanguage.ENGLISH)
                    }
                }
            }
            onNodeWithTag("battery-row-wakelock-1").performScrollTo().performClick()
            onNodeWithTag("battery-evidence-inspector").assertExists()
            onNodeWithTag("battery-row-wakelock-1").assertTextContains("Inferred", substring = true)
            onNodeWithTag("battery-evidence-search").performScrollTo().performTextInput("AudioMix")
            onNodeWithTag("battery-row-wakelock-2").assertExists()
            onNodeWithTag("battery-row-wakelock-0").assertDoesNotExist()
            onNodeWithTag("battery-tab-network").performScrollTo().performClick()
            onNodeWithTag("battery-row-wifi-rx").assertTextContains("8400000 B", substring = true)
            onNodeWithTag("battery-tab-history").performScrollTo().performClick()
            onNodeWithTag("battery-row-history-0").performScrollTo().performClick()
            onNodeWithText("raw-evidence +wakelock uid=10234").assertExists()
            onNodeWithTag("battery-tab-energy").performScrollTo().performClick()
            onNodeWithText("No matching evidence in this run.").assertExists()
        }

    @Test
    fun `narrow workspace stacks charts and keeps all evidence tabs reachable`() =
        runDesktopComposeUiTest(width = 560, height = 900) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        BatteryRunWorkspace(batteryVisualDelta(), null, UiLanguage.ENGLISH)
                    }
                }
            }
            onNodeWithText("No snapshot samples are attached to this artifact.").assertExists()
            onNodeWithTag("battery-tab-warnings").performScrollTo().performClick()
            onNodeWithTag("battery-row-warning-0").assertExists()
        }

    @Test
    fun `minimum desktop viewport keeps sessions and evidence controls reachable in light theme`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var opened: String? = null
            var newCount = 0
            var refreshCount = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().testTag("battery-minimum-viewport")) {
                        val state = batteryVisualState()
                        BatterySessionWorkspace(
                            state = state,
                            language = UiLanguage.ENGLISH,
                            onOpenSession = { opened = it },
                            onNewExperiment = { newCount++ },
                            onRefreshSessions = { refreshCount++ },
                        ) {
                            BatteryProfilerScreen(state, BatteryProfilerActions(), UiLanguage.ENGLISH)
                        }
                    }
                }
            }
            onNodeWithTag("battery-session-visual-session").assertExists()
            onNodeWithTag("battery-snapshot-chart").assertExists()
            waitForIdle()
            onNodeWithTag("battery-minimum-viewport").captureToImage().saveViewport("battery-1280x720-en-light.png")
            onNodeWithTag("battery-session-search").performTextInput("experiment3")
            onNodeWithTag("battery-session-visual-session").assertDoesNotExist()
            onNodeWithTag("battery-session-stored-3").performClick()
            assertEquals("stored-3", opened)
            onNodeWithTag("battery-session-search").performTextClearance()
            onNodeWithText("+ New").performClick()
            assertEquals(1, newCount)
            onNodeWithText("Refresh").performClick()
            assertEquals(1, refreshCount)
            onNodeWithTag("battery-tab-warnings").performScrollTo().performClick()
            onNodeWithTag("battery-row-warning-0").performScrollTo().assertExists()
            onNodeWithTag("battery-tab-history").performScrollTo().performClick()
            onNodeWithTag("battery-row-history-0").performScrollTo().performClick()
            onNodeWithTag("battery-evidence-inspector").performScrollTo().assertExists()
        }

    @Test
    fun `narrow full workspace preserves sessions chart and scrollable resource controls`() =
        runDesktopComposeUiTest(width = 720, height = 900) {
            var opened: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().testTag("battery-narrow-viewport")) {
                        val state = batteryVisualState()
                        BatterySessionWorkspace(
                            state = state,
                            language = UiLanguage.ENGLISH,
                            onOpenSession = { opened = it },
                            onNewExperiment = {},
                            onRefreshSessions = {},
                        ) {
                            BatteryProfilerScreen(state, BatteryProfilerActions(), UiLanguage.ENGLISH)
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("battery-narrow-viewport").captureToImage().saveViewport("battery-720x900-en-dark.png")
            onNodeWithTag("battery-session-search").performTextInput("sync")
            onNodeWithTag("battery-session-visual-session").performClick()
            assertEquals("visual-session", opened)
            onNodeWithTag("battery-snapshot-chart").performScrollTo().assertExists()
            onNodeWithTag("battery-tab-network").performScrollTo().performClick()
            onNodeWithTag("battery-row-wifi-rx").performScrollTo().assertExists()
            onNodeWithTag("battery-evidence-inspector").performScrollTo().assertExists()
        }
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { buffered ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[x, y]
                val argb =
                    ((color.alpha * 255).toInt() shl 24) or
                        ((color.red * 255).toInt() shl 16) or
                        ((color.green * 255).toInt() shl 8) or
                        (color.blue * 255).toInt()
                buffered.setRGB(x, y, argb)
            }
        }
    }
}

private fun ImageBitmap.saveViewport(fileName: String) {
    System.getenv("APS_BATTERY_PROFILER_SCREENSHOT_DIR")?.let { directory ->
        val target = Path.of(directory)
        Files.createDirectories(target)
        ImageIO.write(toBufferedImage(), "png", target.resolve(fileName).toFile())
    }
}
