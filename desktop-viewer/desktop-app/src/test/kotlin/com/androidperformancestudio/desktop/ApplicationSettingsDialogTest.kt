package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationSettingsDialogTest {
    private val unifiedDialog =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppSettingsPage.kt"),
        )

    @Test
    fun `unified settings shows an About page with the runtime version`() {
        assertTrue(unifiedDialog.contains("SettingsPage.ABOUT"))
        assertTrue(unifiedDialog.contains("AboutSettingsContent("))
        assertTrue(unifiedDialog.contains("ApplicationVersion.current()"))
    }

    @Test
    fun `unified settings owns AI configuration immediately above About`() {
        assertTrue(SettingsPage.entries.indexOf(SettingsPage.AI) < SettingsPage.entries.indexOf(SettingsPage.ABOUT))
        assertTrue(unifiedDialog.contains("SettingsPage.AI ->"))
        assertTrue(unifiedDialog.contains("AiSettingsContent("))
        assertTrue(unifiedDialog.contains("!runtime.credential(OPENAI_API_KEY).isNullOrBlank()"))
        assertTrue(unifiedDialog.contains("Res.string.source_ai_key_required"))
        assertTrue(
            unifiedDialog.indexOf("label = SettingsPage.AI.label(language)") <
                unifiedDialog.indexOf("label = SettingsPage.ABOUT.label(language)"),
        )

        val sourceWorkspaces =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"),
            )
        assertTrue(sourceWorkspaces.contains("onClick = onOpenAiSettings"))
        assertFalse(sourceWorkspaces.contains("AiCredentialDialog"))

        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("onOpenAiSettings = { openSettings(SettingsPage.AI) }"))
    }
    @Test
    fun `general settings applies the selected display percentage through application settings`() {
        assertTrue(unifiedDialog.contains("Res.string.display_size"))
        assertTrue(unifiedDialog.contains("ApplicationDisplayScale.entries"))
        assertTrue(unifiedDialog.contains("onSettingsChanged(settings.copy(displayScale = it))"))
    }

    @Test
    fun `general settings keeps theme color directly below theme selection`() {
        assertFalse(unifiedDialog.contains("THEME_COLOR"))
        assertTrue(unifiedDialog.contains("ThemeColorSettingsContent("))
        assertTrue(unifiedDialog.contains("ApplicationThemeColor.entries"))
        assertTrue(unifiedDialog.contains("onSettingsChanged(settings.copy(themeColor = color))"))
        assertTrue(
            unifiedDialog.indexOf("label = localizedStringResource(Res.string.theme, language)") <
                unifiedDialog.indexOf("ThemeColorSettingsContent("),
        )
    }

    @Test
    fun `theme colour swatches stay compact`() {
        assertTrue(unifiedDialog.contains("THEME_SWATCH_COLUMNS = 4"))
        assertTrue(unifiedDialog.contains("height(38.dp)"))
        assertTrue(unifiedDialog.contains("size(18.dp)"))
        assertTrue(unifiedDialog.contains("themeColorPreferenceLabel(color, language)"))
    }

    @Test
    fun `settings page headings use the active viewer accent`() {
        assertTrue(unifiedDialog.contains("private fun SettingsPageTitle("))
        assertTrue(unifiedDialog.contains("color = LocalViewerColors.current.accent"))
        assertTrue(Regex("SettingsPageTitle\\(").findAll(unifiedDialog).count() >= 7)
    }

    @Test
    fun `settings sub-navigation rows stay compact`() {
        // Rows are not painted across the whole sidebar while unselected, and the label is not
        // indented by an icon slot that is never filled.
        assertTrue(unifiedDialog.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176"))
        assertTrue(unifiedDialog.contains("else Color.Transparent"))
        assertFalse(unifiedDialog.contains("Spacer(Modifier.width(16.dp))"))
        assertFalse(unifiedDialog.contains("settingsLabel"))
    }

    @Test
    fun `general settings can show or hide the sidebar titles`() {
        // Checked expands the rail (labels visible); unchecked is the collapsed icon-only rail.
        assertTrue(unifiedDialog.contains("Res.string.show_sidebar_titles"))
        assertTrue(unifiedDialog.contains("checked = !settings.navigationRailCollapsed"))
        assertTrue(unifiedDialog.contains("navigationRailCollapsed = !showSidebarTitles"))
        assertTrue(unifiedDialog.contains("MacOSSwitch(checked = checked, onCheckedChange = onCheckedChange)"))

        // The label is styled as a section label: accent colour, flush with the "Theme colour"
        // heading, while the switch keeps the row's horizontal inset.
        val toggleRow =
            unifiedDialog
                .substringAfter("private fun SettingsToggleRow(")
                .substringBefore("private fun GeneralSettingsContent(")
        assertTrue(toggleRow.contains("color = colors.accent"))
        assertTrue(toggleRow.contains("padding(end = 8.dp)"))
        assertFalse(toggleRow.contains("padding(horizontal = 8.dp)"))
    }

    @Test
    fun `configuration page owns the Android SDK path setting`() {
        assertTrue(SettingsPage.entries.indexOf(SettingsPage.CONFIGURATION) > SettingsPage.entries.indexOf(SettingsPage.GENERAL))
        assertTrue(unifiedDialog.contains("SettingsPage.CONFIGURATION ->"))
        assertTrue(unifiedDialog.contains("ConfigurationSettingsContent("))
        assertTrue(unifiedDialog.contains("AndroidSdkPathSetting("))
        assertTrue(
            unifiedDialog.indexOf("SettingsPage.CONFIGURATION ->") <
                unifiedDialog.indexOf("AndroidSdkPathSetting("),
        )
    }

    @Test
    fun `trace analyzer receives the configured Android SDK path`() {
        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("androidSdkPath = applicationSettings.androidSdkPath?.let"))
        assertTrue(mainPage.contains("PerfettoMainPage("))
    }

    @Test
    fun `settings window applies the selected display scale in its own composition`() {
        assertTrue(unifiedDialog.contains("viewerThemeContext: ViewerThemeContext"))
        assertTrue(unifiedDialog.contains("ProvideViewerThemeContext(viewerThemeContext)"))
        assertFalse(unifiedDialog.contains("ViewerTheme("))
        assertTrue(unifiedDialog.contains("viewerOutlinedTextFieldColors()"))

        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("displayScale = applicationSettings.displayScale.multiplier"))
        assertTrue(mainPage.contains("rememberViewerThemeContext()"))
        assertTrue(mainPage.contains("viewerThemeContext = viewerThemeContext"))
    }

}
