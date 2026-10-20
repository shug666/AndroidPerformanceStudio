## Context

`StudioNavigationRail` (`CommercialAppShell.kt`) renders the rail with a width that switches on a `collapsed: Boolean`. Today `collapsed` comes from `resolvedNavigationRailCollapsed(maxWidth, navigationRailCollapsed)`, which threads a `Boolean?` user value and falls back to a window-width breakpoint (`< 1280dp`). In production the persisted value is always a non-null `Boolean` (default `true`), so the fallback (`navigationRailCollapsedFor`) is dead — it only executes when called with `null` directly (i.e., never outside tests). There is no in-rail control; the only way to flip the state is the Settings page "Show sidebar titles" toggle, which writes `navigationRailCollapsed = !showSidebarTitles`.

## Goals / Non-Goals

**Goals:**
- One-click collapse/expand from the rail itself.
- Purely user-controlled state — no automatic width-based collapsing.
- Smooth width transition so frequent toggling isn't jarring.
- Keep the Settings toggle working and in sync.

**Non-Goals:**
- Per-destination collapse memory (every destination shares one rail state).
- A "restore automatic" option — once the user picks, it's fixed; there's no auto mode to restore.
- Changing the persisted field type (stays `Boolean`) or key.
- Collapsing/animating anything other than the rail width (entries already handle icon-only vs. icon+label).

## Decisions

### Decision 1: Toggle lives at the rail bottom, above the Settings entry
**Choice:** A new row rendered by `StudioNavigationRail`, placed between the `Spacer` and the Settings entry — matching the existing code comment that says *"the collapsed rail centres the icon so it lines up with the collapse control below"* (i.e., a bottom collapse control was anticipated).

**Why over alternatives:**
- Top of rail: dominates visually and pushes the first destination down; also competes with Home as the rail's anchor.
- Floating boundary handle (on the rail/content divider): cleaner but requires an overlay outside the rail's `Column`, complicating layout for a small payoff.

**Rendering:** Reuse the existing `NavigationRailEntry` shape (icon + optional label + collapsed hover tooltip) so the toggle aligns with every other row. The toggle is not a destination, so it carries its own `onClick` and never reports `selected`.

**Alternatives considered:** a bespoke `RailToggleButton` composable — rejected; `NavigationRailEntry` already handles the collapsed-center / expanded-left align + tooltip behavior we want, so reuse avoids drift.

### Decision 2: Icons are `ic_toggle_left` (expanded, click to collapse) / `ic_toggle_right` (collapsed, click to expand)
**Choice:** Direction follows the gesture: `«` means "this will fold left/collapse", `»` means "this will unfold right/expand". Both vector assets already exist in ui-components and are used by layout-inspector.

**Why:** consistent with the app's existing toggle-icon vocabulary; no new asset work.

### Decision 3: Width animates via `animateDpAsState`
**Choice:** Replace `val width = if (collapsed) W1 else W2` with `val width by animateDpAsState(if (collapsed) W1 else W2)`. The rail's `Modifier.width(width)` then drives a smooth 54↔216 transition. Entries inside reflow naturally (text appears/disappears on the `!collapsed` branch of `RailEntryButton`).

**Why over `AnimatedContent` / `Crossfade`:** there's no content swap — the same composable tree is shown, only the width and the label visibility change. `animateDpAsState` is one line and animates the only thing that actually changes.

**Why not snap:** the user toggles more often once it's one click, so the transition is worth the trivial cost.

### Decision 4: Collapse state becomes a plain read of the persisted `Boolean`; auto-collapse helpers deleted
**Choice:** In `CommercialAppShell`, replace `val collapsed = resolvedNavigationRailCollapsed(maxWidth.value.toInt(), navigationRailCollapsed)` with `val collapsed = navigationRailCollapsed`. Delete `NAVIGATION_COLLAPSE_BREAKPOINT_DP`, `navigationRailCollapsedFor`, `resolvedNavigationRailCollapsed`.

**Why:** the fallback is dead code (persisted value is non-null). Keeping a `Boolean?` + fallback that never fires is misleading and the source of this change's confusion. Removing it makes the state model honest: one persisted `Boolean`, two entry points (rail toggle + Settings toggle), no width coupling.

**Migration:** no persisted-data migration — the field key and type are unchanged; existing users' saved `true`/`false` still applies. The `maxWidth` is still computed in `BoxWithConstraints` for other uses but no longer feeds the rail.

**Alternatives considered:**
- Keep `Boolean?` and make the toggle write `true`/`false` only — rejected: leaves the dead fallback and the misleading three-state model.
- Add a real "auto" mode the user can pick — rejected (Non-Goal: no automatic collapsing).

## Risks / Trade-offs

- **[CommercialAppShellTest rewrite]** The test file pins `navigationRailCollapsedFor` / `resolvedNavigationRailCollapsed` / the breakpoint, and asserts `resolvedNavigationRailCollapsed(...)` is called. All of that must be removed and replaced with an assertion that the rail reads the persisted `Boolean` directly and renders a toggle. → Mitigation: rewrite those tests to assert the new contract (no fallback, toggle present, width animated).
- **[Animation jank on resize during transition]** Animating width while the window also resizes could compound. → Mitigation: `animateDpAsState` uses a spring; a window resize just retargets. Acceptable; no special handling.
- **[Two entry points drift]** The rail toggle and the Settings toggle both write `navigationRailCollapsed`. → Not a risk: they both go through `updateApplicationSettings` → `settings.copy(navigationRailCollapsed = ...)`, so the single source of truth stays the persisted settings object; the rail re-reads it on recomposition.
- **[Collapsed tooltip reuses existing strings]** `expand_navigation` / `collapse_navigation` already exist (en + zh). No new strings needed — confirmed during exploration.

## Migration Plan

No data migration. Defaults unchanged (`navigationRailCollapsed = true`, i.e., collapsed on first launch — same as today). Rollback: revert the code; persisted `true`/`false` values remain valid.

## Open Questions

None remaining — scope, position, icons, animation, and auto-collapse removal are all decided.
