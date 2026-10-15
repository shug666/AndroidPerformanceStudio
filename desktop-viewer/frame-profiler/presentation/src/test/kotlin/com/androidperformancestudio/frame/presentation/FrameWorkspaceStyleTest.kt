package com.androidperformancestudio.frame.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"),
        )

    @Test
    fun `empty and analyzed workspaces share exactly one heading above their content`() {
        val workspace = source.substringAfter("public fun FrameProfilerScreen(").substringBefore("private fun EmptyState(")
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        assertEquals(1, Regex("FrameWorkspaceHeading\\(state, language\\)").findAll(workspace).count())
        assertTrue(workspace.indexOf("FrameWorkspaceHeading(state, language)") < workspace.indexOf("if (analysis == null)"))
        val heading = source.substringAfter("private fun FrameWorkspaceHeading(").substringBefore("private fun FrameFilterControls(")
        assertTrue(heading.contains("Res.string.frame_profiler_title, language"))
        assertTrue(heading.contains("Res.string.frame_profiler_subtitle, language"))
        assertTrue(heading.contains("supportingText = captureLabel"))
        assertTrue(heading.contains("testTag(\"frame-workspace-heading\")"))
    }

    @Test
    fun `compact cards retain the three analysis panes and frame controls`() {
        assertTrue(source.contains(".padding(StudioTokens.compactContentPadding)"))
        listOf("frame-clusters-panel", "frame-problems-panel", "frame-detail-panel").forEach { tag ->
            assertTrue(source.contains("testTag(\"$tag\")"))
        }
        assertTrue(source.contains("modifier = Modifier.weight(0.85f)"))
        assertTrue(source.contains("modifier = Modifier.weight(1.45f)"))
        val detail = source.substringAfter("private fun FrameDetail(").substringBefore("private fun FrameLayoutPreview(")
        assertFalse(detail.contains("Modifier.fillMaxSize().padding(8.dp)"), "Panel padding must not be applied twice")
        listOf("onInspectLayout", "onOpenTrace", "onCopyEvidence", "previousFrameId", "nextFrameId").forEach { action ->
            assertTrue(detail.contains(action))
        }
        assertTrue(source.contains(".testTag(\"frame-source-filter\")"))
        assertTrue(source.contains(".testTag(\"frame-result-filter\")"))
    }

    @Test
    fun `metric tones use the shared card without introducing a page theme`() {
        val metric = source.substringAfter("private fun FrameMetricCard(").substringBefore("private fun MetricSparkline(")
        assertTrue(metric.contains("StudioCard("))
        assertTrue(metric.contains("containerColor = color.copy(alpha = 0.10f)"))
        assertTrue(metric.contains("borderColor = color.copy(alpha = 0.5f)"))
        assertTrue(metric.contains("MetricSparkline(samples, color)"))
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }
}
