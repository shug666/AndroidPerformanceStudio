## 1. Quick-action catalog & persistence

- [x] 1.1 Define `QuickActionType` enum (id, label/description/icon/onClick resolver) covering the four current actions plus one "open module" action per analysis module; default visible set = the four current actions.
- [x] 1.2 Add `HomeLayoutPreferencesStore` (sibling to `ApplicationUiSettingsStore`) backed by `java.util.prefs.Preferences`, keys `application.home.moduleOrder` and `application.home.quickActions`; load splits/filters known IDs, pads missing defaults; save joins with `,`; follow the existing `runCatching` null-safe pattern.
- [x] 1.3 Unit-test the store: round-trip order, drop-unknown-ID forward compat, default reconciliation when saved list is empty/partial, null-store degradation.

## 2. Shared layout controller

- [x] 2.1 Add `HomeLayoutController` exposing `moduleOrder: SnapshotStateList<AppDestination>` and `quickActions: SnapshotStateList<QuickActionType>`, loaded from the store on creation, saving on every mutation.
- [x] 2.2 Implement `moveModule(from, to)`, `addQuickAction(type)`, `removeQuickAction(type)`, `moveQuickAction(from, to)` that mutate the lists and persist.
- [x] 2.3 Unit-test the controller: mutations update lists and persist; default module order equals the current hardcoded order; default quick-action set equals the current four.

## 3. Module grid — lazy layout + live drag

- [x] 3.1 Replace `HomeModuleGrid`'s manual chunked `Row`/`Column` with `LazyVerticalGrid(GridCells.Fixed(columnCount))`, `fillMaxWidth` items, `spacedBy(StudioTokens.compactContentPadding)`; keep the existing `homeGridColumnCount` breakpoint helper. Visually diff before/after for card size/spacing parity.
- [x] 3.2 Add `Modifier.animateItem()` to each module item so non-dragged cards animate on list mutation.
- [x] 3.3 Implement long-press-drag via `detectDragGesturesAfterLongPress`: track `draggedKey` + `dragOffset`, render the dragged item with `Modifier.offset { dragOffset }` and `animateItem` disabled while it is the dragged key.
- [x] 3.4 Compute drop target index from `LazyGridLayoutInfo`/`LazyGridItemInfo` rects (pointer containment, row-then-column nearest fallback); on change call `controller.moveModule`. Freeze column count at drag start and hold until drop.
- [x] 3.5 On drop: clear offset, re-enable `animateItem` on the dragged item so it settles; if no drag delta exceeded a small threshold, cancel (no module open, no reorder).
- [x] 3.6 Verify short tap still opens the module (existing `clickable` path unchanged) and long-press-without-move does not open it.

## 4. Quick-action row — lazy layout, drag, Edit entry

- [x] 4.1 Replace `HomeQuickActionGrid`'s chunked rows with a `LazyRow` using `homeQuickActionColumnCount`-driven item widths (keep the responsive 1/2/4 sizing); add `Modifier.animateItem()`.
- [x] 4.2 Emit a trailing "＋ Edit" item after the visible actions, outside the reorderable set, opening the management dialog on click; not draggable.
- [x] 4.3 Implement long-press-drag reorder on the action items mirroring the module grid (dragged item offset + animateItem disabled, live reorder via `controller.moveQuickAction`, drop-index from `LazyListItemInfo`).
- [x] 4.4 Verify the Edit entry stays last through reorders and cannot be dragged or removed.

## 5. Quick-action management dialog

- [x] 5.1 Build the management dialog listing the `QuickActionType` catalog; show which are currently visible; allow add (to end, then user drags) and remove.
- [x] 5.2 Wire add/remove to `controller.addQuickAction`/`removeQuickAction`; close on confirm.
- [x] 5.3 Add localized strings (en + zh) for the dialog title, add/remove labels, and the "＋ Edit" card label.

## 6. Navigation rail unification

- [x] 6.1 In `CommercialAppShell.kt`, replace the hardcoded `navigationItems` list (lines ~125-144) with `[AppDestination.HOME] + controller.moduleOrder` mapped to `StudioNavigationItem`.
- [x] 6.2 Confirm the rail pins Home first and reflects a home-screen module reorder live (same composition, same controller).
- [x] 6.3 Verify command palette (`commandItems`) still includes all destinations regardless of order (it derives from navigationItems + Method Recording; keep Method Recording appended).

## 7. Wire-up & integration

- [x] 7.1 Instantiate `HomeLayoutController` at the app shell level and pass it to both `AppHomePage` and `StudioNavigationRail`; replace the hardcoded `entries`/`quickActions` construction in `AppHomePage` with reads from the controller.
- [x] 7.2 Ensure the controller is created once and shared (not recreated per recomposition); persist mutations debounced or on change.
- [ ] 7.3 Run the app and confirm: first launch == current layout; reorder modules → rail reorders + persists across restart; add/remove/reorder quick actions → persists across restart; unknown-ID forward compat by hand-editing the prefs key.

## 8. Tests & verification

- [x] 8.1 Unit tests for drop-index math (module grid and quick-action row) over synthetic layouts incl. partial last row and RTL.
- [x] 8.2 Compose UI test for module drag-reorder (live reorder assertion) and quick-action add/remove via dialog where feasible.
- [x] 8.3 Regression check: metrics row, recent sessions, device panel, settings page render unchanged.
