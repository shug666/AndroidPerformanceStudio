package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.viewerColors
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppHomePageLayoutTest {
    private val source: String
        get() = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))

    @Test
    fun `home dashboard renders on the shell canvas background`() {
        assertTrue(source.contains("color = colors.canvasBackground"))
        assertEquals(viewerColors(darkTheme = false).canvasBackground, viewerColors(darkTheme = false).workspace)
        assertEquals(viewerColors(darkTheme = true).canvasBackground, viewerColors(darkTheme = true).workspace)
    }

    @Test
    fun `home dashboard splits into a main column and a fixed width rail`() {
        assertTrue(source.contains("HOME_TWO_COLUMN_MIN_WIDTH_DP"))
        assertTrue(source.contains("Modifier.width(HOME_RAIL_WIDTH_DP.dp)"))
        assertTrue(source.contains("HomeMainColumn("))
        assertTrue(source.contains("HomeRailColumn("))
        assertTrue(source.contains("HomeDateCard(language)"))
        assertTrue(source.contains("HomeDeviceSummary("))
    }

    @Test
    fun `home module grid stays adaptive`() {
        assertEquals(3, homeGridColumnCount(HOME_RAIL_WIDTH_DP + 500))
        assertEquals(3, homeGridColumnCount(900))
        assertEquals(2, homeGridColumnCount(600))
        assertEquals(1, homeGridColumnCount(420))
        assertTrue(HOME_MODULE_CARD_HEIGHT_DP < 120)
        assertTrue(source.contains("RoundedCornerShape(StudioTokens.cardRadius)"))
        assertTrue(source.contains("entries.chunked(columnCount)"))
        assertTrue(source.contains("height(HOME_MODULE_CARD_HEIGHT_DP.dp)"))
        assertTrue(source.contains("entry.destination.navigationIconResource"))
    }

    @Test
    fun `recent session rows stay compact`() {
        val row =
            source
                .substringAfter("private fun RecentSessionRow(")
                .substringBefore("private fun StudioRecentItem.featureLabel(")

        assertTrue(row.contains("vertical = 3.dp"))
        // Material's 40 dp minimum button height made every row tall.
        assertFalse(row.contains("TextButton("))
        assertTrue(row.contains(".clickable(onClick = onOpen)"))
    }

    @Test
    fun `device refresh updates in place instead of flashing the loading state`() {
        val effect =
            source
                .substringAfter("LaunchedEffect(active, deviceOverviewSource, deviceRefreshRevision)")
                .substringBefore("val entries =")

        assertTrue(effect.contains("deviceOverview = deviceOverviewSource.load()"))
        // Clearing the state before loading swapped the panel to the loading state on every refresh.
        assertFalse(effect.contains("deviceOverview = null"))
        // The very first load still shows loading because the state starts empty.
        assertTrue(
            source.contains(
                "var deviceOverview by remember { mutableStateOf<StudioDeviceOverview?>(null) }",
            ),
        )
    }

    @Test
    fun `quick actions stay a four up row until the column narrows`() {
        assertEquals(4, homeQuickActionColumnCount(1020))
        assertEquals(4, homeQuickActionColumnCount(720))
        assertEquals(2, homeQuickActionColumnCount(680))
        assertEquals(1, homeQuickActionColumnCount(360))
        assertTrue(source.contains("homeQuickActionColumnCount(maxWidth.value.toInt())"))
    }

    @Test
    fun `home metrics only read the real device and recent projections`() {
        assertEquals(2, Regex("StudioMetricCard\\(").findAll(source).count())
        assertTrue(source.contains("connectedCount"))
        assertTrue(source.contains("val recentValue ="))
        assertTrue(source.contains("recent_artifacts_scope"))
        // Sections without a backing data source are intentionally not rendered.
        assertFalse(source.contains("recent_reports"))
        assertFalse(source.contains("ai_insights"))
    }

    @Test
    fun `recent sessions table shows only columns backed by real artifacts`() {
        listOf(
            "recent_column_name",
            "recent_column_package",
            "recent_column_type",
            "recent_column_started",
            "recent_column_action",
        ).forEach { column -> assertTrue(source.contains(column), "missing $column") }
        assertFalse(source.contains("recent_column_device"))
        assertFalse(source.contains("recent_column_duration"))
        assertFalse(source.contains("recent_column_status"))
    }

    @Test
    fun `quick actions reuse the shell navigation icons`() {
        assertTrue(source.contains("HomeQuickAction("))
        assertTrue(source.contains("HOME_QUICK_ACTION_HEIGHT_DP"))
        assertTrue(source.contains("UiComponentsRes.drawable.ic_action_play"))
        assertTrue(source.contains("UiComponentsRes.drawable.ic_action_import"))
    }

    @Test
    fun `source workspace is the last home entry`() {
        val entries = source.substringAfter("val entries =").substringBefore("val quickActions =")

        val lastEntry = entries.substring(entries.lastIndexOf("HomeFeatureEntry("))
        assertTrue(lastEntry.contains("onClick = onOpenSourceWorkspaces"))
    }

    @Test
    fun `dashboard exposes all implemented module routes`() {
        val entries = source.substringAfter("val entries =").substringBefore("val quickActions =")

        assertTrue(entries.contains("onClick = onOpenGpuInspector"))
        assertTrue(entries.contains("onClick = onOpenBenchmarkRegression"))
        assertFalse(entries.contains("onClick = onOpenMethodRecording"))
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )
        assertTrue(shell.contains("listOf(AppDestination.METHOD_RECORDING)"))
        assertTrue(shell.contains("navigator.open(destination)"))
    }
}
