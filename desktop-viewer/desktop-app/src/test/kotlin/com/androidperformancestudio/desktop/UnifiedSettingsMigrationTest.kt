package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedSettingsMigrationTest {
    @Test
    fun `unified settings is a routed page instead of a dialog window`() {
        val page = desktopSource("DesktopAppSettingsPage.kt")
        val shell = desktopSource("DesktopAppMainPage.kt")

        assertFalse(page.contains("DialogWindow("))
        assertFalse(page.contains("rememberDialogState("))
        assertTrue(page.contains("Modifier.fillMaxSize()"))
        assertTrue(page.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 220"))
        assertTrue(shell.contains("AppDestination.SETTINGS ->"))
        assertTrue(shell.contains("DesktopAppSettingsPage("))
        assertTrue(shell.contains("navigator.open(AppDestination.SETTINGS)"))
        assertFalse(shell.contains("showSettings"))
    }

    @Test
    fun `layout inspector complete settings are embedded`() {
        val content = layoutInspectorSource("LayoutInspectorSettingsContent.kt")

        listOf(
            "hideInvisibleHierarchyViews",
            "hideInvisibleFindings",
            "hideHierarchyIndices",
            "showHierarchyIds",
            "showHierarchyLayerVisibilityButtons",
            "showVisibleViewBounds",
            "canvasHitTestOrder",
            "snapshotSizeMultiplier",
            "canvasColorPresets",
            "CanvasBorderColors().normal",
            "CanvasBorderColors().hovered",
            "CanvasBorderColors().selected",
        ).forEach { setting ->
            assertTrue(content.contains(setting), "Missing Layout Inspector setting: $setting")
        }
    }

    @Test
    fun `simpleperf complete settings and live capture context are embedded`() {
        val dialog = desktopSource("DesktopAppSettingsPage.kt")
        val shell = desktopSource("DesktopAppMainPage.kt")
        val settings = simpleperfPresentationSource("CaptureSettingsSection.kt")
        val workspace = simpleperfDesktopSource("SimpleperfMainPage.kt")

        listOf(
            "SAMPLING_TEMPLATE",
            "CAPTURE_CONFIGURATION",
            "ADVANCED_PARAMETERS",
            "FLAME_GRAPH",
            "SIMPLEPERF_ENGINE",
            "USER_GUIDE",
        ).forEach { section -> assertTrue(settings.contains(section), "Missing Simpleperf section: $section") }
        assertTrue(settings.contains("fun SimpleperfSettingsSectionContent("))
        assertTrue(dialog.contains("SimpleperfSettingsSectionContent("))
        assertTrue(dialog.contains("simpleperfExpanded"))
        assertTrue(dialog.contains("CaptureSettingsSection.entries.forEach"))
        assertTrue(dialog.contains("onSimpleperfSectionSelected"))
        assertTrue(dialog.contains("width(UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP.dp)"))
        assertTrue(dialog.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 220"))
        assertFalse(
            dialog.contains("import com.androidperformancestudio.presentation.SimpleperfSettingsContent"),
        )
        assertTrue(dialog.contains("context?.onSelectTemplate"))
        assertTrue(dialog.contains("context?.onUpdateSamplingParameters"))
        assertTrue(workspace.contains("onCaptureSettingsContextChanged"))
        assertTrue(workspace.contains("captureSettingsManagedExternally = onOpenPreferences != null"))
        assertTrue(shell.contains("simpleperfCaptureSettingsContext = simpleperfCaptureSettingsContext"))
        assertTrue(shell.contains("simpleperfSettingsSection = section"))
    }

    private fun desktopSource(fileName: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/$fileName"))

    private fun layoutInspectorSource(fileName: String): String =
        Files.readString(
            Path.of("../layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/$fileName"),
        )

    private fun simpleperfPresentationSource(fileName: String): String =
        Files.readString(
            Path.of(
                "../simpleperf-viewer/presentation/src/main/kotlin/" +
                    "com/androidperformancestudio/presentation/$fileName",
            ),
        )

    private fun simpleperfDesktopSource(fileName: String): String =
        Files.readString(
            Path.of(
                "../simpleperf-viewer/app-desktop/src/main/kotlin/" +
                    "com/androidperformancestudio/desktop/$fileName",
            ),
        )
}
