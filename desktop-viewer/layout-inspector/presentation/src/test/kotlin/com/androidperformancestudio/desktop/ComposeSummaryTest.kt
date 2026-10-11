package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposeValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeSummaryTest {
    @Test
    fun `summarizes the shapes the inspector really returns`() {
        // Captured from a device running Compose 1.10.4: lambda parameters carry their definition
        // site as the value, and the single modifier value comes back empty.
        val summary = detail().toComposeSummary()

        assertEquals(3, summary.parameterCount)
        assertEquals(2, summary.lambdaCount)
        assertEquals(
            listOf("onClick" to "AnrDemoScreen.kt:112", "content" to "DiagnosticsPanel.kt:38"),
            summary.lambdaParameters.map { parameter -> parameter.name to parameter.location },
        )
        assertEquals(1, summary.modifierCount)
        assertEquals(1, summary.mergedSemanticsCount)
        assertEquals(0, summary.unmergedSemanticsCount)
    }

    @Test
    fun `caps the listed lambdas and reports a blank location as unknown`() {
        val detail = ComposableDetail(
            nodeId = 1,
            anchorHash = 0,
            parameters = (1..MAX_SUMMARY_LAMBDAS + 2).map { index ->
                ComposeValue(
                    name = "lambda" + index,
                    type = LAMBDA_PARAMETER_TYPE,
                    value = if (index == 1) "   " else "File.kt:" + index,
                )
            },
        )

        val summary = detail.toComposeSummary()

        assertEquals(MAX_SUMMARY_LAMBDAS + 2, summary.lambdaCount)
        assertEquals(MAX_SUMMARY_LAMBDAS, summary.lambdaParameters.size)
        assertNull(summary.lambdaParameters.first().location)
    }

    @Test
    fun `summarizes a missing detail as empty`() {
        val summary = (null as ComposableDetail?).toComposeSummary()

        assertEquals(0, summary.parameterCount)
        assertEquals(0, summary.lambdaCount)
        assertEquals(0, summary.modifierCount)
    }

    @Test
    fun `the tree badge carries the lambda count and a stability warning`() {
        val withLambdas = detail().toComposeSummary().copy(lambdaCount = 2, lambdaParameters = emptyList())
        val plain = withLambdas.copy(lambdaCount = 0)

        assertEquals("λ2", composeTreeBadge(withLambdas, risky = false))
        assertEquals("λ2 ⚠", composeTreeBadge(withLambdas, risky = true))
        assertEquals("⚠", composeTreeBadge(plain, risky = true))
        assertEquals("", composeTreeBadge(plain, risky = false))
    }

    private fun detail() = ComposableDetail(
        nodeId = -2124627832L,
        anchorHash = 1,
        parameters = listOf(
            ComposeValue("label", "STRING", "ok"),
            ComposeValue("onClick", LAMBDA_PARAMETER_TYPE, "AnrDemoScreen.kt:112"),
            ComposeValue("content", LAMBDA_PARAMETER_TYPE, "DiagnosticsPanel.kt:38"),
        ),
        modifiers = listOf(ComposeValue("modifier", "STRING", "")),
        mergedSemantics = listOf(ComposeValue("text", "STRING", "hi")),
    )
}
