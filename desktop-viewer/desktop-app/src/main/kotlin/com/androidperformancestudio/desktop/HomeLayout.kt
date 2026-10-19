package com.androidperformancestudio.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.analyze_hprof
import com.androidperformancestudio.desktop_app.generated.resources.battery_network_description
import com.androidperformancestudio.desktop_app.generated.resources.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing
import com.androidperformancestudio.desktop_app.generated.resources.cold_warm_startup_breakdown_and_baseline_profile_support
import com.androidperformancestudio.desktop_app.generated.resources.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre
import com.androidperformancestudio.desktop_app.generated.resources.discover_and_launch_android_gpu_inspector_then_index_and_verify
import com.androidperformancestudio.desktop_app.generated.resources.heap_dump_capture_object_statistics_and_class_histogram_analysis
import com.androidperformancestudio.desktop_app.generated.resources.inspect_android_view_hierarchies_screenshots_bounds_and_properties
import com.androidperformancestudio.desktop_app.generated.resources.open_trace_file
import com.androidperformancestudio.desktop_app.generated.resources.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_analyze_hprof
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_analyze_hprof_description
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_import_trace
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_import_trace_description
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_run_startup_test
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_run_startup_test_description
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_start_capture
import com.androidperformancestudio.desktop_app.generated.resources.quick_action_start_capture_description
import com.androidperformancestudio.desktop_app.generated.resources.reverse_net_home_description
import com.androidperformancestudio.desktop_app.generated.resources.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis
import com.androidperformancestudio.desktop_app.generated.resources.source_home_description
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.ui_components.generated.resources.ic_action_import
import com.androidperformancestudio.ui_components.generated.resources.ic_action_play
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_memory_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_startup_profiler
import com.androidperformancestudio.desktop.shell.navigationIconResource
import java.util.prefs.Preferences
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * The finite built-in catalog of quick actions surfaced on the home screen.
 *
 * The first four entries mirror the original hardcoded quick actions. The remaining entries are
 * one "open module" action per analysis module — launching the module straight from the quick
 * action row. IDs are stable storage keys; unknown IDs are dropped on load for forward
 * compatibility. [destination] is non-null for module-open actions so the icon/label/description
 * resolve from the destination's resources; the four originals carry dedicated resources.
 */
internal enum class QuickActionType(val id: String, val destination: AppDestination? = null) {
    START_CAPTURE("start_capture"),
    IMPORT_TRACE("import_trace"),
    ANALYZE_HPROF("analyze_hprof"),
    RUN_STARTUP_TEST("run_startup_test"),
    OPEN_LAYOUT_INSPECTOR("open_layout_inspector", AppDestination.LAYOUT_INSPECTOR),
    OPEN_SIMPLEPERF("open_simpleperf", AppDestination.SIMPLEPERF),
    OPEN_PERFETTO("open_perfetto", AppDestination.PERFETTO),
    OPEN_MEMORY_PROFILER("open_memory_profiler", AppDestination.MEMORY_PROFILER),
    OPEN_FRAME_PROFILER("open_frame_profiler", AppDestination.FRAME_PROFILER),
    OPEN_STARTUP_PROFILER("open_startup_profiler", AppDestination.STARTUP_PROFILER),
    OPEN_BATTERY_NETWORK("open_battery_network", AppDestination.BATTERY_NETWORK),
    OPEN_GPU_INSPECTOR("open_gpu_inspector", AppDestination.GPU_INSPECTOR),
    OPEN_BENCHMARK_REGRESSION("open_benchmark_regression", AppDestination.BENCHMARK_REGRESSION),
    OPEN_SOURCE_WORKSPACES("open_source_workspaces", AppDestination.SOURCE_WORKSPACES),
    OPEN_REVERSE_NET("open_reverse_net", AppDestination.REVERSE_NET),
    ;

    companion object {
        fun parse(id: String?): QuickActionType? = entries.firstOrNull { it.id == id }

        /** The four quick actions shown on a fresh install — the pre-customization default. */
        val DEFAULTS: List<QuickActionType> =
            listOf(START_CAPTURE, IMPORT_TRACE, ANALYZE_HPROF, RUN_STARTUP_TEST)
    }
}

/**
 * The default module order — the order that was hardcoded in [AppHomePage] and
 * [com.androidperformancestudio.desktop.shell.CommercialAppShell] before this change. A fresh
 * install or a layout with no recognized modules restores this order.
 */
internal val DEFAULT_MODULE_ORDER: List<AppDestination> =
    listOf(
        AppDestination.LAYOUT_INSPECTOR,
        AppDestination.SIMPLEPERF,
        AppDestination.PERFETTO,
        AppDestination.MEMORY_PROFILER,
        AppDestination.FRAME_PROFILER,
        AppDestination.STARTUP_PROFILER,
        AppDestination.BATTERY_NETWORK,
        AppDestination.GPU_INSPECTOR,
        AppDestination.BENCHMARK_REGRESSION,
        AppDestination.SOURCE_WORKSPACES,
        AppDestination.REVERSE_NET,
    )

/** The set of destinations the user is allowed to place in the module grid / rail. */
internal val REORDERABLE_MODULE_DESTINATIONS: Set<AppDestination> = DEFAULT_MODULE_ORDER.toSet()

/**
 * Resolves the catalog metadata for a [QuickActionType]. Module-open actions derive their label,
 * description, and icon from the destination; the four originals carry dedicated strings/icons.
 */
internal data class QuickActionDescriptor(
    val label: String,
    val description: String,
    val icon: DrawableResource,
)

@Suppress("FunctionName")
internal fun quickActionDescriptor(type: QuickActionType, language: UiLanguage): QuickActionDescriptor =
    when (type) {
        QuickActionType.START_CAPTURE -> QuickActionDescriptor(
            localizedStringResource(Res.string.quick_action_start_capture, language),
            localizedStringResource(Res.string.quick_action_start_capture_description, language),
            UiComponentsRes.drawable.ic_action_play,
        )
        QuickActionType.IMPORT_TRACE -> QuickActionDescriptor(
            localizedStringResource(Res.string.quick_action_import_trace, language),
            localizedStringResource(Res.string.quick_action_import_trace_description, language),
            UiComponentsRes.drawable.ic_action_import,
        )
        QuickActionType.ANALYZE_HPROF -> QuickActionDescriptor(
            localizedStringResource(Res.string.quick_action_analyze_hprof, language),
            localizedStringResource(Res.string.quick_action_analyze_hprof_description, language),
            UiComponentsRes.drawable.ic_nav_memory_profiler,
        )
        QuickActionType.RUN_STARTUP_TEST -> QuickActionDescriptor(
            localizedStringResource(Res.string.quick_action_run_startup_test, language),
            localizedStringResource(Res.string.quick_action_run_startup_test_description, language),
            UiComponentsRes.drawable.ic_nav_startup_profiler,
        )
        // Module-open actions reuse the module's own title/description/icon.
        QuickActionType.OPEN_LAYOUT_INSPECTOR -> moduleDescriptor(
            AppDestination.LAYOUT_INSPECTOR,
            Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties,
            language,
        )
        QuickActionType.OPEN_SIMPLEPERF -> moduleDescriptor(
            AppDestination.SIMPLEPERF,
            Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis,
            language,
        )
        QuickActionType.OPEN_PERFETTO -> moduleDescriptor(
            AppDestination.PERFETTO,
            Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic,
            language,
        )
        QuickActionType.OPEN_MEMORY_PROFILER -> moduleDescriptor(
            AppDestination.MEMORY_PROFILER,
            Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis,
            language,
        )
        QuickActionType.OPEN_FRAME_PROFILER -> moduleDescriptor(
            AppDestination.FRAME_PROFILER,
            Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing,
            language,
        )
        QuickActionType.OPEN_STARTUP_PROFILER -> moduleDescriptor(
            AppDestination.STARTUP_PROFILER,
            Res.string.cold_warm_startup_breakdown_and_baseline_profile_support,
            language,
        )
        QuickActionType.OPEN_BATTERY_NETWORK -> moduleDescriptor(
            AppDestination.BATTERY_NETWORK,
            Res.string.battery_network_description,
            language,
        )
        QuickActionType.OPEN_GPU_INSPECTOR -> moduleDescriptor(
            AppDestination.GPU_INSPECTOR,
            Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify,
            language,
        )
        QuickActionType.OPEN_BENCHMARK_REGRESSION -> moduleDescriptor(
            AppDestination.BENCHMARK_REGRESSION,
            Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre,
            language,
        )
        QuickActionType.OPEN_SOURCE_WORKSPACES -> moduleDescriptor(
            AppDestination.SOURCE_WORKSPACES,
            Res.string.source_home_description,
            language,
        )
        QuickActionType.OPEN_REVERSE_NET -> moduleDescriptor(
            AppDestination.REVERSE_NET,
            Res.string.reverse_net_home_description,
            language,
        )
    }

private fun moduleDescriptor(
    destination: AppDestination,
    descriptionRes: StringResource,
    language: UiLanguage,
): QuickActionDescriptor {
    val title = localizedStringResource(destination.titleResource, language)
    val description = localizedStringResource(descriptionRes, language)
    val icon = destination.navigationIconResource
    return QuickActionDescriptor(title, description, icon)
}

/**
 * Maps the catalog metadata onto an [onClick] using the navigation callbacks available at the
 * home screen. The controller never holds these callbacks — they are composable-scoped — so the
 * home page resolves them at composition time.
 */
internal fun QuickActionType.resolveOnClick(
    onOpenPerfetto: () -> Unit,
    onOpenTraceFile: () -> Unit,
    onAnalyzeHprof: () -> Unit,
    onOpenStartupProfiler: () -> Unit,
    openDestination: (AppDestination) -> Unit,
): () -> Unit =
    when (this) {
        QuickActionType.START_CAPTURE -> onOpenPerfetto
        QuickActionType.IMPORT_TRACE -> onOpenTraceFile
        QuickActionType.ANALYZE_HPROF -> onAnalyzeHprof
        QuickActionType.RUN_STARTUP_TEST -> onOpenStartupProfiler
        // Every module-open action just opens its destination.
        else -> { -> destination?.let(openDestination) }
    }

/**
 * Persists the home layout — the ordered module list and the ordered quick-action set — as two
 * comma-separated ID strings in [java.util.prefs.Preferences], mirroring the
 * [ApplicationUiSettingsStore] pattern. Unknown IDs are dropped on load for forward
 * compatibility; missing defaults are appended so a fresh install matches today's layout.
 */
internal class HomeLayoutPreferencesStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun loadModuleOrder(): List<AppDestination> {
        val saved = readValue(MODULE_ORDER_KEY)?.split(',')?.mapNotNull { id ->
            AppDestination.entries.firstOrNull { it.name == id.trim() }
        } ?: emptyList()
        return reconcileModules(saved)
    }

    fun loadQuickActions(): List<QuickActionType> {
        val saved = readValue(QUICK_ACTIONS_KEY)?.split(',')?.mapNotNull { id ->
            QuickActionType.parse(id.trim())
        } ?: emptyList()
        return reconcileQuickActions(saved)
    }

    fun save(moduleOrder: List<AppDestination>, quickActions: List<QuickActionType>): Boolean =
        runCatching {
            writeValue(MODULE_ORDER_KEY, moduleOrder.joinToString(",") { it.name })
            writeValue(QUICK_ACTIONS_KEY, quickActions.joinToString(",") { it.id })
            flush()
        }.isSuccess

    private fun reconcileModules(saved: List<AppDestination>): List<AppDestination> {
        val known = saved.filter { it in REORDERABLE_MODULE_DESTINATIONS }
        val missing = DEFAULT_MODULE_ORDER.filter { it !in known }
        return known + missing
    }

    private fun reconcileQuickActions(saved: List<QuickActionType>): List<QuickActionType> {
        // De-dup while preserving order, then append any default actions the user removed (i.e. not
        // present) so a fresh install shows the four defaults. Removed-but-catalog actions stay
        // removed because the user explicitly deleted them — we only restore defaults when the
        // saved list is empty (first launch / wiped prefs).
        return if (saved.isEmpty()) {
            QuickActionType.DEFAULTS
        } else {
            saved.distinct()
        }
    }

    companion object {
        private const val MODULE_ORDER_KEY = "application.home.moduleOrder"
        private const val QUICK_ACTIONS_KEY = "application.home.quickActions"

        fun desktop(): HomeLayoutPreferencesStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(HomeLayoutPreferencesStore::class.java)
                }.getOrNull()
            return HomeLayoutPreferencesStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    checkNotNull(preferences) { "Home layout preferences are unavailable" }
                    preferences.put(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}

/**
 * The single source of truth for the home layout. Both [AppHomePage] and the navigation rail read
 * [moduleOrder]; [quickActions] backs the quick-action row. Mutations update the
 * [SnapshotStateList]s in place (driving recomposition and live drag animation) and persist.
 */
internal class HomeLayoutController(
    private val store: HomeLayoutPreferencesStore,
) {
    val moduleOrder: SnapshotStateList<AppDestination> = store.loadModuleOrder().toMutableStateList()
    val quickActions: SnapshotStateList<QuickActionType> = store.loadQuickActions().toMutableStateList()

    fun moveModule(from: Int, to: Int) {
        if (from == to || from !in moduleOrder.indices || to !in moduleOrder.indices) return
        moduleOrder.add(to, moduleOrder.removeAt(from))
        persist()
    }

    fun moveQuickAction(from: Int, to: Int) {
        if (from == to || from !in quickActions.indices || to !in quickActions.indices) return
        quickActions.add(to, quickActions.removeAt(from))
        persist()
    }

    fun addQuickAction(type: QuickActionType) {
        if (type in quickActions) return
        quickActions.add(type)
        persist()
    }

    fun removeQuickAction(type: QuickActionType) {
        if (quickActions.remove(type)) persist()
    }

    private fun persist() {
        store.save(moduleOrder.toList(), quickActions.toList())
    }

    companion object {
        fun desktop(): HomeLayoutController = HomeLayoutController(HomeLayoutPreferencesStore.desktop())
    }
}

/**
 * Computes the insertion index for a pointer position over a lazy grid. Returns the index at
 * which the dragged item should be placed so the grid reorders live as the pointer moves.
 *
 * Strategy: find the item whose bounds contain the pointer; the dragged item goes to that item's
 * index. If the pointer is between items (in a gap), pick the item whose start edge is nearest
 * forward, else the last item. Pure and unit-testable.
 */
internal fun dropIndexForGrid(
    itemSlots: List<GridSlot>,
    pointerOffset: Int,
    columnCount: Int,
): Int {
    if (itemSlots.isEmpty()) return 0
    // Containment first.
    itemSlots.firstOrNull { pointerOffset in it.start..it.end }?.let { return it.index }
    // Pointer before the first item.
    if (pointerOffset < itemSlots.first().start) return 0
    // Pointer after the last item.
    if (pointerOffset > itemSlots.last().end) return itemSlots.last().index + 1
    // In a gap: nearest forward start edge.
    return itemSlots.firstOrNull { it.start >= pointerOffset }?.index ?: (itemSlots.last().index + 1)
}

/** One slot in the primary axis (the row's x-axis for the quick-action row). */
internal data class GridSlot(val index: Int, val start: Int, val end: Int)

/** Grid slots along the x-axis for a single visible row — used by the grid's drop-index math. */
internal fun gridRowSlots(
    itemRects: List<IntRect>,
    columnCount: Int,
): List<GridSlot> {
    if (itemRects.isEmpty()) return emptyList()
    // Use the first row (the first `columnCount` items) for horizontal targeting; the vertical
    // row is resolved separately by the caller via y-bucketing.
    return itemRects.take(columnCount).mapIndexed { i, rect -> GridSlot(i, rect.left, rect.right) }
}

/** Minimal rect used by the drop-index math — kept allocation-light for unit tests. */
internal data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Resolves which row a pointer y falls into for a multi-row grid. */
internal fun rowForY(itemRects: List<IntRect>, columnCount: Int, pointerY: Int): Int {
    if (itemRects.isEmpty()) return 0
    val rowTops = itemRects.map { it.top }.distinct().sorted()
    // The row containing the pointer is the last row whose top edge is at or above the pointer.
    val row = rowTops.indexOfLast { it <= pointerY }
    val resolved = if (row == -1) 0 else row
    val maxRow = (itemRects.size - 1) / columnCount
    return resolved.coerceIn(0, maxRow)
}

/** Full grid drop index combining row and column targeting. */
internal fun dropIndexForGrid(
    itemRects: List<IntRect>,
    pointerX: Int,
    pointerY: Int,
    columnCount: Int,
): Int {
    if (itemRects.isEmpty()) return 0
    val row = rowForY(itemRects, columnCount, pointerY)
    val rowStart = row * columnCount
    val rowRects = itemRects.drop(rowStart).take(columnCount)
    val slots = rowRects.mapIndexed { i, rect -> GridSlot(rowStart + i, rect.left, rect.right) }
    return dropIndexForGrid(slots, pointerX, columnCount)
}
