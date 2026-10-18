package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class InspectorCardBoundaryTest {
    private val source =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))

    @Test
    fun `properties stay a structural pane with selectable values and expandable rows`() {
        val pane =
            source
                .substringAfter("internal fun DetailsPane(")
                .substringBefore("private fun com.androidperformancestudio.compose.inspection.ComposableDetail.findValue(")

        assertTrue(pane.contains("Column(modifier.background(colors.panel))"))
        assertTrue(pane.contains("LazyColumn(state = detailsListState"))
        assertTrue(pane.contains(".clickable(onClick = onToggle)"))
        assertTrue(pane.contains("SelectionContainer {"))
        assertTrue(pane.contains("onLoadComposeParameter(row.composeReference)"))
        assertFalse(pane.contains("StudioCard("))
    }

    @Test
    fun `preview and zoom controls retain their dedicated surfaces`() {
        val canvas = source.substringAfter("private fun PreviewPane(").substringBefore("internal fun DetailsPane(")

        assertTrue(canvas.contains("shadowElevation = 8.dp"))
        assertTrue(canvas.contains("canvasCornerRadiusDp(appOnly)"))
        assertTrue(canvas.contains("PreviewZoomButton("))
        assertFalse(canvas.contains("StudioCard("))
    }
}
