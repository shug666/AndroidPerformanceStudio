package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportTab
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FirefoxExistingPanelsTest {
    @Test
    fun `diagnostic card keeps finding selection and function navigation`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = sampleReportState()
            val report =
                requireNotNull(base.lastReadyReport).copy(
                    diagnostics =
                        listOf(
                            DiagnosticFinding(
                                ruleId = "hot-function",
                                title = "Inspect the render hotspot",
                                severity = DiagnosticSeverity.CRITICAL,
                                conclusion = "The selected function dominates the samples.",
                                evidence = emptyList(),
                                recommendations = emptyList(),
                                target = DiagnosticTarget.Function("renderFrame", "/system/lib64/libui.so"),
                            ),
                        ),
                )
            val events = mutableListOf<String>()
            val state = base.copy(loadState = ReportLoadState.Ready(report), lastReadyReport = report)
            val actions =
                goldenActions().copy(
                    onSelectOverviewFinding = { events += "finding:$it" },
                    onFocusFunction = { events += "function:$it" },
                )

            setContent { ReportPage(state, actions) }

            onNodeWithTag("overview-report-list").performScrollToNode(hasText("Inspect the render hotspot"))
            onNodeWithText("Inspect the render hotspot").performClick()

            assertEquals(listOf("finding:hot-function", "function:renderFrame"), events)
        }

    @Test
    fun `diagnostic card without navigation still selects its finding`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = sampleReportState()
            val report =
                requireNotNull(base.lastReadyReport).copy(
                    diagnostics =
                        listOf(
                            DiagnosticFinding(
                                ruleId = "quality-info",
                                title = "Capture quality information",
                                severity = DiagnosticSeverity.INFO,
                                conclusion = "The evidence has no navigation target.",
                                evidence = emptyList(),
                                recommendations = emptyList(),
                            ),
                        ),
                )
            var selected: String? = null
            val state = base.copy(loadState = ReportLoadState.Ready(report), lastReadyReport = report)

            setContent {
                ReportPage(state, goldenActions().copy(onSelectOverviewFinding = { selected = it }))
            }

            onNodeWithTag("overview-report-list").performScrollToNode(hasText("Capture quality information"))
            onNodeWithText("Capture quality information").performClick()

            assertEquals("quality-info", selected)
        }

    @Test
    fun `overview contains diagnostics without a diagnostics tab`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = sampleReportState()
            val report =
                requireNotNull(base.lastReadyReport).copy(
                    diagnostics =
                        listOf(
                            DiagnosticFinding(
                                ruleId = "data-quality",
                                title = "Unwind quality needs attention",
                                severity = DiagnosticSeverity.WARNING,
                                conclusion = "Some samples could not be unwound.",
                                evidence = emptyList(),
                                recommendations = listOf("Collect symbols before the next capture."),
                            ),
                        ),
                )
            val state = base.copy(loadState = ReportLoadState.Ready(report), lastReadyReport = report)

            setContent { ReportPage(state, goldenActions()) }

            onNodeWithText("Data quality").assertExists()
            onNodeWithTag("overview-report-list").performScrollToNode(hasText("Recommendations"))
            onNodeWithText("Recommendations").assertExists()
            onNodeWithTag("report-tab-DIAGNOSTICS").assertDoesNotExist()
        }

    @Test
    fun `flame graph has one shared filter toolbar`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent { ReportPage(sampleReportState(ReportTab.FLAME_GRAPH), goldenActions()) }

            assertEquals(1, onAllNodesWithTag("stack-toolbar").fetchSemanticsNodes().size)
        }
}
