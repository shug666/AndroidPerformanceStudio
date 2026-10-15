package com.androidperformancestudio.frame.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FrameProfilerScreenFilterTest {
    @Test
    fun `source and jank filters update the problem frame list`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    listOf(
                        sample(1, FrameSource.GFXINFO, 8_000_000L),
                        sample(2, FrameSource.PERFETTO, 24_000_000L),
                        sample(3, FrameSource.PERFETTO, 8_000_000L).copy(jankStatsJank = true),
                    ),
                )

            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        state = FrameProfilerState(analysis = analysis, selectedFrameId = 1),
                        actions = FrameProfilerActions(),
                        language = UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithContentDescription("Source").performClick()
            onNodeWithText("Perfetto").performClick()
            onNodeWithTag("problem-frame-row-1", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("problem-frame-row-2", useUnmergedTree = true).assertExists()
            onNodeWithTag("problem-frame-row-3", useUnmergedTree = true).assertExists()

            onNodeWithContentDescription("Filter").performClick()
            onNodeWithText("Jank Only").performClick()
            onNodeWithTag("problem-frame-row-2", useUnmergedTree = true).assertExists()
            onNodeWithTag("problem-frame-row-3", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `source and frame filters are disabled while capture updates`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis = FrameJankAnalyzer().analyze(listOf(sample(1, FrameSource.GFXINFO, 8_000_000L)))

            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        state = FrameProfilerState(analysis = analysis, isCapturing = true),
                        actions = FrameProfilerActions(),
                        language = UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithContentDescription("Source").assertIsNotEnabled()
            onNodeWithContentDescription("Filter").assertIsNotEnabled()
        }

    private fun sample(
        frameId: Long,
        source: FrameSource,
        durationNs: Long,
    ): FrameSample =
        FrameSample(
            frameId = frameId,
            sessionId = "session-1",
            source = source,
            totalDurationNs = durationNs,
            intendedVsyncNs = frameId * 20_000_000L,
            expectedDurationNs = 16_666_667L,
            expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL,
        )
}
