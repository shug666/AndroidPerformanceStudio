package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.cold
import com.androidperformancestudio.startup.presentation.generated.resources.exact
import com.androidperformancestudio.startup.presentation.generated.resources.phase_first_frame_to_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.phase_range
import com.androidperformancestudio.startup.presentation.generated.resources.requested
import com.androidperformancestudio.startup.presentation.generated.resources.run_detail
import com.androidperformancestudio.startup.presentation.generated.resources.startup_profiler
import com.androidperformancestudio.startup.presentation.generated.resources.total
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt"),
        )

    @Test
    fun `startup profiler screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("run.observedType.localizedLabel(language)"))
        assertTrue(source.contains("milestone.kind.localizedLabel(language)"))
        assertTrue(source.contains("milestone.confidence.localizedLabel(language)"))
        assertTrue(source.contains("phase.localizedName(language)"))
        assertFalse(source.contains("else -> return name"))
    }

    @Test
    fun `startup profiler presentation labels are translated to Chinese`() {
        assertEquals(
            "启动性能分析器",
            localizedStringResource(Res.string.startup_profiler, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "总耗时",
            localizedStringResource(Res.string.total, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "冷启动",
            localizedStringResource(Res.string.cold, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "精确",
            localizedStringResource(Res.string.exact, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "首帧到完全绘制",
            localizedStringResource(Res.string.phase_first_frame_to_fully_drawn, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "进程启动到首帧",
            localizedStringResource(
                Res.string.phase_range,
                UiLanguage.SIMPLIFIED_CHINESE,
                "进程启动",
                "首帧",
            ),
        )
    }

    @Test
    fun `run presentation keeps requested and observed startup types distinct`() {
        assertTrue(source.contains("StudioTableColumn(localizedStringResource(Res.string.requested, language)"))
        assertTrue(source.contains("run.requestedType.localizedLabel(language)"))
        assertTrue(source.contains("run.observedType.localizedLabel(language)"))
        assertEquals("请求类型", localizedStringResource(Res.string.requested, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals(
            "第 2 轮详情 · 请求 冷启动 · 实际 热启动",
            localizedStringResource(Res.string.run_detail, UiLanguage.SIMPLIFIED_CHINESE, 2, "冷启动", "热启动"),
        )
    }

    @Test
    fun `measured runs table keeps selection and horizontal access to real run values`() {
        assertTrue(source.contains("StudioDataTable("))
        assertTrue(source.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(source.contains(".selectable(selected = selected, onClick = { onSelect(run.id) }"))
        assertTrue(source.contains("TableCell(run.iteration.toString(), RUN_COLUMN_WEIGHT, emphasized = selected)"))
        assertTrue(source.contains("run.platform.totalTimeMs.formatMs()"))
        assertTrue(source.contains("run.platform.displayedTimeMs.formatMs()"))
        assertTrue(source.contains("run.platform.fullyDrawnTimeMs.formatMs()"))
    }

    @Test
    fun `Startup consumes shared workspace styling without applying a second local palette`() {
        val controls =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceControls.kt"),
            )
        val metrics =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceMetrics.kt"),
            )

        assertTrue(controls.contains("StudioWorkspaceHeader("))
        assertTrue(metrics.contains("StudioMetricCard("))
        assertFalse(source.contains("startupWorkspaceColors"))
        assertFalse(controls.contains("startupWorkspaceColors"))
    }

    @Test
    fun `operation messages errors and progress follow the analysis cards and baseline comparison`() {
        val workspace =
            source
                .substringAfter("private fun StartupMainWorkspace(")
                .substringBefore("private fun StartupEvidenceRail(")
        val scenario = workspace.indexOf("StartupScenarioControls(state, actions, language)")
        val metrics = workspace.indexOf("StartupMetricOverview(state.analysis, language)")
        val comparison = workspace.indexOf("Res.string.baseline_comparison_empty")

        assertTrue(scenario >= 0 && metrics > scenario)
        assertTrue(comparison > metrics)
        listOf("state.operationMessage", "state.errorMessage", "startup-progress").forEach { marker ->
            assertTrue(workspace.indexOf(marker) > comparison, "$marker must appear below the analysis content")
        }
    }

    @Test
    fun `multi-run waterfall appears before the selectable table and comparison`() {
        val results =
            source
                .substringAfter("private fun StartupMainWorkspace(")
                .substringBefore("private fun StartupEvidenceRail(")
        val waterfallIndex = results.indexOf("StartupRunsWaterfall(analysis.runs, selected, actions, language)")
        assertTrue(waterfallIndex < results.indexOf("StudioDataTable("))
        val comparisonIndex = results.indexOf("BaselineComparison(comparison, language)")
        assertTrue(results.indexOf("StudioDataTable(") < comparisonIndex)
        assertTrue(source.contains("RunDetail(run, actions, language)"))
    }

    @Test
    fun `commercial metrics retain P90 sample confidence and tail warning`() {
        val metrics =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupWorkspaceMetrics.kt"),
            )
        assertTrue(metrics.contains("metricEvidenceText("))
        assertTrue(metrics.contains("it.firstFrame.p90Ms.formatMs()"))
        assertTrue(metrics.contains("Res.string.low_tail_resolution"))
    }
}
