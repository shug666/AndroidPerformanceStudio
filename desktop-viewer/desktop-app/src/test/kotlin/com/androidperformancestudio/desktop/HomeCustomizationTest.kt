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

    @Test
    fun `a drag lifts the card with a phase state machine and an animatable lift progress`() {
        // The drag-polish lift is driven by a phase enum + an Animatable progress, with spring
        // specs for lift and drop.
        assertTrue(home.contains("enum class DragPhase"))
        assertTrue(home.contains("DragPhase.Lifting"))
        assertTrue(home.contains("DragPhase.Dragging"))
        assertTrue(home.contains("DragPhase.Dropping"))
        assertTrue(home.contains("Animatable(0f)"))
        assertTrue(home.contains("LiftSpring"))
        assertTrue(home.contains("DropSpring"))
        // Lift runs in parallel with pointer-follow (no dead-time): onDragStart launches the lift
        // animation in the same handler that pins the card to the pointer.
        assertTrue(home.contains("liftProgress.animateTo(1f, LiftSpring)"))
        assertTrue(home.contains("liftProgress.animateTo(0f, DropSpring)"))
    }

    @Test
    fun `the dragged slot shows a dashed placeholder while other cards keep their content`() {
        // The dragged slot renders a dashed placeholder instead of the card; the placeholder rides
        // animateItem() so it shuffles with the grid. Non-dragged slots still render itemContent.
        assertTrue(home.contains("DraggedSlotPlaceholder"))
        assertTrue(home.contains("dashPathEffect"))
        assertTrue(home.contains("Modifier.animateItem()"))
        // The lifted card is lifted OUT of the grid slot (no in-slot offset/zIndex on the dragged
        // item anymore); the slot only carries the placeholder.
        assertFalse(home.contains("dragPosition.x - grabLocal.x - slot.offset.x"))
    }

    @Test
    fun `the lifted card floats as an overlay above the grid only during a drag`() {
        // The floating overlay is gated on draggedIndex != null, sized to the dragged slot, and
        // positioned at dragPosition - grabLocal. It reuses itemContent for the dragged entry.
        assertTrue(home.contains("val liftedIndex = draggedIndex"))
        assertTrue(home.contains("if (liftedIndex != null)"))
        assertTrue(home.contains("itemContent(liftedIndex, items[liftedIndex])"))
        assertTrue(home.contains("graphicsLayer"))
        assertTrue(home.contains("shadowElevation"))
        // Scale/shadow are derived from the lift progress via the mapping helpers.
        assertTrue(home.contains("liftScaleFor("))
        assertTrue(home.contains("liftShadowDp("))
    }

    @Test
    fun `drop still commits the reorder and clears drag state`() {
        // The drop-index math is unchanged; on drag end/cancel the dragged state is cleared.
        assertTrue(home.contains("dropIndexForGrid("))
        assertTrue(home.contains("onDragEnd ="))
        assertTrue(home.contains("onDragCancel ="))
        assertTrue(home.contains("draggedIndex = null"))
        assertTrue(home.contains("frozenColumns = null"))
        // A short tap still routes to the item (open module); the long-press drag does not open it.
        assertTrue(home.contains("detectTapGestures"))
        assertTrue(home.contains("detectDragGesturesAfterLongPress"))
    }
}
