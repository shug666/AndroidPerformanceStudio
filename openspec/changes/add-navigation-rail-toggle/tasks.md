## 1. Remove auto-collapse

- [x] 1.1 In `CommercialAppShell.kt`, delete `NAVIGATION_COLLAPSE_BREAKPOINT_DP`, `navigationRailCollapsedFor`, and `resolvedNavigationRailCollapsed`.
- [x] 1.2 Replace `val collapsed = resolvedNavigationRailCollapsed(maxWidth.value.toInt(), navigationRailCollapsed)` with `val collapsed = navigationRailCollapsed`.

## 2. Add the collapse/expand toggle

- [x] 2.1 In `StudioNavigationRail`, add a toggle row between the `Spacer` and the Settings entry: expanded shows `ic_toggle_left` + the "Collapse navigation" label; collapsed shows a centered `ic_toggle_right`.
- [x] 2.2 Wire the toggle's `onClick` to flip `navigationRailCollapsed` via a new `onToggleCollapsed: () -> Unit` param threaded from `CommercialAppShell` (which calls back into the settings updater).
- [x] 2.3 Reuse the `NavigationRailEntry` shape so the toggle aligns with the other rows; the toggle is never `selected` and carries a hover tooltip in the collapsed state ("Expand navigation").
- [x] 2.4 Thread the toggle callback from `DesktopAppMainPage` (reuse `updateApplicationSettings` with `settings.copy(navigationRailCollapsed = !settings.navigationRailCollapsed)`).

## 3. Animate the rail width

- [x] 3.1 Replace `val width = if (collapsed) W1 else W2` with `val width by animateDpAsState(if (collapsed) W1 else W2)` in `StudioNavigationRail` (collapsed 54dp / expanded 216dp).

## 4. Tests

- [x] 4.1 In `CommercialAppShellTest`, remove the assertions on `navigationRailCollapsedFor` / `resolvedNavigationRailCollapsed` / the breakpoint, and the `resolvedNavigationRailCollapsed(...)` call assertion.
- [x] 4.2 Add assertions that the rail reads `navigationRailCollapsed` directly (no fallback), renders the toggle (e.g. references `ic_toggle_left` / `ic_toggle_right` and the `collapse_navigation` / `expand_navigation` strings), and animates width via `animateDpAsState`.
- [x] 4.3 Run `:desktop-app:test` and confirm green.

## 5. Build & verify

- [x] 5.1 Compile `:desktop-app:compileKotlin`; run the full `:desktop-app:test` suite.
- [x] 5.2 Commit on a branch, push, confirm CI green on the PR.
