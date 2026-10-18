package com.androidperformancestudio.desktop

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.battery.presentation.BatteryProfilerActions
import com.androidperformancestudio.battery.presentation.BatteryProfilerScreen
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class BatteryNetworkInteractionTest {
    @Test
    fun `compact composite keeps tabs and both child empty and error states`() =
        runDesktopComposeUiTest(width = 720, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    BatteryNetworkWorkspace(
                        language = UiLanguage.ENGLISH,
                        batteryContent = {
                            BatteryProfilerScreen(
                                state = BatteryProfilerState(operationMessage = "Battery capture pending - fixture"),
                                actions = BatteryProfilerActions(),
                                language = UiLanguage.ENGLISH,
                            )
                        },
                        networkContent = { Text("Network import failed - fixture") },
                    )
                }
            }
            onNodeWithText("Battery capture pending - fixture").assertIsDisplayed()
            onNodeWithText("Energy Evidence").assertIsDisplayed()
            onNodeWithText("Network", useUnmergedTree = true).performClick()
            onNodeWithText("Network import failed - fixture").assertIsDisplayed()
            onNodeWithText("Battery", useUnmergedTree = true).performClick()
            onNodeWithText("Battery capture pending - fixture").assertIsDisplayed()
        }

    @Test
    fun `tabs retain independent state and expose unavailable correlation`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    BatteryNetworkWorkspace(
                        language = UiLanguage.ENGLISH,
                        batteryContent = {
                            var count by remember { mutableStateOf(0) }
                            Button(onClick = { count++ }) { Text("Battery selections: $count") }
                        },
                        networkContent = { Text("Network import failed - fixture") },
                    )
                }
            }
            onNodeWithText("Battery selections: 0").performClick()
            onNodeWithText("Network", useUnmergedTree = true).performClick()
            onNodeWithText("Network import failed - fixture").assertIsDisplayed()
            onNodeWithText("Battery", useUnmergedTree = true).performClick()
            onNodeWithText("Battery selections: 1").assertIsDisplayed()
            onNodeWithText("Correlation", useUnmergedTree = true).performClick()
            onNodeWithText("These artifacts are independent evidence and cannot be aligned on one clock.")
                .assertIsDisplayed()
            System.getenv("APS_BATTERY_NETWORK_SCREENSHOT")?.let { output ->
                val bitmap = onNodeWithTag("battery-network-workspace").captureToImage()
                val pixels = bitmap.toPixelMap()
                val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
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
                File(output).parentFile.mkdirs()
                ImageIO.write(image, "png", File(output))
            }
        }
}
