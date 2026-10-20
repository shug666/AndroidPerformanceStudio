## Context

The home screen (`AppHomePage.kt`) renders two hardcoded lists: 11 analysis-module cards and 4 quick-action cards. The navigation rail (`CommercialAppShell.kt:125-144`) hardcodes the same 11-destination order. Both lists live in source with no state, no persistence, and no user control. Compose 1.12.0 is in use; `LazyVerticalGrid` is already used in the memory profiler, so the lazy-grid + `Modifier.animateItem()` toolchain is available. Settings are persisted via `java.util.prefs.Preferences` through `ApplicationUiSettingsStore` and sibling stores (key-value, string-typed).

Two earlier hardcoded lists already agree on order (the `feat/reverse-net` work deliberately appended ReverseNet to both), so unifying them behind one persisted list formalizes an existing de-facto invariant rather than introducing a new one.

## Goals / Non-Goals

**Goals:**
- A single shared, persisted ordered module list drives both the home grid and the navigation rail.
- Live (during-drag) reordering of module cards and quick-action cards, phone-style.
- A customizable quick-action set drawn from a finite built-in catalog, with add/remove and reorder.
- Persistence that survives restarts and degrades gracefully across version changes.
- Zero behavior change for existing users on first run (defaults = today's order/set).

**Non-Goals:**
- User-defined arbitrary quick actions (custom command/URL/label/icon). Only the built-in catalog.
- Reordering or customizing anything other than the module grid, the quick-action set, and (as a consequence) the rail. Metrics, recent-sessions, device panel, and the settings page are untouched.
- Persisting quick-action or module *visibility toggles* beyond add/remove semantics (removing a quick action = not in the persisted list; there is no separate hidden flag).
- Dragging across the rail. Reordering is done on the home grid; the rail is a read-only reflection.

## Decisions

### Decision 1: Single source of truth — one persisted module order shared by home grid and rail
**Choice:** A `HomeLayoutController` holds `moduleOrder: SnapshotStateList<AppDestination>` and `quickActions: SnapshotStateList<QuickActionType>`, loaded from / saved to the prefs store. `CommercialAppShell` reads `moduleOrder` (with `HOME` prepended) for the rail; `AppHomePage` reads it for the grid.

**Why over alternatives:** Keeping two synced hardcoded lists (status quo) is what made the reverse-net append a two-file change; a shared list makes future additions a one-line catalog change. Putting it in a controller rather than passing two lists down keeps the home composable's signature stable.

**Alternatives considered:**
- Two separate persisted lists (one for grid, one for rail) — rejected: doubles the persistence surface and lets them drift, reintroducing the sync problem.
- Persist only the grid, derive the rail as "same order" at read time — equivalent in effect but hides the relationship; an explicit shared controller is clearer and testable.

### Decision 2: Migrate both grids to lazy layouts + `animateItem()` for live reordering
**Choice:** `HomeModuleGrid` moves from manual `entries.chunked(columnCount)` `Row`/`Column` to `LazyVerticalGrid(GridCells.Fixed(columnCount))` with `Modifier.animateItem()` on each item. `HomeQuickActionGrid` moves to a `LazyRow` with `animateItem()`.

**Why:** Phone-style live reorder means mutating the underlying list on every drag delta and having items animate to their new slots. `Modifier.animateItem()` does exactly this, but it only works inside Lazy layouts. Replicating that animation by hand over a chunked `Row`/`Column` grid (tracking offsets, animating each cell) is substantial, fragile code. `LazyVerticalGrid` is already used in the repo.

**Why `Fixed` not `Adaptive`:** The existing responsive logic is breakpoint-based (1/2/3 columns for modules, 1/2/4 for quick actions) via `homeGridColumnCount` / `homeQuickActionColumnCount`. `GridCells.Fixed(n)` with `fillMaxWidth` items preserves that exact behavior; `Adaptive(minSize)` would change the thresholds. We keep the existing helper functions to compute `n` from `BoxWithConstraints` width.

**Alternatives considered:**
- A reorderable library (`sh.calvin.reorderable`, etc.) — rejected: most target 1-D `LazyRow`/`LazyColumn` and do not cleanly handle a responsive `LazyVerticalGrid` cross-row drop; adds a dependency for logic we still have to adapt.
- Keep manual chunking, animate by hand — rejected: high implementation risk, no reuse.

### Decision 3: Hand-rolled drag via `detectDragGesturesAfterLongPress`, drop index from `LazyGridItemInfo`
**Choice:** Each draggable item wraps its content in a `Modifier.pointerInput { detectDragGesturesAfterLongPress(...) }`. A drag-state holder tracks `(draggedKey, dragOffset)`. On each drag delta, the dragged item is rendered with `Modifier.offset { dragOffset }` (no `animateItem` on it while dragged), and the pointer position is mapped to a target index using the `LazyGridGridLayoutInfo`/`LazyGridItemInfo` rects; if the target index differs from the dragged item's current index, the underlying `SnapshotStateList` is reordered, and the non-dragged items animate via `animateItem()`. Column count is captured at drag start and held constant.

**Why:** Long-press-then-drag leaves short taps to the existing `clickable` (open module) and consumes the long-press as the drag start. Computing the drop index from item rects (not "which neighbor am I closest to") is the robust approach for multi-column grids and avoids the classic off-by-one on row wraps.

**Why the dragged item is excluded from `animateItem` while held:** otherwise it fights the manual offset that pins it to the pointer. On drop, clear the offset and let `animateItem` settle it.

**Alternatives considered:**
- "Neighbor heuristic" drop index (swap with nearest visible neighbor) — rejected: wrong on row boundaries and at grid edges.
- Use a gesture library for the whole thing — rejected for the same reason as Decision 2.

### Decision 4: Quick-action catalog as a finite enum
**Choice:** `enum class QuickActionType(val id: String, ...)` with entries for the four current actions plus one "open module" action per analysis module (label/description/icon resolved from the destination). The visible set is a persisted ordered list of `QuickActionType` ids; the catalog is the enum's `entries`.

**Why over arbitrary custom actions:** Every existing quick action already maps to a destination or callback; there is no general "run a command" path, and inventing one (shell/URL execution) is a much larger, riskier surface (permissions, quoting, security) that this change does not need. The rich catalog (4 + per-module open actions) covers ~all practical launcher needs at near-zero cost.

**Alternatives considered:**
- Minimal catalog (only the current four; customize = show/hide/reorder those four) — rejected as too limiting; the per-module actions are free.
- Arbitrary custom actions — rejected (Non-Goal): out of scope and risky.

### Decision 5: "＋ Edit" as a non-draggable trailing slot in the `LazyRow`
**Choice:** The quick-action `LazyRow` emits the visible actions followed by one extra fixed item: the Edit card. The Edit item is not part of the persisted list and is skipped by the drag handler (its key is not in the reorderable set). Clicking it opens the management dialog.

**Why:** This satisfies the requested UX ("after the last card") and keeps the entry co-located with what it edits. Putting it outside the row (e.g., a header pencil icon) was an option but loses the spatial cue.

**Alternatives considered:**
- Edit affordance in the `HomeSection` header — rejected per the explicit UX request for a trailing card slot.
- Edit as a draggable card that is just pinned last — rejected: extra special-casing; simpler to keep it out of the reorderable set entirely.

### Decision 6: Persistence as comma-separated IDs in the existing prefs store
**Choice:** Add a `HomeLayoutPreferencesStore` (sibling to `ApplicationUiSettingsStore`) with two string keys: `application.home.moduleOrder` (comma-separated `AppDestination.name`) and `application.home.quickActions` (comma-separated `QuickActionType.id`). Load splits on `,`, filters to known IDs (preserving relative order), pads missing defaults. Save joins with `,`.

**Why:** Matches the established pattern in the file; `java.util.prefs.Preferences` is already the store; string values with stable enum IDs give forward/backward compatibility for free (unknown future IDs dropped on load). No schema, no JSON, no migration.

**Default reconciliation:** On load, any default-list module/action missing from the persisted list is appended in default order; any persisted ID unknown today is dropped. This keeps a fresh install identical to today and a returning user's customizations intact.

**Alternatives considered:**
- JSON encoding — rejected: overkill for two ordered lists of IDs; no nested structure to warrant it.
- A single combined key — rejected: two independent concerns, two keys is clearer and avoids re-serializing both when one changes.

## Risks / Trade-offs

- **[Drag hit-testing across a responsive grid]** Drop-index computation from `LazyGridItemInfo` rects must correctly handle the last row (which may be partial), gaps from `Spacer` padding, and RTL. → Mitigation: compute target index by "smallest item whose bounds contain the pointer, else nearest by row-then-column"; unit-test the index math against synthetic layouts; freeze column count during drag so the grid does not reflow mid-gesture (Requirement: Column count stable during drag).
- **[animateItem + manual offset conflict on the dragged item]** If the dragged item keeps `animateItem` while we also apply `Modifier.offset`, it can stutter. → Mitigation: disable `animateItem` on the dragged item while `draggedKey == thisKey`, re-enable on drop. Verify settle animation on release.
- **[Long-press vs. tap ambiguity]** A long-press that releases without moving must not open the module. → Mitigation: only start the drag on `detectDragGesturesAfterLongPress`'s `onDragStart`; in `onDragEnd`, if no drag delta exceeded a small threshold, treat as a cancelled long-press and do nothing. Tap is handled by the existing `clickable`, which does not fire for a long-press consumed by the pointer input.
- **[Rail loses its own independent ordering]** Some users may want a different rail order than the home grid. → Trade-off accepted: the proposal explicitly unifies them. If that later turns out wrong, the controller can be split without a persistence-format change (the rail could reorder independently while still defaulting to the module order).
- **[LazyVerticalGrid replaces a working chunked layout]** Visual regressions in card sizing/spacing are possible. → Mitigation: keep the same `Modifier.weight`-equivalent via `fillMaxWidth` + the existing `StudioTokens.compactContentPadding` spacing using `LazyVerticalGrid`'s `horizontalArrangement`/`verticalArrangement` `spacedBy`; visually diff before/after.
- **[Prefs store availability]** `Preferences.userNodeForPackage` can be unavailable in some sandboxed/headless contexts; the existing stores already handle this by returning null reads and failing writes silently. → Mitigation: follow the same `runCatching` pattern; a missing store degrades to in-memory-only (customizations work for the session, not persisted).

## Migration Plan

No data migration. Defaults equal today's hardcoded order/set, so existing users see no change on first launch and only gain customization ability. If a later change removes a module/destination, old persisted layouts drop that ID on load (forward compatibility requirement) — no code migration needed.

Rollback: deleting the two prefs keys reverts to defaults; reverting the code revert restores the hardcoded lists. No persistent state is left in a bad state.

## Open Questions

- Should the management dialog also allow reordering quick actions (drag inside the dialog), or is in-row drag the only reorder path? **Assumed:** in-row drag is the reorder path; the dialog only does add/remove. Confirm at implementation.
- Should reordering the rail via some future affordance feed back into the home grid? Out of scope here; rail is read-only.
