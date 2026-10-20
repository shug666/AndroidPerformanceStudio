package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the customizable home layout: the reorderable module grid and the shared
 * module order that drives both the home grid and the rail.
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
    fun `module grid supports long-press drag with live reorder`() {
        // Live reorder is driven by a container overlay using long-press-drag + animateItem.
        assertTrue(home.contains("detectDragGesturesAfterLongPress"))
        assertTrue(home.contains("Modifier.animateItem()"))
        assertTrue(home.contains("dropIndexForGrid("))
        // The dragged card follows the pointer via a deferred offset; non-dragged cards animate.
        assertTrue(home.contains(".offset {"))
        assertTrue(home.contains(".zIndex(1f)"))
        // A short tap on a card still routes to the item (open module).
        assertTrue(home.contains("detectTapGestures"))
    }

    @Test
    fun `column count is frozen for the duration of a drag`() {
        assertTrue(home.contains("frozenColumns"))
        assertTrue(home.contains("val cols = frozenColumns ?: columnCount"))
    }

    @Test
    fun `reordering a module mutates the shared controller and the rail follows`() {
        assertTrue(home.contains("controller.moveModule"))
        assertTrue(shell.contains("listOf(AppDestination.HOME) + moduleOrder"))
    }

    @Test
    fun `the controller is created once at the shell level and shared by home and rail`() {
        assertTrue(mainPage.contains("HomeLayoutController.desktop()"))
        assertTrue(mainPage.contains("homeLayout = homeLayout"))
        assertTrue(mainPage.contains("moduleOrder = homeLayout.moduleOrder"))
    }

    @Test
    fun `home stays pinned first in the rail regardless of module order`() {
        assertTrue(shell.contains("listOf(AppDestination.HOME) + moduleOrder"))
        assertFalse(DEFAULT_MODULE_ORDER.contains(AppDestination.HOME))
    }

    @Test
    fun `quick actions section is removed from the home screen`() {
        // The Quick Actions section, its cards, dialog, and the catalog are all gone.
        assertFalse(home.contains("Res.string.quick_actions"))
        assertFalse(home.contains("HomeQuickActionGrid"))
        assertFalse(home.contains("QuickActionCard"))
        assertFalse(home.contains("QuickActionsManageDialog"))
        assertFalse(home.contains("homeQuickActionColumnCount"))
    }

    @Test
    fun `tap and drag handlers stay live after a reorder`() {
        // The pointerInput overlay is keyed on stable values, so its coroutine launches once.
        // The tap/drag callbacks must be kept live via rememberUpdatedState, otherwise a tap after
        // a drag would open the module that *used to* be at the tapped slot.
        assertTrue(home.contains("rememberUpdatedState(onTap)"), "onTap must be rememberUpdatedState")
        assertTrue(home.contains("rememberUpdatedState(onMove)"), "onMove must be rememberUpdatedState")
        assertTrue(home.contains("currentOnTap(it.index)"))
        assertTrue(home.contains("currentOnMove(di, target)"))
        // The stale-closure anti-pattern: the overlay must NOT call onTap/onMove directly.
        assertFalse(home.contains("onTap(it.index)"))
        assertFalse(Regex("onMove\\(di, target\\)").containsMatchIn(home))
    }
}
