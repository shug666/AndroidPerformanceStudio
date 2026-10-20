## Why

The home screen's analysis-module drag-to-reorder (added by `add-home-card-customization`) is functionally correct but visually flat: long-press snaps the card straight to the pointer with no lift feedback, and the dragged card stays visually identical to its neighbors. The result feels unresponsive and un-phone-like — there is no "pick up" cue and no persistent visual anchor for where the card came from. Users expect the phone/tablet home-screen idiom: a press-and-lift anticipation (shrink then grow), a raised shadowed floating card that follows the finger, and a dashed placeholder left in the card's original slot.

## What Changes

- **Lift anticipation on long-press.** When a long-press is recognized, the dragged card animates a brief shrink-then-grow (≈0.92 → 1.05 scale) over ~200ms alongside a shadow grow (1dp → 12dp), in parallel with the card beginning to follow the pointer — no dead-time delay between press and drag.
- **Floating dragged card.** The dragged card is lifted out of the grid slot and rendered as an overlay (scaled 1.05, elevated shadow, full opacity) that tracks the pointer. It is no longer the same composable as the slot it came from.
- **In-place dashed placeholder.** The dragged card's original grid slot shows a dashed-outline, faintly-filled placeholder of the same size. The placeholder rides `Modifier.animateItem()` so it moves in real time as the other cards shuffle aside, giving the user a persistent "home slot" anchor.
- **Settle animation on drop.** Releasing the card plays a settle (scale 1.05 → 1.0, shadow 12dp → 1dp, ~150ms) as the floating card collapses back into the placeholder, which then becomes the real card again.
- **Scope limited to the analysis-module grid.** The quick-action `LazyRow` drag (a separate implementation) is untouched by this change.

## Capabilities

### New Capabilities
<!-- No new capabilities. The drag-polish refines an existing capability's existing requirement. -->

### Modified Capabilities
- `home-layout-customization`: The "Analysis modules support live drag-to-reorder" requirement gains explicit visual-behavior requirements for the lift animation, the floating-card overlay, the in-place placeholder, and the settle animation. The functional behavior (live reorder, drop commits, tap still opens) is unchanged.

## Impact

- **Code:**
  - `desktop-viewer/desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt` — `ReorderableGrid` gains a drag-phase state machine (`Idle → Lifting → Dragging → Dropping → Idle`), an `Animatable<Float>` driving `graphicsLayer` (scale + shadowElevation) for the floating card, a new dashed-placeholder composable rendered in the dragged slot, and a top-level overlay rendering the lifted card outside the `LazyVerticalGrid`. The existing `detectDragGesturesAfterLongPress` / `dropIndexForGrid` / `frozenColumns` logic is preserved; only the dragged item's rendering and the gesture start/end handlers change.
  - No changes to `HomeLayout.kt` (reorder math), `HomeLayoutController`, persistence, or the navigation rail.
- **Dependencies:** None. `Animatable`, `graphicsLayer`, and `Modifier.offset` are already in use or available in Compose 1.12.0.
- **Tests:** `HomeCustomizationTest.kt` / `AppHomePageLayoutTest.kt` extended to assert the placeholder composable is present while `draggedIndex != null` and absent otherwise, and that the floating overlay appears only during drag. Animation curves are visual and not unit-asserted.
- **Risk:** Coordinate-space bookkeeping for the floating overlay (it lives in the outer `Box` coordinate space while `grabLocal` is relative to the dragged slot's `visibleItemsInfo`) is the most error-prone area; the slot must remain present in `visibleItemsInfo` while its content is swapped for the placeholder so the drop-index math stays valid.
