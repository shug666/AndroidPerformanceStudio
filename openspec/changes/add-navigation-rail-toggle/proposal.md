## Why

The navigation rail can be collapsed/expanded, but the only control is a toggle buried in the Settings page ("Show sidebar titles"). There is no in-rail affordance, so users who never open Settings never discover the feature. Meanwhile the rail's collapse state is nominally a three-value system (`userCollapsed: Boolean?` with a window-width auto fallback), but the auto branch is dead code — the persisted setting is always a non-null `Boolean`, so `navigationRailCollapsedFor(width)` never runs in production. This change adds a one-click toggle in the rail and removes the dead auto-collapse, making collapse state purely user-controlled.

## What Changes

- **A collapse/expand toggle at the bottom of the navigation rail.** Expanded state shows a `«` icon (`ic_toggle_left`) + the "Collapse navigation" label; collapsed state shows a centered `»` icon (`ic_toggle_right`) with a hover tooltip "Expand navigation" (reusing existing strings). Clicking flips `navigationRailCollapsed`.
- **Smooth width transition.** The rail width animates between 54dp and 216dp via `animateDpAsState` instead of snapping.
- **Auto-collapse removed.** Delete `NAVIGATION_COLLAPSE_BREAKPOINT_DP`, `navigationRailCollapsedFor`, and `resolvedNavigationRailCollapsed`. The rail's collapsed state is now read directly from the persisted `navigationRailCollapsed` `Boolean` (no width fallback). **BREAKING** only in the sense that the dead fallback path is removed; observable behavior for existing users is unchanged because the fallback never ran.
- **The Settings toggle stays.** "Show sidebar titles" continues to flip the same field; it and the new in-rail button are two entry points into one state and stay in sync automatically (both read/write `navigationRailCollapsed`).

## Capabilities

### New Capabilities
- `navigation-rail-collapse`: User control over the navigation rail's collapsed/expanded state — an in-rail toggle, persisted state, and animated width transition. No automatic width-based collapsing.

### Modified Capabilities
<!-- No existing spec-level requirements change. The nav rail was not previously specified. -->

## Impact

- **Code:**
  - `desktop-viewer/desktop-app/.../desktop/shell/CommercialAppShell.kt` — delete the auto-collapse helpers and `resolvedNavigationRailCollapsed(...)` call; use `navigationRailCollapsed` directly; add the toggle row to `StudioNavigationRail`; animate the rail width with `animateDpAsState`.
  - `desktop-viewer/desktop-app/.../desktop/DesktopAppMainPage.kt` — no change (still passes `applicationSettings.navigationRailCollapsed`; the type stays `Boolean`).
  - `desktop-viewer/desktop-app/.../desktop/ApplicationUiSettings.kt` — no change to the field type; it is already `Boolean` (default `true`).
  - `desktop-viewer/desktop-app/.../desktop/DesktopAppSettingsPage.kt` — no change; the "Show sidebar titles" toggle remains.
- **Strings:** none new — reuse existing `collapse_navigation` / `expand_navigation`.
- **Icons:** reuse existing `ic_toggle_left` / `ic_toggle_right` from ui-components.
- **Tests:** `CommercialAppShellTest` currently asserts the auto-collapse helpers and their breakpoint behavior — those tests move to asserting that the rail reads the persisted `Boolean` directly (no width fallback). Add a contract test that the rail renders a collapse toggle that flips the state.
- **Risk:** low. The dead auto branch removal is a behavior no-op for existing users; the toggle and animation are additive. The only real surface is the `CommercialAppShellTest` rewrite.
