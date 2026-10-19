## Why

The home screen's Quick Actions and Analysis Modules are hardcoded lists in source — users cannot add, remove, reorder, or pick which actions surface on their dashboard. For a multi-tool performance studio where different users reach for different probes (CPU vs. memory vs. battery), a fixed card set is friction. This change makes both sections user-customizable and persists the layout, so the home screen becomes a personal launchpad rather than a fixed menu.

## What Changes

- **Quick Actions become customizable.** The four hardcoded quick actions become a persisted, reorderable set drawn from a finite catalog of built-in actions (the current four plus an "open module" action per analysis module). Users add/remove actions from the catalog and reorder the row via drag.
- **Analysis Modules gain drag-to-reorder.** Long-pressing a module card initiates a phone-style live drag: as the dragged card passes over other cards they shift aside in real time (not only on drop), powered by `LazyVerticalGrid` + `Modifier.animateItem()`. The new order persists.
- **Navigation rail order follows the module order.** The rail's hardcoded destination list is replaced by the shared ordered module list, so reordering modules on the home screen reorders the rail. The rail keeps Home pinned at the top.
- **Single source of truth.** The home module grid and the navigation rail currently hardcode the same 11-destination order in two files. Both read from one shared, persisted ordered list (`application.home.moduleOrder`), with the current hardcoded order as the default.
- **An "＋ Edit" entry on the Quick Actions row** sits in the slot after the last action card. It opens the management dialog for adding/removing actions. It is always present at the end and is not itself draggable.
- **Persistence** extends the existing `java.util.prefs.Preferences`-backed settings store (`ApplicationUiSettingsStore` style) with two keys: `application.home.moduleOrder` and `application.home.quickActions`, stored as comma-separated stable IDs. Unknown IDs are dropped for forward compatibility. No migration needed — defaults equal today's hardcoded order.

## Capabilities

### New Capabilities
- `home-layout-customization`: User-customizable home screen — reorderable analysis-module grid (live drag-to-reorder, persisted) and a customizable quick-action set (add/remove from a built-in catalog, reorderable via drag, persisted). The navigation rail reflects the persisted module order.

### Modified Capabilities
<!-- No existing spec-level requirements change. The home screen and nav rail were not previously specified. -->

## Impact

- **Code:**
  - `desktop-viewer/desktop-app/.../desktop/AppHomePage.kt` — replace hardcoded `entries`/`quickActions` lists with a shared controller; migrate `HomeModuleGrid` from manual `Row`/`Column` chunking to `LazyVerticalGrid` with `animateItem()`; migrate `HomeQuickActionGrid` to `LazyRow` with `animateItem()` and an "＋ Edit" trailing slot.
  - `desktop-viewer/desktop-app/.../desktop/shell/CommercialAppShell.kt` — replace the hardcoded `navigationItems` list (lines ~125-144) with the shared module order, keeping `HOME` pinned first.
  - `desktop-viewer/desktop-app/.../desktop/ApplicationUiSettings.kt` (or a sibling store) — add persisted `moduleOrder`/`quickActions` keys following the existing store pattern.
  - New: a `HomeLayoutController`/viewmodel-style holder exposing the ordered lists as `MutableStateList` plus reorder/add/remove operations and persistence.
  - New: a `QuickActionType` enum catalog and a management dialog composable.
- **Dependencies:** None. `LazyVerticalGrid`, `LazyRow`, and `Modifier.animateItem()` are already available (Compose 1.12.0, `LazyVerticalGrid` already used in the memory profiler).
- **Tests:** New unit tests for the persistence store (parse/serialize/forward-compat ID drop) and the reorder index math; Compose UI coverage for the drag-reorder and the management dialog where feasible.
- **Risk:** Drag-hit-testing across a responsive multi-column grid (computing the drop index from pointer coordinates via `LazyGridItemInfo`) is the most error-prone area; column count is frozen for the duration of a drag to avoid mid-drag re-layout.
