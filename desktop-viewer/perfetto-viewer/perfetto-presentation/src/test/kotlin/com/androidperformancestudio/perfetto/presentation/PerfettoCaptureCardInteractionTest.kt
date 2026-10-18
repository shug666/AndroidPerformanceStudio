package com.androidperformancestudio.perfetto.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PerfettoCaptureCardInteractionTest {
    @Test
    fun `template card selection toggles custom fields and restores data source cards`() =
        runDesktopComposeUiTest(width = 1200, height = 900) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    PerfettoCapturePage(
                        captureState = PerfettoCaptureState.Idle,
                        language = UiLanguage.ENGLISH,
                        onStartCapture = { _, _ -> },
                        onStopCapture = {},
                        onOpenTrace = {},
                    )
                }
            }

            onNodeWithText("CPU").assertIsDisplayed()
            onNodeWithText("Custom").performScrollTo().performClick()
            onNodeWithText("Custom TraceConfig (text protobuf)").assertIsDisplayed()
            onNodeWithText("CPU").assertDoesNotExist()
            onNodeWithText("System Overview").performScrollTo().performClick()
            onNodeWithText("CPU").assertIsDisplayed()
            onNodeWithText("Custom TraceConfig (text protobuf)").assertDoesNotExist()
        }
}
