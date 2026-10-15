package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopStartupWorkspaceStyleTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")

    @Test
    fun `desktop entry pages each use exactly one common workspace header`() {
        listOf(
            "AppHomePage.kt",
            "DesktopAppSettingsPage.kt",
            "SourceWorkspacesPage.kt",
            "BatteryNetworkMainPage.kt",
        ).forEach { file ->
            val source = Files.readString(sourceRoot.resolve(file))
            assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count(), file)
            assertFalse(source.contains("Brush.horizontalGradient("), file)
            assertFalse(source.contains("ViewerTheme("), file)
        }
    }

    @Test
    fun `home heading precedes both responsive dashboard branches without a promotional banner`() {
        val source = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        val header = source.indexOf("StudioWorkspaceHeader(")
        assertTrue(header in 0 until source.indexOf("if (maxWidth.value >= HOME_TWO_COLUMN_MIN_WIDTH_DP)"))
        assertTrue(source.contains("Res.string.android_performance_studio, language"))
        assertTrue(source.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(source.contains("color = colors.workspace"))
        assertFalse(source.contains("HomeHero("))
        assertFalse(source.contains("Res.string.home_hero_subtitle"))
        assertTrue(source.contains("tone = colors.info"))
        assertTrue(source.contains("tone = colors.accent"))
    }

    @Test
    fun `settings retains sidebar theme context and persistence feedback under the common header`() {
        val source = Files.readString(sourceRoot.resolve("DesktopAppSettingsPage.kt"))
        assertTrue(source.contains("title = localizedStringResource(Res.string.settings, language)"))
        assertTrue(source.contains("ProvideViewerThemeContext(viewerThemeContext)"))
        assertTrue(source.contains("SettingsSidebar("))
        assertTrue(source.contains("onPageSelected = onPageSelected"))
        assertTrue(source.contains("if (persistenceErrorPage != null)"))
        assertTrue(source.contains("Modifier.weight(1f).padding(StudioTokens.compactContentPadding)"))
    }

    @Test
    fun `source workspace heading leaves the native toolbar and resizable source workflow intact`() {
        val source = Files.readString(sourceRoot.resolve("SourceWorkspacesPage.kt"))
        val toolbar = source.substringAfter("Surface(").substringBefore("StudioWorkspaceHeader(")
        assertTrue(toolbar.contains("HomeButton("))
        assertTrue(toolbar.contains(".height(HEADER_TOOL_BAR_HEIGHT)"))
        assertTrue(toolbar.contains("onClick = onOpenAiSettings"))
        assertTrue(toolbar.contains("Res.string.source_add_local"))
        assertEquals(1, Regex("Res\\.string\\.source_workspaces, language").findAll(source).count())
        assertTrue(source.contains("SourceWorkspacePaneLayout.dragWorkspaces("))
        assertTrue(source.contains("SourceWorkspacePaneLayout.dragFiles("))
        assertTrue(source.contains("runtime.service.read(location)"))
        assertTrue(source.contains("pageError?.let"))
    }

    @Test
    fun `battery network uses a single parent heading and keeps responsive tabs and retained layers`() {
        val source = Files.readString(sourceRoot.resolve("BatteryNetworkMainPage.kt"))
        assertTrue(source.contains("title = localizedStringResource(Res.string.battery_network, language)"))
        assertTrue(source.contains("supportingText = localizedStringResource(Res.string.independent_analyzers, language)"))
        assertTrue(source.contains(".widthIn(max = 360.dp).fillMaxWidth()"))
        assertTrue(source.contains("onItemSelected = onSelectedTabChanged"))
        assertTrue(source.contains("blockBatteryNetworkPointerInputWhenInactive(active)"))
        assertTrue(source.contains("blockBatteryNetworkKeyboardInputWhenInactive(active)"))
        assertTrue(source.contains("LocalWindowMenuBarActive provides menuBarActive"))
        assertTrue(source.contains("Res.string.correlation_unavailable_message, language"))
        assertFalse(source.contains("Res.string.battery_network_subtitle"))
    }
}
