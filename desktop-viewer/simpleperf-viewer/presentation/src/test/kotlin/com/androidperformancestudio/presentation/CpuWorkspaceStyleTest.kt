package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuWorkspaceStyleTest {
    @Test
    fun `capture and report share one localized workspace heading below the toolbar`() {
        val source = source("DeviceTargetPage.kt")
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        assertTrue(source.contains("SimpleperfViewerRes.sp_cpu_profiler"))
        assertTrue(source.contains(".testTag(\"cpu-workspace-header\")"))
        assertTrue(source.indexOf("HeaderToolbar(") < source.indexOf("StudioWorkspaceHeader("))
        assertTrue(source.indexOf("StudioWorkspaceHeader(") < source.indexOf("ReportWorkspace("))
        assertTrue(source.indexOf("ReportWorkspace(") < source.indexOf("WorkspaceFooter("))
        assertTrue(source.contains("StudioTokens.compactContentPadding"))
    }

    @Test
    fun `report panes and capture settings do not repeat the workspace heading`() {
        listOf("HomeScreen.kt", "ReportPage.kt", "FirefoxReportWorkspace.kt", "CaptureSettingsSection.kt").forEach { file ->
            assertFalse(source(file).contains("StudioWorkspaceHeader("), file)
        }
        val source = source("DeviceTargetPage.kt")
        assertTrue(source.contains("onNavigateHome = onNavigateHome"))
        assertTrue(source.contains("ToolbarCaptureActions(state, captureState, actions, style"))
        assertTrue(source.contains("flameTooltipMode = flameTooltipMode"))
        assertTrue(source.contains("onRunAiAnalysis = onRunAiAnalysis"))
    }

    private fun source(file: String): String = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation", file))
}
