## 1. Drag-phase state and animation primitives

- [x] 1.1 Add a drag-phase enum (`Idle`, `Lifting`, `Dragging`, `Dropping`) and a `phase` state holder to `ReorderableGrid`.
- [x] 1.2 Add an `Animatable<Float>` (`liftProgress`, 0f..1f) remembered in `ReorderableGrid`, plus a coroutine scope (or reuse `rememberCoroutineScope`) to drive it.
- [x] 1.3 Add helper mapping functions `liftProgress` → `scale` (0.92 → 1.05) and `liftProgress` → `shadowElevation` (1dp → 12dp), and the drop mapping back to (1.0, 1dp).

## 2. Replace dragged-slot rendering with a placeholder

- [x] 2.1 In `itemsIndexed`, replace the `dragged` branch's `offset{…}.zIndex(1f)` with `Modifier.animateItem()` so the dragged slot animates with the grid shuffle.
- [x] 2.2 Add a `DraggedSlotPlaceholder` composable: a `Box` of the same size as the card, dashed outline border, faint `colors.accent.copy(alpha = 0.06f)` fill, rounded to `StudioTokens.cardRadius`.
- [x] 2.3 In `itemsIndexed`, when `draggedIndex == index`, render `DraggedSlotPlaceholder` instead of `FeatureEntryCard`; otherwise render `itemContent` as today.

## 3. Floating overlay card

- [x] 3.1 Inside the outer `Box` (sibling of the `LazyVerticalGrid` and above the transparent pointer overlay), add a conditional floating card rendered only when `draggedIndex != null`.
- [x] 3.2 Render the floating card with the same `FeatureEntryCard` content for `entries[draggedIndex]`, wrapped in `Modifier.graphicsLayer { scaleX = scaleY = scale; shadowElevation = shadowElevation }`.
- [x] 3.3 Position the floating card with `Modifier.offset { IntOffset((dragPosition.x - grabLocal.x).roundToInt(), (dragPosition.y - grabLocal.y).roundToInt()) }` and `zIndex(1f)`; verify the outer `Box` and pointer overlay share the same coordinate origin.
- [x] 3.4 Drive the floating card's `scale`/`shadowElevation` from the `liftProgress` mapping (Decision 1/5); during `Dragging` hold at 1.05 / 12dp, full opacity.

## 4. Gesture wiring

- [x] 4.1 In `onDragStart`, set `phase = Lifting`, set `draggedIndex`/`frozenColumns`/`grabLocal`/`dragPosition` as today, and launch `liftProgress.animateTo(1f)` with the lift spring.
- [x] 4.2 In `onDrag`, keep the existing `dropIndexForGrid` reorder logic unchanged; once `liftProgress` reaches 1f transition `phase` to `Dragging`.
- [x] 4.3 In `onDragEnd` and `onDragCancel`, set `phase = Dropping` and launch `liftProgress.animateTo(0f)` with the drop spring; on completion clear `draggedIndex`, `frozenColumns`, and reset `phase = Idle`.
- [x] 4.4 Ensure an interrupted drop (new long-press mid-`Dropping`) re-launches `animateTo(1f)` from the current value without glitch.

## 5. Tests

- [x] 5.1 Extend `AppHomePageLayoutTest.kt` / `HomeCustomizationTest.kt` to assert the dashed placeholder composable is present while a drag is active and absent when idle.
- [x] 5.2 Add a test asserting the floating overlay is rendered only during a drag and uses `entries[draggedIndex]` content.
- [x] 5.3 Add a test asserting `dropIndexForGrid` reorder behavior is unchanged (no regression from the rendering change).
- [x] 5.4 Verify tap-still-opens and long-press-without-cross-does-not-open scenarios still pass.

## 6. Verification

- [x] 6.1 Run `./gradlew :desktop-app:test` (or the module's test task) and confirm green.
- [ ] 6.2 Launch the app and manually verify lift anticipation, floating card tracking, dashed placeholder shuffle, and settle animation on a 2-column and 3-column width.
- [ ] 6.3 Confirm reordering persists across a drag and the navigation rail reflects the new order (unchanged behavior).
