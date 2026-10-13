package com.androidperformancestudio.benchmark.presentation

import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.benchmark.presentation.generated.resources.Res
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkReportsLocalizationTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkReportsScreen.kt",
            ),
        )

    private val tableSource =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkComparisonTable.kt",
            ),
        )

    @Test
    fun `benchmark reports screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("Res.string.release_gate, language"))
        assertTrue(tableSource.contains("Res.string.comparison_results, language"))
        assertTrue(tableSource.contains("Res.string.no_comparisons, language"))
        assertTrue(source.contains("Res.string.report_summary, language"))
        assertTrue(source.contains("reason.displayName(language)"))
    }

    @Test
    fun `benchmark presentation resources resolve in Chinese`() {
        assertEquals("性能回归与报告", localizedStringResource(Res.string.reports_title, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("发布门禁状态", localizedStringResource(Res.string.release_gate, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("通过", localizedStringResource(Res.string.gate_pass, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("证据不足", localizedStringResource(Res.string.gate_inconclusive, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("高", localizedStringResource(Res.string.severity_high, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("基准测试对比结果", localizedStringResource(Res.string.comparison_results, UiLanguage.SIMPLIFIED_CHINESE))
    }
}
