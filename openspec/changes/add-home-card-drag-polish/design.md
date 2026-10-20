## Context

`ReorderableGrid` (`AppHomePage.kt:409-533`) already implements functional phone-style drag-to-reorder: `detectDragGesturesAfterLongPress` + `dropIndexForGrid` against `LazyGridItemInfo` rects, with the dragged item pinned to the pointer via `Modifier.offset` + `zIndex(1f)` and the others animating via `Modifier.animateItem()`. Column count is frozen for the drag (`frozenColumns`).

The visual gap: there is no lift feedback (the card snaps to the pointer with no anticipation, no shadow grow, no scale change), and the dragged card is visually indistinguishable from its neighbors except for z-order. There is no placeholder anchoring where the card came from. The target is the phone/tablet home-screen idiom.

Constraints: Compose 1.12.0 is in use. `dropIndexForGrid` and the `frozenColumns` / `rememberUpdatedState` machinery must stay intact — only the dragged item's rendering and the gesture start/end hooks change. The dragged slot must remain a real `LazyVerticalGrid` item (so `visibleItemsInfo` keeps reporting its rect for drop math) even while its content is swapped for a placeholder.

## Goals / Non-Goals

**Goals:**
- A visible lift anticipation (shrink-then-grow + shadow grow) on long-press, in parallel with the card beginning to follow the pointer — zero dead-time between press and drag.
- A floating, raised overlay card (scale 1.05, elevated shadow, full opacity) that tracks the pointer for the duration of the drag.
- A dashed in-place placeholder in the dragged card's slot that rides `animateItem()` and moves as the grid shuffles.
- A settle animation (scale/shadow back to rest) on drop as the floating card collapses into the placeholder.
- Preserve all existing functional behavior: live reorder, drop commits order, tap opens module, long-press-without-cross does not open, frozen columns during drag, persisted order.

**Non-Goals:**
- Touching the quick-action `LazyRow` drag (separate implementation, out of scope).
- Haptic feedback (desktop platform; no haptics available).
- Customizable animation timing/curves exposed to users.
- Changing reorder math, persistence, or the navigation rail.

## Decisions

### Decision 1: Drag-phase state machine driven by an `Animatable<Float>`
**Choice:** Introduce a sealed phase — `Idle`, `Lifting`, `Dragging`, `Dropping` — plus an `Animatable<Float>` (`liftProgress`, 0..1) whose value is mapped to scale and shadow. `onDragStart` sets `Lifting` and launches `liftProgress.animateTo(1f)`; once it reaches 1 (or the pointer has moved enough that following is active) the phase is `Dragging`. `onDragEnd/onDragCancel` sets `Dropping` and launches `animateTo(0f)`, clearing `draggedIndex`/`frozenColumns` on completion.

**Why over a plain `mutableStateOf` scale:** a single `Animatable` gives interruptible, spring-eased animation for both lift and settle from one source of truth, and lets scale and shadow grow stay perfectly in sync.

**Alternatives considered:**
- Two separate `Animatable`s (scale + shadow) — rejected: extra coroutines, drift risk.
- `animateFloatAsState` keyed on phase — rejected: harder to read the current value mid-animation for the floating overlay; `Animatable` is the idiomatic choice for gesture-driven continuous animation.

### Decision 2: Lift runs in parallel with pointer-follow (no dead-time)
**Choice:** The moment `onDragStart` fires, both the lift animation begins AND the floating overlay is positioned at the pointer. There is no "wait for lift to finish before following" phase.

**Why:** A sequential "play lift, then follow" design inserts ~150ms of the card being visually pinned while the finger moves — feels laggy on desktop where there is no haptic to sell the anticipation. Running them in parallel shows the shrink-grow within the first ~200ms while the card is already tracking, matching iOS/Android home-screen feel.

**Alternatives considered:**
- Strict sequence via custom `awaitEachGesture` that gates drag on lift completion — rejected: dead-time, heavier rewrite of the gesture pipeline.

### Decision 3: Floating card as an overlay in the outer `Box`, not a grid item
**Choice:** The dragged card is rendered as a sibling of the `LazyVerticalGrid` inside the outer `Box(modifier.fillMaxWidth().height(gridHeightDp))`, drawn above the grid (and above the transparent pointer overlay). It uses the same `FeatureEntryCard` content (`entries[draggedIndex]`) wrapped in `graphicsLayer { scaleX = scaleY = scale; shadowElevation = shadow }` and positioned with `Modifier.offset` from `dragPosition − grabLocal` expressed in the outer Box's coordinate space.

**Why:** Keeping the lifted card inside the `LazyVerticalGrid` item (the status quo) forces it to share the slot with the placeholder and fights `animateItem` on the slot itself. Lifting it out decouples "what the finger holds" from "what occupies the slot", which is exactly the phone idiom.

**Coordinate-space note:** `grabLocal` is currently computed as `offset − slot.offset` (slot-local). The floating overlay lives in the outer `Box`, so its offset must be `dragPosition − grabLocal` directly, where `dragPosition` is already in the outer-Box coordinate space (the transparent overlay fills the outer Box, so `change.position` is in that same space). No additional transform is needed; this is the most error-prone line and is called out in Risks.

**Alternatives considered:**
- Render the floating card inside the dragged grid item alongside the placeholder — rejected: clipping by the slot, z-fighting, and `animateItem` interference.

### Decision 4: Dashed placeholder replaces the dragged slot's content, slot stays a grid item
**Choice:** In `itemsIndexed`, when `draggedIndex == index`, the item renders a dashed-outline `Box` of the same size and faint fill (`colors.accent.copy(alpha = 0.06f)`, dashed border) instead of `FeatureEntryCard`. The item keeps `Modifier.animateItem()` (NOT the old `offset{…}`) so the placeholder animates to its new slot as the grid shuffles.

**Why:** The slot must remain in `visibleItemsInfo` for `dropIndexForGrid` to keep working — and the placeholder IS the visual anchor the user follows, so it should animate with the shuffle. Removing the old `offset{…}.zIndex(1f)` branch (the card-following-in-slot approach) is the core structural change.

**Alternatives considered:**
- Hide the dragged slot entirely (invisible item) — rejected: loses the "home slot" anchor the user requested; grid would close the gap with no marker.
- Placeholder outside the grid, manually positioned — rejected: re-implements grid layout by hand, fragile.

### Decision 5: Animation timing/curves
**Choice:** Lift: `spring(dampingRatio = 0.6f, stiffness = 400f)` over ~200ms, mapping `liftProgress 0→1` to scale `0.92→1.05` and shadow `1dp→12dp`. (The 0.92 trough lands near ~80ms, visible as the shrink.) Drop: `spring(dampingRatio = 0.8f, stiffness = 350f)` over ~150ms, mapping back to scale `1.0`, shadow `1dp`. Floating card during `Dragging`: held at scale 1.05, shadow 12dp, opacity 1.0 (no dim — dimming reads as "weak" rather than "lifted").

**Why:** Spring, not tween, for the organic phone feel; the slightly underdamped lift sells the "pop up". Constants are starting points, tunable in review.

**Alternatives considered:**
- Tween with fixed durations — rejected: spring composes better with interruption (e.g., user re-grabs immediately).

## Risks / Trade-offs

- **[Floating-overlay coordinate drift]** The overlay's `offset` uses `dragPosition − grabLocal` in the outer-Box space; if the outer `Box` is not exactly aligned with the grid's coordinate origin (e.g., due to `verticalScroll` padding), the floating card could be offset from the finger. → Mitigation: the outer `Box` is the direct parent of both the grid and the pointer overlay and fills them exactly; assert in tests that the grid and overlay share the same top-left. Reuse the existing `dragPosition` plumbing unchanged.
- **[Placeholder visible during Dropping creates a flash]** While `Dropping` plays, the floating card settles back while the placeholder still occupies the slot — they overlap for ~150ms. → Mitigation: on `Dropping` completion (single frame), clear `draggedIndex`, which swaps the placeholder back to the real card and removes the overlay in the same recomposition. No explicit fade needed; `animateItem` is not involved for the dragged slot's return (it was never displaced).
- **[Spring values feel wrong on some displays]** Animation feel is subjective. → Mitigation: constants are localized and easy to tune; no user-facing config.
- **[Interrupting a drop with a new long-press]** If the user long-presses again while `Dropping` is mid-animation, `liftProgress` is already nonzero. → Mitigation: `onDragStart` always calls `animateTo(1f)` from current value (interruptible `Animatable`), so a re-grab simply continues toward lifted — no glitch.

## Migration Plan

No data/persistence migration. Pure UI change. Rollback = revert the `ReorderableGrid` diff; functional reorder behavior is unchanged by the rollback. No feature flag needed given the localized scope, but the change can be gated behind an existing settings toggle if review prefers a staged rollout.

## Open Questions

- Should the quick-action `LazyRow` drag adopt the same lift idiom in a follow-up change? (Out of scope here, flagged for later.)
- Is opacity 1.0 on the floating card acceptable, or should it dip to ~0.95 for extra depth? (Defaulting to 1.0 per Decision 5; tunable in review.)
