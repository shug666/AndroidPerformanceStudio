package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PanelVisibilityTest {
    @Test
    fun `all inspector panels are visible initially`() {
        assertEquals(
            PanelVisibility(showHierarchy = true, showFindings = true, showDetails = true),
            PanelVisibility(),
        )
    }

    @Test
    fun `each header button toggles only its matching panel`() {
        val initial = PanelVisibility()

        assertEquals(initial.copy(showHierarchy = false), initial.toggleHierarchy())
        assertEquals(initial.copy(showFindings = false), initial.toggleFindings())
        assertEquals(initial.copy(showDetails = false), initial.toggleDetails())
    }

    @Test
    fun `workspace sizing and both drag handlers account for visible side panels`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val calls =
            Regex(
                "PaneLayout\\.(fit|dragHierarchy|dragProperties)\\((.*?)\n\\s*\\)",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(source).toList()

        assertEquals(listOf("fit", "dragHierarchy", "dragProperties"), calls.map { it.groupValues[1] })
        calls.forEach { call ->
            val arguments = call.groupValues[2]
            assertTrue(arguments.contains("widths = paneWidths"), call.value)
            assertTrue(arguments.contains("showHierarchy = panelVisibility.showHierarchy"), call.value)
            assertTrue(arguments.contains("showDetails = panelVisibility.showDetails"), call.value)
        }
    }
}
