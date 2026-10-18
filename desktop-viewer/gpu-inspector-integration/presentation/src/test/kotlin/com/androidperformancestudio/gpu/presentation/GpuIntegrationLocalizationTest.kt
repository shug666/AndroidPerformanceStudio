package com.androidperformancestudio.gpu.presentation

import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.gpu.presentation.generated.resources.analysis_tools
import com.androidperformancestudio.gpu.presentation.generated.resources.artifact_kind_perfetto_trace
import com.androidperformancestudio.gpu.presentation.generated.resources.artifact_search_placeholder
import com.androidperformancestudio.gpu.presentation.generated.resources.closed_panel_shader_hotspots
import com.androidperformancestudio.gpu.presentation.generated.resources.closed_panels_title
import com.androidperformancestudio.gpu.presentation.generated.resources.import_gpu_trace
import com.androidperformancestudio.gpu.presentation.generated.resources.launch_mode_gui_only
import com.androidperformancestudio.gpu.presentation.generated.resources.no
import com.androidperformancestudio.gpu.presentation.generated.resources.open_with
import com.androidperformancestudio.gpu.presentation.generated.resources.select_analysis_artifact
import com.androidperformancestudio.gpu.presentation.generated.resources.summary_available_support
import com.androidperformancestudio.gpu.presentation.generated.resources.tab_artifacts
import com.androidperformancestudio.gpu.presentation.generated.resources.tab_overview
import com.androidperformancestudio.gpu.presentation.generated.resources.tab_related_evidence
import com.androidperformancestudio.gpu.presentation.generated.resources.tab_tool_capability
import com.androidperformancestudio.gpu.presentation.generated.resources.unavailable
import com.androidperformancestudio.gpu.presentation.generated.resources.unknown_version
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/presentation/GpuIntegrationScreen.kt"),
        )

    @Test
    fun `gpu inspector screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("Res.string.android_gpu_inspector, language"))
        assertTrue(source.contains("Res.string.recent_gpu_artifacts, language"))
        assertTrue(source.contains("artifact.kind.displayName(language)"))
        assertTrue(source.contains("state.capability?.launchMode?.displayName(language)"))
        assertTrue(source.contains("localizedWarning(warning, language)"))
    }

    @Test
    fun `gpu inspector presentation resources are translated to Chinese`() {
        assertEquals(
            "版本未知",
            localizedStringResource(Res.string.unknown_version, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "不可用",
            localizedStringResource(Res.string.unavailable, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "Perfetto 轨迹",
            localizedStringResource(Res.string.artifact_kind_perfetto_trace, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "仅 GUI",
            localizedStringResource(Res.string.launch_mode_gui_only, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `hub workspace labels are translated to Chinese`() {
        assertEquals("概览", localizedStringResource(Res.string.tab_overview, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("产物", localizedStringResource(Res.string.tab_artifacts, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("关联分析", localizedStringResource(Res.string.tab_related_evidence, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("工具能力", localizedStringResource(Res.string.tab_tool_capability, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("分析工具", localizedStringResource(Res.string.analysis_tools, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("导入 GPU Trace", localizedStringResource(Res.string.import_gpu_trace, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("选择分析产物", localizedStringResource(Res.string.select_analysis_artifact, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("搜索产物…", localizedStringResource(Res.string.artifact_search_placeholder, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("用 AGI 打开", localizedStringResource(Res.string.open_with, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("着色器热点", localizedStringResource(Res.string.closed_panel_shader_hotspots, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("否", localizedStringResource(Res.string.no, UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `hub workspace labels keep their placeholder order in both languages`() {
        assertEquals(
            "2 of 3 verified by size and SHA-256",
            localizedStringResource(Res.string.summary_available_support, UiLanguage.ENGLISH, 2, 3),
        )
        assertEquals(
            "3 个中有 2 个已按大小与 SHA-256 校验",
            localizedStringResource(Res.string.summary_available_support, UiLanguage.SIMPLIFIED_CHINESE, 2, 3),
        )
        assertEquals(
            "Related analysis",
            localizedStringResource(Res.string.tab_related_evidence, UiLanguage.ENGLISH),
        )
        assertEquals(
            "Closed until a stable parser exists",
            localizedStringResource(Res.string.closed_panels_title, UiLanguage.ENGLISH),
        )
    }

    @Test
    fun `english and chinese resource bundles declare the same keys`() {
        val english = resourceKeys("values/strings.xml")
        val chinese = resourceKeys("values-zh/strings.xml")

        assertEquals(english, chinese)
        assertTrue(english.contains("tab_tool_capability"))
        assertTrue(english.contains("closed_panel_overdraw"))
    }

    private fun resourceKeys(path: String): Set<String> {
        val xml = Files.readString(Path.of("src/main/composeResources", path))
        return Regex("name=\"([a-z0-9_]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
    }
}
