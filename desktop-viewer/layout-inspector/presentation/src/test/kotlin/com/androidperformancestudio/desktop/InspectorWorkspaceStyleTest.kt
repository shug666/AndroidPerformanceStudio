package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class InspectorWorkspaceStyleTest {
    private val source =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))

    @Test
    fun `inspector uses one localized workspace header without changing pane sizing`() {
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(source).count())
        assertTrue(source.contains("title = localizedStringResource(Res.string.layout_inspector, uiLanguage)"))
        assertTrue(source.contains(".testTag(\"inspector-workspace-header\")"))
        assertTrue(source.indexOf("HeaderToolbar(") < source.indexOf("StudioWorkspaceHeader("))
        assertTrue(source.indexOf("StudioWorkspaceHeader(") < source.indexOf("correlationHint?.let"))
        assertTrue(source.contains("StudioTokens.compactContentPadding"))
        assertTrue(source.contains("val availableHeightDp = maxHeight.value"))
        assertTrue(source.contains("val availableWidthDp = maxWidth.value"))
        assertTrue(source.contains("showHierarchy = panelVisibility.showHierarchy"))
        assertTrue(source.contains("showDetails = panelVisibility.showDetails"))
    }

    @Test
    fun `inspector keeps root keyboard navigation capture and timeline controls`() {
        assertTrue(source.contains(".onPreviewKeyEvent { event ->"))
        assertTrue(source.contains("onNavigateHome = onNavigateHome"))
        assertTrue(source.contains("ViewerAction.TOGGLE_HIERARCHY"))
        assertTrue(source.contains("ViewerAction.TOGGLE_FINDINGS"))
        assertTrue(source.contains("ViewerAction.TOGGLE_DETAILS"))
        assertTrue(source.contains("SelectionContainer {"))
        assertTrue(source.contains("PaneLayout.fit("))
        assertTrue(source.contains("FindingsLayout.fit("))
    }
}
