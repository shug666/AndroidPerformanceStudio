package com.androidperformancestudio.battery.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryProfilerWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt"),
        )

    @Test
    fun `battery child uses compact shared surfaces without a duplicate page heading or theme`() {
        assertTrue(source.contains(".background(LocalViewerColors.current.workspace)"))
        assertTrue(source.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(source.contains("StudioPanel("))
        assertTrue(source.contains("StudioMetricCard("))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertFalse(source.contains("Res.string.battery_energy_profiler"))
        assertFalse(source.contains("headlineMedium"))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `metric rows adapt to available width without dropping statistics`() {
        assertTrue(source.contains("BoxWithConstraints("))
        assertTrue(source.contains("metrics.chunked(columnCount)"))
        assertTrue(source.contains("statistics.median.format(unit)"))
        assertTrue(source.contains("statistics.p90.format(unit), statistics.count"))
        assertTrue(source.contains("if (analysis.energyMah.count > 0)"))
        assertTrue(source.contains("Res.string.thermal_status"))
        assertTrue(source.contains("tone = tone"))
        assertTrue(Regex("analysis\\.networkBytes,\\s*\"B\",\\s*colors\\.info").containsMatchIn(source))
        val thermalMetric = Regex("analysis\\.peakTemperatureCelsius,\\s*\"\u00B0C\",\\s*colors\\.warning")
        assertTrue(thermalMetric.containsMatchIn(source))
    }

    @Test
    fun `shared run table retains selection evidence warnings and error states`() {
        assertTrue(source.contains("StudioDataTable("))
        assertTrue(source.contains("RunRow(run, run.runId == selected.runId, actions.onSelectRun, language)"))
        assertTrue(source.contains("BatteryRunWorkspace("))
        assertTrue(source.contains("state.experiment?.runs ?: state.storedRuns"))
        assertTrue(source.contains("state.baseline?.let"))
        assertTrue(source.contains("state.errorMessage != null && analysis == null"))
        assertTrue(source.contains("state.operationMessage?.let"))
        assertTrue(source.contains("state.artifact?.let"))
        assertTrue(source.contains("state.warnings.forEach"))
        val workspace =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryRunWorkspace.kt"),
            )
        assertTrue(workspace.contains("BatterySnapshotChart("))
        assertTrue(workspace.contains("BatteryHistoryChart("))
        assertTrue(workspace.contains("BatteryEvidenceInspector("))
        assertFalse(workspace.contains(".take(100)"))
    }
}
