package com.androidperformancestudio.perfetto.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/presentation/TraceAnalyzerWorkspace.kt"),
        )

    @Test
    fun `wide and narrow workspaces each render the same shared heading once`() {
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        listOf("WideTraceWorkspace", "NarrowTraceWorkspace").forEach { name ->
            assertEquals(1, Regex("TraceWorkspaceBanner\\(language\\)").findAll(functionSource(name)).count())
        }
        val heading = functionSource("TraceWorkspaceBanner")
        assertTrue(heading.contains("Res.string.trace_workspace_title, language"))
        assertTrue(heading.contains("Res.string.trace_workspace_subtitle, language"))
        assertTrue(heading.contains("Res.string.trace_workspace_system_visibility, language"))
        assertTrue(heading.contains("testTag(\"trace-analyzer-banner\")"))
        assertTrue(heading.contains("titleModifier = Modifier.testTag(\"trace-analyzer-title\")"))
        assertFalse(heading.contains(".background("))
        assertFalse(heading.contains(".border("))
    }

    @Test
    fun `workspace retains independent sessions viewer capture and evidence layouts`() {
        val wide = functionSource("WideTraceWorkspace")
        assertTrue(wide.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(wide.contains("Modifier.width(232.dp).fillMaxHeight()"))
        assertTrue(wide.contains("Modifier.width(312.dp).fillMaxHeight()"))
        val narrow = functionSource("NarrowTraceWorkspace")
        assertTrue(narrow.contains(".verticalScroll(rememberScrollState())"))
        listOf("280", "360", "420").forEach { height -> assertTrue(narrow.contains(".height($height.dp)")) }
        val panel = functionSource("WorkspaceCard")
        assertTrue(panel.contains("StudioPanelHeader("))
        assertTrue(panel.contains("contentPadding = 0.dp"))
        assertTrue(panel.contains("contentSpacing = 0.dp"))
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `summary cards preserve real unavailable metrics and compact tone styling`() {
        val summary = functionSource("TraceSummaryCard")
        assertTrue(summary.contains("summary.value ?: localizedStringResource(Res.string.trace_workspace_unavailable, language)"))
        assertTrue(summary.contains(".height(72.dp)"))
        assertTrue(summary.contains("accent.copy(alpha = 0.10f)"))
        assertTrue(summary.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(summary.contains("verticalArrangement = Arrangement.SpaceBetween"))
    }

    private fun functionSource(name: String): String = source.substringAfter("fun $name(").substringBefore("\n@Composable")
}
