@file:Suppress("MagicNumber")

package com.androidperformancestudio.startup.presentation

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.viewerColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StartupProfilerWorkspaceTest {
    @Test
    fun `Startup evidence panel uses the shared palette without a second surface tint`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(StartupProfilerState(), StartupProfilerActions(), UiLanguage.ENGLISH)
                }
            }

            val panel = onNodeWithTag("startup-profile-status").captureToImage().toPixelMap()
            assertEquals(viewerColors(true).panel, panel[3, panel.height / 2])
        }

    @Test
    fun `empty workspace keeps scenario controls and type tabs available without a top banner`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var requested: StartupType? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        StartupProfilerState(),
                        StartupProfilerActions(onSelectStartupType = { requested = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-scenario").assertIsDisplayed()
            onNodeWithTag("startup-type-WARM").performClick()
            assertEquals(StartupType.WARM, requested)
            onNodeWithTag("startup-start-stop").assertIsNotEnabled()
            onNodeWithTag("startup-date").assertDoesNotExist()
        }

    @Test
    fun `running workspace locks configuration but keeps cancellation available`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            var cancelled = false
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState().copy(isRunning = true, completedRuns = 2, totalRuns = 5),
                        StartupProfilerActions(onStartStop = { cancelled = true }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-type-HOT").assertIsNotEnabled()
            onNodeWithTag("startup-start-stop").assertIsEnabled().performClick()
            assertTrue(cancelled)
            onNodeWithTag("startup-main").performMouseInput { scroll(1200f) }
            waitForIdle()
            onNodeWithTag("startup-progress").assertIsDisplayed()
            onNodeWithText("Measured runs: 2 / 5").assertIsDisplayed()
        }

    @Test
    fun `operation messages and errors appear below the baseline comparison`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState().copy(operationMessage = "Capture finished", errorMessage = "Trace inspection failed"),
                        StartupProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("startup-scenario").assertIsDisplayed()
            onNodeWithTag("startup-metric-TTID").assertIsDisplayed()
            onNodeWithText("Capture finished").assertIsNotDisplayed()
            onNodeWithText("Trace inspection failed").assertIsNotDisplayed()
            onNodeWithText("Trace inspection failed").performScrollTo()
            onNodeWithText("Capture finished").assertIsDisplayed()
            onNodeWithText("Trace inspection failed").assertIsDisplayed()

            val comparison = onNodeWithText("Baseline Comparison").fetchSemanticsNode().boundsInRoot
            val message = onNodeWithText("Capture finished").fetchSemanticsNode().boundsInRoot
            assertTrue(message.top >= comparison.bottom)
        }

    @Test
    fun `stacked layout retains messages errors and running progress after the analysis content`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState().copy(
                            isRunning = true,
                            completedRuns = 2,
                            totalRuns = 5,
                            operationMessage = "Measuring startup",
                            errorMessage = "Optional trace unavailable",
                        ),
                        StartupProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithText("Measured runs: 2 / 5").performScrollTo()
            onNodeWithTag("startup-stacked-workspace").performMouseInput { scroll(40f) }
            waitForIdle()
            onNodeWithText("Measuring startup").assertIsDisplayed()
            onNodeWithText("Optional trace unavailable").assertIsDisplayed()
            onNodeWithText("Measured runs: 2 / 5").assertIsDisplayed()
            onNodeWithTag("startup-progress").assertIsDisplayed()

            val comparison = onNodeWithText("Baseline Comparison").fetchSemanticsNode().boundsInRoot
            val message = onNodeWithText("Measuring startup").fetchSemanticsNode().boundsInRoot
            assertTrue(message.top >= comparison.bottom)
        }

    @Test
    fun `reference layout has four real KPIs multiple run rows and a separate evidence rail`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(startupVisualState(), StartupProfilerActions(), UiLanguage.SIMPLIFIED_CHINESE)
                }
            }
            listOf("TTID", "TTFD", "FIRST_FRAME", "P95").forEach {
                onNodeWithTag("startup-metric-$it").assertIsDisplayed()
            }
            onNodeWithTag("startup-waterfall-run-1").assertIsDisplayed()
            onNodeWithTag("startup-waterfall-run-5").assertIsDisplayed()
            val main = onNodeWithTag("startup-main").fetchSemanticsNode().boundsInRoot
            val rail = onNodeWithTag("startup-evidence-rail").fetchSemanticsNode().boundsInRoot
            assertTrue(main.right <= rail.left)
            assertTrue(rail.width >= 280f)
            onNodeWithTag("startup-date").assertDoesNotExist()
        }

    @Test
    fun `table and waterfall selections and trace failure feedback use real callbacks`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var selected: String? = null
            var trace: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onSelectRun = { selected = it }, onOpenTrace = {
                            trace = it
                            false
                        }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-run-3").performClick()
            assertEquals("run-3", selected)
            onNodeWithTag("startup-main").performScrollToNode(hasTestTag("startup-table-run-4"))
            waitForIdle()
            onNodeWithTag("startup-main").performMouseInput { scroll(500f) }
            waitForIdle()
            onNodeWithTag("startup-table-run-4").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals("run-4", selected)
            onNodeWithTag("startup-evidence-rail").performScrollToNode(hasTestTag("startup-open-trace"))
            onNodeWithTag("startup-open-trace").performClick()
            assertEquals("/synthetic/startup.perfetto-trace", trace)
            onNodeWithText("Trace could not be opened from this computer.").assertIsDisplayed()
        }

    @Test
    fun `advanced controls preserve timeout trace and compilation warmups`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            var tracing: Boolean? = null
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(
                        startupVisualState(),
                        StartupProfilerActions(onSetPerfettoTraceEnabled = { tracing = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-advanced").performClick()
            onNodeWithTag("startup-timeout").assertIsDisplayed()
            onNodeWithTag("startup-perfetto").performClick()
            assertEquals(true, tracing)
        }

    @Test
    fun `narrow layout stacks evidence without removing content`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StartupProfilerScreen(startupVisualState(), StartupProfilerActions(), UiLanguage.ENGLISH)
                }
            }
            onNodeWithTag("startup-stacked-workspace").performScrollToNode(hasTestTag("startup-profile-status"))
            onNodeWithTag("startup-profile-status").assertIsDisplayed()
        }

    @Test
    fun `baseline profile is not inferred from generic speed profile compilation`() {
        val evidence =
            startupVisualState()
                .analysis!!
                .runs
                .first()
                .compilationEvidence!!
        assertTrue(evidence.hasVerifiedBaselineProfile())
        val genericProfile = evidence.copy(profileSource = StartupProfileSource.MACROBENCHMARK)
        assertEquals(false, genericProfile.hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(baselineProfileArtifact = null).hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(verified = false).hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(failureReason = "preparation failed").hasVerifiedBaselineProfile())
        assertEquals(false, evidence.copy(requestedMode = CompilationMode.CURRENT).hasVerifiedBaselineProfile())
    }

    @Test
    fun `cohort metrics keep missing and mixed confidence and selected long-run preview`() {
        val state = startupVisualState()
        val base = state.analysis!!.runs.first()
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    base,
                    base.copy(
                        id = "missing",
                        ttfdEvidence = base.ttfdEvidence.copy(confidence = EvidenceConfidence.UNAVAILABLE),
                        platform = base.platform.copy(fullyDrawnTimeMs = null),
                    ),
                ),
            )
        val caption = metricEvidenceText(analysis, analysis.fullyDrawn, { it.ttfdEvidence }, UiLanguage.ENGLISH)
        assertEquals("Evidence: Exact / Unavailable · n=1 · missing=1", caption)
        val runs = (1..100).map { base.copy(id = "long-$it", iteration = it) }
        val preview = startupWaterfallPreview(runs, runs.last())
        assertEquals(5, preview.size)
        assertEquals("long-100", preview.last().id)
    }

    @Test
    fun `unavailable phases keep real total values and selectable run rows`() =
        runDesktopComposeUiTest(width = 1336, height = 904) {
            val original = startupVisualState()
            val analysis =
                StartupAnalyzer().analyze(
                    original.analysis!!.runs.map { it.copy(milestones = emptyList(), phases = emptyList()) },
                )
            var selected: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    StartupProfilerScreen(
                        original.copy(analysis = analysis),
                        StartupProfilerActions(onSelectRun = { selected = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }
            onNodeWithTag("startup-waterfall-run-5").performClick()
            assertEquals("run-5", selected)
        }

    @Test
    fun `fully missing metrics report zero available samples`() {
        val run = startupVisualState().analysis!!.runs.first()
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    run.copy(
                        platform = run.platform.copy(fullyDrawnTimeMs = null),
                        ttfdEvidence = run.ttfdEvidence.copy(confidence = EvidenceConfidence.UNAVAILABLE),
                    ),
                ),
            )
        assertEquals(
            "Evidence: Unavailable · n=0 · missing=1",
            metricEvidenceText(analysis, analysis.fullyDrawn, {
                it.ttfdEvidence
            }, UiLanguage.ENGLISH),
        )
    }
}
