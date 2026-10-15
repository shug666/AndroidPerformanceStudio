package com.androidperformancestudio.benchmark.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkReportsScreen.kt"),
        )

    @Test
    fun `benchmark has one localized shared heading above its own toolbar and metrics`() {
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        val heading = source.substringAfter("private fun TitleBlock(").substringBefore("private fun BenchmarkToolbar(")
        assertTrue(heading.contains("Res.string.reports_title, language"))
        assertTrue(heading.contains("Res.string.reports_subtitle, language"))
        val workspace = source.substringAfter("public fun BenchmarkReportsScreen(").substringBefore("private fun TitleBlock(")
        assertEquals(1, Regex("TitleBlock\\(language\\)").findAll(workspace).count())
        assertTrue(workspace.indexOf("TitleBlock(language)") < workspace.indexOf("BenchmarkToolbar(state, language, actions)"))
    }

    @Test
    fun `benchmark uses compact shared spacing without replacing comparison trend or report layouts`() {
        val workspace = source.substringAfter("public fun BenchmarkReportsScreen(").substringBefore("private fun TitleBlock(")
        assertTrue(workspace.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(workspace.contains(".background(colors.workspace)"))
        assertFalse(workspace.contains("StudioTokens.sectionGap"))
        assertTrue(workspace.contains("BenchmarkComparisonTable("))
        assertTrue(workspace.contains("BenchmarkTrendPanel("))
        assertTrue(workspace.contains("modifier = Modifier.height(210.dp)"))
        assertTrue(workspace.contains("modifier = Modifier.width(320.dp)"))
        assertEquals(4, Regex("StudioMetricCard\\(").findAll(source).count())
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `benchmark preserves filters evidence actions and unavailable gate state`() {
        listOf(
            "onSelectBaseline", "onSelectCurrent", "onCompare", "onImportRun", "onOrchestrateRun", "onExportPdf",
            "onExportCsv", "onShareReport", "onCompareSettings", "onSelectMetric", "onQueryChange",
            "onSeverityFilterChange", "onClassificationFilterChange", "onSelectComparison", "onOpenReport", "onGenerateReport",
        ).forEach { action -> assertTrue(source.contains("actions.$action")) }
        assertTrue(source.contains("gate?.decision?.displayName(language) ?:"))
        assertTrue(source.contains("enabled = state.baselineId != null && state.currentId != null && !state.busy"))
        assertTrue(source.contains("enabled = state.report != null"))
    }

    @Test
    fun `shared metric tones follow actual counts and gate decisions`() {
        val summary = source.substringAfter("private fun BenchmarkSummaryRow(").substringBefore("private fun BenchmarkReportColumn(")
        assertTrue(summary.contains("tone = if (state.regressionCount > 0)"))
        assertTrue(summary.contains("tone = if (state.improvementCount > 0)"))
        assertTrue(summary.contains("when (gate?.decision)"))
        assertTrue(summary.contains("GateDecision.PASS -> colors.success"))
        assertTrue(summary.contains("GateDecision.FAIL -> MaterialTheme.colorScheme.error"))
        assertTrue(summary.contains("GateDecision.INCONCLUSIVE -> colors.warning"))
        assertTrue(summary.contains("null -> colors.secondaryText"))
    }
}
