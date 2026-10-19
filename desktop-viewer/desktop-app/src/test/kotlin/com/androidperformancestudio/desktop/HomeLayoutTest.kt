package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeLayoutStoreTest {
    private fun store(values: MutableMap<String, String> = mutableMapOf()) =
        HomeLayoutPreferencesStore(values::get, values::set)

    @Test
    fun `module order round-trips through the store`() {
        val values = mutableMapOf<String, String>()
        val s = store(values)
        val order = DEFAULT_MODULE_ORDER.reversed()
        assertTrue(s.save(order, QuickActionType.DEFAULTS))
        assertEquals(order, s.loadModuleOrder())
    }

    @Test
    fun `quick actions round-trip and stay ordered`() {
        val values = mutableMapOf<String, String>()
        val s = store(values)
        val actions = listOf(
            QuickActionType.OPEN_PERFETTO,
            QuickActionType.START_CAPTURE,
            QuickActionType.OPEN_GPU_INSPECTOR,
        )
        assertTrue(s.save(DEFAULT_MODULE_ORDER, actions))
        assertEquals(actions, s.loadQuickActions())
    }

    @Test
    fun `unknown module IDs are dropped on load, keeping relative order`() {
        val values = mutableMapOf<String, String>()
        values["application.home.moduleOrder"] =
            "SIMPLEPERF,UNKNOWN_FUTURE_MODULE,PERFETTO,REVERSE_NET"
        val s = store(values)
        val loaded = s.loadModuleOrder()
        // Unknown dropped; the two recognized keep their order; missing defaults appended.
        assertEquals(AppDestination.SIMPLEPERF, loaded[0])
        assertEquals(AppDestination.PERFETTO, loaded[1])
        assertEquals(AppDestination.REVERSE_NET, loaded[2])
        // Everything from the default list is present exactly once.
        assertEquals(DEFAULT_MODULE_ORDER.toSet(), loaded.toSet())
        assertEquals(DEFAULT_MODULE_ORDER.size, loaded.size)
    }

    @Test
    fun `unknown quick action IDs are dropped on load`() {
        val values = mutableMapOf<String, String>()
        values["application.home.quickActions"] = "start_capture,unknown_action,import_trace"
        val s = store(values)
        val loaded = s.loadQuickActions()
        assertEquals(
            listOf(QuickActionType.START_CAPTURE, QuickActionType.IMPORT_TRACE),
            loaded,
        )
    }

    @Test
    fun `a fresh install restores the default module order and four quick actions`() {
        val s = store(mutableMapOf())
        assertEquals(DEFAULT_MODULE_ORDER, s.loadModuleOrder())
        assertEquals(QuickActionType.DEFAULTS, s.loadQuickActions())
    }

    @Test
    fun `a partial module list is padded with missing defaults at the end`() {
        val values = mutableMapOf<String, String>()
        values["application.home.moduleOrder"] = "REVERSE_NET,PERFETTO"
        val s = store(values)
        val loaded = s.loadModuleOrder()
        assertEquals(AppDestination.REVERSE_NET, loaded[0])
        assertEquals(AppDestination.PERFETTO, loaded[1])
        // The remaining defaults follow, in default order.
        assertEquals(DEFAULT_MODULE_ORDER.filter { it !in loaded.take(2) }, loaded.drop(2))
    }

    @Test
    fun `non-default quick actions are not padded back to the four defaults`() {
        val values = mutableMapOf<String, String>()
        // User explicitly keeps only one non-default action; defaults must NOT be restored.
        values["application.home.quickActions"] = "open_gpu_inspector"
        val s = store(values)
        assertEquals(listOf(QuickActionType.OPEN_GPU_INSPECTOR), s.loadQuickActions())
    }

    @Test
    fun `null-store reads degrade to defaults and writes report failure`() {
        // Mirrors the desktop() store when prefs are unavailable: reads return null, writes throw.
        val s = HomeLayoutPreferencesStore(
            readValue = { null },
            writeValue = { _, _ -> error("preferences unavailable") },
            flush = {},
        )
        assertEquals(DEFAULT_MODULE_ORDER, s.loadModuleOrder())
        assertEquals(QuickActionType.DEFAULTS, s.loadQuickActions())
        assertFalse(s.save(DEFAULT_MODULE_ORDER, QuickActionType.DEFAULTS))
    }
}

class HomeLayoutControllerTest {
    private fun controller(values: MutableMap<String, String> = mutableMapOf()): HomeLayoutController =
        HomeLayoutController(HomeLayoutPreferencesStore(values::get, values::set))

    @Test
    fun `a fresh controller mirrors the hardcoded default order and four actions`() {
        val c = controller()
        assertEquals(DEFAULT_MODULE_ORDER, c.moduleOrder.toList())
        assertEquals(QuickActionType.DEFAULTS, c.quickActions.toList())
    }

    @Test
    fun `moveModule reorders the list live and persists`() {
        val values = mutableMapOf<String, String>()
        val c = controller(values)
        // Move PERFETTO (index 2) to index 0.
        val perfettoIndex = c.moduleOrder.indexOf(AppDestination.PERFETTO)
        c.moveModule(perfettoIndex, 0)
        assertEquals(AppDestination.PERFETTO, c.moduleOrder[0])
        assertEquals(DEFAULT_MODULE_ORDER.size, c.moduleOrder.size)
        // Persisted: reloading from the same store yields the new order.
        val reloaded = HomeLayoutController(HomeLayoutPreferencesStore(values::get, values::set))
        assertEquals(c.moduleOrder.toList(), reloaded.moduleOrder.toList())
    }

    @Test
    fun `moveModule ignores out-of-range indices`() {
        val c = controller()
        val before = c.moduleOrder.toList()
        c.moveModule(-1, 0)
        c.moveModule(0, c.moduleOrder.size)
        c.moveModule(3, 3)
        assertEquals(before, c.moduleOrder.toList())
    }

    @Test
    fun `moveQuickAction reorders the action row live and persists`() {
        val values = mutableMapOf<String, String>()
        val c = controller(values)
        c.moveQuickAction(0, 1) // START_CAPTURE after IMPORT_TRACE
        assertEquals(QuickActionType.IMPORT_TRACE, c.quickActions[0])
        assertEquals(QuickActionType.START_CAPTURE, c.quickActions[1])
        val reloaded = HomeLayoutController(HomeLayoutPreferencesStore(values::get, values::set))
        assertEquals(c.quickActions.toList(), reloaded.quickActions.toList())
    }

    @Test
    fun `addQuickAction appends a catalog action and persists, ignoring duplicates`() {
        val values = mutableMapOf<String, String>()
        val c = controller(values)
        c.addQuickAction(QuickActionType.OPEN_GPU_INSPECTOR)
        assertEquals(QuickActionType.OPEN_GPU_INSPECTOR, c.quickActions.last())
        assertEquals(5, c.quickActions.size)
        c.addQuickAction(QuickActionType.OPEN_GPU_INSPECTOR) // no-op
        assertEquals(5, c.quickActions.size)
        val reloaded = HomeLayoutController(HomeLayoutPreferencesStore(values::get, values::set))
        assertEquals(c.quickActions.toList(), reloaded.quickActions.toList())
    }

    @Test
    fun `removeQuickAction removes and persists`() {
        val values = mutableMapOf<String, String>()
        val c = controller(values)
        c.removeQuickAction(QuickActionType.START_CAPTURE)
        assertFalse(QuickActionType.START_CAPTURE in c.quickActions)
        assertEquals(3, c.quickActions.size)
        val reloaded = HomeLayoutController(HomeLayoutPreferencesStore(values::get, values::set))
        assertEquals(c.quickActions.toList(), reloaded.quickActions.toList())
    }
}

class HomeLayoutDropIndexTest {
    private fun rect(left: Int, top: Int, w: Int, h: Int) =
        IntRect(left, top, left + w, top + h)

    @Test
    fun `pointer inside an item targets that item index`() {
        // 3 columns, one row of three 100-wide items.
        val rects = listOf(rect(0, 0, 100, 80), rect(110, 0, 100, 80), rect(220, 0, 100, 80))
        assertEquals(0, dropIndexForGrid(rects, 50, 40, 3))
        assertEquals(1, dropIndexForGrid(rects, 160, 40, 3))
        assertEquals(2, dropIndexForGrid(rects, 270, 40, 3))
    }

    @Test
    fun `pointer in a gap targets the next forward item`() {
        val rects = listOf(rect(0, 0, 100, 80), rect(110, 0, 100, 80), rect(220, 0, 100, 80))
        // Gap between item 0 and item 1 (x=105).
        assertEquals(1, dropIndexForGrid(rects, 105, 40, 3))
    }

    @Test
    fun `pointer before the first item targets index 0`() {
        val rects = listOf(rect(20, 0, 100, 80), rect(130, 0, 100, 80))
        assertEquals(0, dropIndexForGrid(rects, 5, 40, 2))
    }

    @Test
    fun `pointer after the last item targets size`() {
        val rects = listOf(rect(0, 0, 100, 80), rect(110, 0, 100, 80))
        assertEquals(2, dropIndexForGrid(rects, 250, 40, 2))
    }

    @Test
    fun `multi-row grid uses the pointer row for the column target`() {
        // Two rows of 3; row 0 tops at y=0, row 1 tops at y=90.
        val rects = (0..1).flatMap { row ->
            (0..2).map { col -> rect(col * 110, row * 90, 100, 80) }
        }
        // Pointer over row 1, column 1 (index 4).
        assertEquals(4, dropIndexForGrid(rects, 160, 130, 3))
        // Pointer over row 0, column 0 (index 0).
        assertEquals(0, dropIndexForGrid(rects, 50, 40, 3))
    }

    @Test
    fun `partial last row targets the trailing index after the last filled slot`() {
        // 3 columns, 4 items: row 0 full, row 1 has one item (index 3).
        val rects = listOf(
            rect(0, 0, 100, 80), rect(110, 0, 100, 80), rect(220, 0, 100, 80),
            rect(0, 90, 100, 80),
        )
        // Pointer far right of the partial last row → index 4 (after the single item).
        assertEquals(4, dropIndexForGrid(rects, 270, 130, 3))
        // Pointer over the single last-row item → index 3.
        assertEquals(3, dropIndexForGrid(rects, 50, 130, 3))
    }

    @Test
    fun `empty grid returns index 0`() {
        assertEquals(0, dropIndexForGrid(emptyList(), 50, 50, 3))
    }
}
