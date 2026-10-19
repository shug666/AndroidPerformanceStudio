package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the customizable home layout: drag-to-reorder grids, the quick-action
 * management dialog, and the shared module order that drives both the home grid and the rail.
 *
 * The drag hit-testing math is covered by [HomeLayoutDropIndexTest]; these tests pin the wiring.
 */
class HomeCustomizationTest {
    private val home: String
        get() = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))

    private val shell: String
        get() = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"))

    private val mainPage: String
        get() = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))

    @Test
    fun `module and quick action grids support long-press drag with live reorder`() {
        // Live reorder is driven by a container overlay using long-press-drag + animateItem.
        assertTrue(home.contains("detectDragGesturesAfterLongPress"))
        assertTrue(home.contains("Modifier.animateItem()"))
        assertTrue(home.contains("dropIndexForGrid("))
        // The dragged card follows the pointer via a deferred offset; non-dragged cards animate.
        assertTrue(home.contains(".offset {"))
        assertTrue(home.contains(".zIndex(1f)"))
        // A short tap on a card still routes to the item (open module / run action).
        assertTrue(home.contains("detectTapGestures"))
    }

    @Test
    fun `column count is frozen for the duration of a drag`() {
        assertTrue(home.contains("frozenColumns"))
        assertTrue(home.contains("val cols = frozenColumns ?: columnCount"))
    }

    @Test
    fun `quick action row ends with a non-draggable edit entry that opens the management dialog`() {
        assertTrue(home.contains("QuickActionEditCard"))
        assertTrue(home.contains("quickActionsDialog"))
        assertTrue(home.contains("QuickActionsManageDialog("))
        // The edit card is a trailing item outside the reorderable set.
        assertTrue(home.contains("reorderableCount = actions.size"))
        assertTrue(home.contains("trailingItem ="))
    }

    @Test
    fun `the management dialog lists the finite catalog with add and remove`() {
        assertTrue(home.contains("QuickActionType.entries.forEach"))
        assertTrue(home.contains("controller.addQuickAction(type)"))
        assertTrue(home.contains("controller.removeQuickAction(type)"))
        // No affordance to create arbitrary custom actions.
        assertFalse(home.contains("customAction"))
        assertFalse(home.contains("runCommand"))
    }

    @Test
    fun `reordering a module mutates the shared controller and the rail follows`() {
        assertTrue(home.contains("controller.moveModule(from, to)") || home.contains("controller.moveModule"))
        assertTrue(shell.contains("listOf(AppDestination.HOME) + moduleOrder"))
    }

    @Test
    fun `the controller is created once at the shell level and shared by home and rail`() {
        assertTrue(mainPage.contains("HomeLayoutController.desktop()"))
        assertTrue(mainPage.contains("homeLayout = homeLayout"))
        assertTrue(mainPage.contains("moduleOrder = homeLayout.moduleOrder"))
    }

    @Test
    fun `the four default quick actions map to the existing import and capture flows`() {
        assertEquals(
            listOf(
                QuickActionType.START_CAPTURE,
                QuickActionType.IMPORT_TRACE,
                QuickActionType.ANALYZE_HPROF,
                QuickActionType.RUN_STARTUP_TEST,
            ),
            QuickActionType.DEFAULTS,
        )
        // The catalog also offers one open-module action per analysis module.
        val moduleOpenActions = QuickActionType.entries.filter { it.destination != null }
        assertEquals(DEFAULT_MODULE_ORDER.size, moduleOpenActions.size)
        assertEquals(DEFAULT_MODULE_ORDER.toSet(), moduleOpenActions.mapNotNull { it.destination }.toSet())
    }

    @Test
    fun `home stays pinned first in the rail regardless of module order`() {
        assertTrue(shell.contains("listOf(AppDestination.HOME) + moduleOrder"))
        assertFalse(DEFAULT_MODULE_ORDER.contains(AppDestination.HOME))
    }
}
