package com.androidperformancestudio.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window hosts its menu bar with Compose's own `MenuBar` control, which creates the `JMenuBar`
 * once and only clears it when the window is disposed. Navigation only swaps the published content;
 * disposing a menu composition would call `JMenuBar.removeAll()` and flash the macOS menu bar.
 */
class ActiveWindowMenuBarTest {
    @Test
    fun `menu readiness is reported once per destination`() {
        val state = MenuBarHostState()

        assertTrue(state.reportReadinessOnce("HOME"))
        assertFalse(state.reportReadinessOnce("HOME"))
        assertTrue(state.reportReadinessOnce("SETTINGS"))
        assertFalse(state.reportReadinessOnce("SETTINGS"))
    }
}
