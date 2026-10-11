package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeSourceLocation
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The overview tab has to carry the Compose facts that decide whether a node is worth opening, since
 * the complete lists live on the Compose tab.
 */
class NodeDetailsPresenterComposeSummaryTest {
    @Test
    fun `the overview summarises source lambdas modifiers and semantics`() {
        val summary = summarySection(composeNode())

        assertEquals("AnrDemoScreen.kt:112", summary.rows.single { it.label == "Source" }.value)
        assertEquals("1", summary.rows.single { it.label == "Lambda parameters" }.value)
        assertEquals("AnrDemoScreen.kt:112", summary.rows.single { it.label == "onClick" }.value)
        assertEquals("1", summary.rows.single { it.label == "Modifiers" }.value)
        assertEquals("1 / 0", summary.rows.single { it.label == "Semantics (merged / unmerged)" }.value)
    }

    @Test
    fun `a view node gets no Compose summary`() {
        val sections = NodeDetailsPresenter.present(
            node = ViewNode(id = "view:1", className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 10, 10)),
            treeDepth = 1,
            language = UiLanguage.ENGLISH,
            composeInspection = document(),
        )

        assertTrue(sections.none { it.title == "COMPOSE SUMMARY" })
    }

    @Test
    fun `a composable without a collected detail gets no Compose summary`() {
        val sections = NodeDetailsPresenter.present(
            node = composeNode(),
            treeDepth = 1,
            language = UiLanguage.ENGLISH,
            composeInspection = document(details = emptyMap()),
        )

        assertTrue(sections.none { it.title == "COMPOSE SUMMARY" })
    }

    private fun summarySection(node: ComposeNode): DetailSectionModel =
        NodeDetailsPresenter.present(
            node = node,
            treeDepth = 1,
            language = UiLanguage.ENGLISH,
            composeInspection = document(),
        ).single { section -> section.title == "COMPOSE SUMMARY" }

    private fun composeNode() = ComposeNode(
        id = "compose-inspection:" + NODE_ID,
        className = "DiagnosticsPanel",
        bounds = Bounds(0, 0, 100, 200),
    )

    private fun document(
        details: Map<Long, ComposableDetail> = mapOf(NODE_ID to detail()),
    ) = ComposeInspectionDocument(
        packageName = "example.app",
        capturedAtEpochMillis = 1,
        frame = ComposeInspectionFrame(
            frameId = "frame",
            generation = 1,
            mode = ComposeInspectionMode.FULL,
            capabilities = emptyList(),
            roots = listOf(
                ComposableRoot(
                    viewId = 7,
                    nodes = listOf(
                        ComposableNode(
                            id = NODE_ID,
                            anchorHash = 7,
                            name = "DiagnosticsPanel",
                            bounds = Bounds(0, 0, 100, 200),
                            source = ComposeSourceLocation(
                                packageHash = 0,
                                fileName = "AnrDemoScreen.kt",
                                lineNumber = 112,
                                offset = 0,
                            ),
                        ),
                    ),
                ),
            ),
            details = details,
        ),
    )

    private fun detail() = ComposableDetail(
        nodeId = NODE_ID,
        anchorHash = 7,
        parameters = listOf(ComposeValue("onClick", LAMBDA_PARAMETER_TYPE, "AnrDemoScreen.kt:112")),
        modifiers = listOf(ComposeValue("modifier", "STRING", "")),
        mergedSemantics = listOf(ComposeValue("text", "STRING", "hi")),
    )

    private companion object {
        const val NODE_ID = 42L
    }
}
