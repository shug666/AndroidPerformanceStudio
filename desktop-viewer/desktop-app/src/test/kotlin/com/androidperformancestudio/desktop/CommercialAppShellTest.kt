package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.shell.NAVIGATION_COLLAPSE_BREAKPOINT_DP
import com.androidperformancestudio.desktop.shell.WorkspaceContext
import com.androidperformancestudio.desktop.shell.navigationIconResource
import com.androidperformancestudio.desktop.shell.navigationRailCollapsedFor
import com.androidperformancestudio.desktop.shell.resolvedNavigationRailCollapsed
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommercialAppShellTest {
    @Test
    fun `desktop window honors the commercial minimum size`() {
        val main = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/Main.kt"))

        assertTrue(main.contains("window.minimumSize = java.awt.Dimension(1280, 720)"))
    }

    @Test
    fun `navigation rail collapses below the documented minimum width`() {
        assertTrue(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP - 1))
        assertFalse(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP))
        assertFalse(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP + 160))
        assertTrue(resolvedNavigationRailCollapsed(1100, null))
        assertFalse(resolvedNavigationRailCollapsed(1100, false))
        assertTrue(resolvedNavigationRailCollapsed(1440, true))
    }

    @Test
    fun `every destination projects a distinct navigation icon`() {
        val icons = AppDestination.entries.map { destination -> destination.navigationIconResource }

        assertEquals(AppDestination.entries.size, icons.toSet().size)
    }

    @Test
    fun `navigation rail draws an icon per item and keeps only icons when collapsed`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )

        assertTrue(shell.contains("val icon: DrawableResource"))
        assertTrue(shell.contains("icon = it.navigationIconResource"))
        // A single shared row renders the icon, so collapsing cannot shift it horizontally.
        assertEquals(1, Regex("painterResource\\(item\\.icon\\)").findAll(shell).count())
        assertTrue(shell.contains("if (!collapsed) {"))
        assertTrue(shell.contains("PaddingValues(horizontal = StudioTokens.navigationItemHorizontalPadding)"))
        assertTrue(shell.contains("painterResource(navigationRailToggleIcon(collapsed))"))
        assertFalse(shell.contains("item.label.take(1)"))
    }

    @Test
    fun `navigation icon drawables ship with the shared UI resources`() {
        val drawables = Path.of("../ui-components/src/main/composeResources/drawable")
        val expected =
            listOf(
                "ic_nav_home",
                "ic_nav_source_workspaces",
                "ic_nav_layout_inspector",
                "ic_nav_cpu_profiler",
                "ic_nav_trace_analyzer",
                "ic_nav_memory_profiler",
                "ic_nav_frame_profiler",
                "ic_nav_startup_profiler",
                "ic_nav_battery",
                "ic_nav_network",
                "ic_nav_gpu_inspector",
                "ic_nav_benchmark",
                "ic_nav_method_recording",
                "ic_nav_rail_collapse",
                "ic_nav_rail_expand",
            )

        expected.forEach { name ->
            assertTrue(Files.exists(drawables.resolve("$name.svg")), "missing navigation icon: $name")
            assertTrue(
                Files.readString(drawables.resolve("$name.svg")).startsWith("<svg"),
                "navigation icon must be an SVG vector: $name",
            )
        }
    }

    @Test
    fun `workspace context is a read only shell projection`() {
        val context =
            WorkspaceContext(
                deviceLabel = "Pixel 9",
                packageName = "com.example.app",
                processName = "com.example.app:worker",
                artifactLabel = "baseline.perfetto-trace",
            )

        assertEquals("Pixel 9", context.deviceLabel)
        assertEquals("com.example.app", context.packageName)
        assertEquals("com.example.app:worker", context.processName)
        assertEquals("baseline.perfetto-trace", context.artifactLabel)
    }

    @Test
    fun `hidden retained destinations block keyboard while shell handles global shortcuts first`() {
        val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")
        val desktopMain = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))

        assertTrue(desktopMain.contains(".blockKeyboardInputWhenInactive(active)"))
        assertTrue(desktopMain.contains("onPreviewKeyEvent { true }"))
        assertTrue(shell.contains(".onPreviewKeyEvent { event ->"))
        assertTrue(shell.contains("LaunchedEffect(navigator.destination)"))
        assertTrue(shell.contains("shellFocusRequester.requestFocus()"))
        assertTrue(shell.contains("event.key == Key.Comma"))
    }

    @Test
    fun `desktop main page delegates chrome to commercial shell and retains its feature host`() {
        val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")
        val desktopMain = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))

        assertTrue(desktopMain.contains("CommercialAppShell("))
        assertTrue(desktopMain.contains("navigator.retainedDestinations.forEach"))
        assertTrue(shell.contains("StudioTopBar("))
        assertTrue(shell.contains("StudioNavigationRail("))
        assertTrue(shell.contains("StudioStatusBar("))
        assertTrue(shell.contains("StudioCommandPalette("))
        assertTrue(shell.contains("navigator::open"))
        assertFalse(shell.contains("UniversalProfilerModel"))
        assertFalse(shell.contains("UniversalTimelineEvent"))
    }
}
