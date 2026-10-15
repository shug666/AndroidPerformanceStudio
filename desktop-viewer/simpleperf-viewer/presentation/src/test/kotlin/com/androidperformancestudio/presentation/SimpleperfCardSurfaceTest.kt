package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfCardSurfaceTest {
    @Test
    fun `diagnostic cards share their surface while retaining severity and navigation`() {
        val card =
            source("ReportPage.kt")
                .substringAfter("private fun DiagnosticCard(")
                .substringBefore("private fun DiagnosticFinding.navigation(")

        assertTrue(card.contains("StudioCard("))
        assertTrue(card.contains("containerColor = style.panel"))
        assertTrue(card.contains("borderColor = accent"))
        assertTrue(card.contains("DiagnosticSeverity.WARNING -> style.warning"))
        assertTrue(card.contains("DiagnosticSeverity.CRITICAL -> style.error"))
        assertTrue(card.contains("actions.onSelectOverviewFinding(finding.ruleId)"))
        assertTrue(card.contains("navigation?.invoke()"))
        assertTrue(card.contains("finding.evidence.forEach"))
        assertTrue(card.contains("finding.recommendations.forEach"))
        assertFalse(card.contains(".background("))
        assertFalse(card.contains(".border("))
        assertFalse(card.contains(".padding("))
    }

    @Test
    fun `report metrics retain the shared metric component and tables remain tables`() {
        val report = source("ReportPage.kt")
        val metric = report.substringAfter("private fun MetricCard(").substringBefore("internal fun TopFunctionsReport(")
        val row = report.substringAfter("private fun TopFunctionRow(").substringBefore("internal fun CallTreeReport(")

        assertTrue(metric.contains("StudioMetricCard("))
        assertTrue(metric.contains("value = value"))
        val overview = report.substringAfter("internal fun OverviewReport(").substringBefore("internal fun overviewMetricColumns(")
        assertTrue(overview.contains("overviewMetricColumns(maxWidth.value.toInt())"))
        assertTrue(overview.contains("Column(verticalArrangement = Arrangement.spacedBy(12.dp))"))
        assertTrue(overview.contains("horizontalArrangement = Arrangement.spacedBy(12.dp)"))
        assertTrue(overview.contains("MetricCard(title, value, Modifier.weight(1f))"))
        assertTrue(report.contains("StudioDataTable("))
        assertTrue(row.contains("Row("))
        assertTrue(row.contains(".clickable(onClick = onSelect)"))
        assertFalse(row.contains("StudioCard("))
    }
}

private fun source(fileName: String): String =
    Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/$fileName"))
