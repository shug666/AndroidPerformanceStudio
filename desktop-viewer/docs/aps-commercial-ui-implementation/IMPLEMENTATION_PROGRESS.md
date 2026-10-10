# Commercial UI — implementation and validation progress

This is a continuation checkpoint for the [phased implementation plan](android-performance-studio-commercial-ui-implementation.md), not an assertion that the full commercial UI is finished.

## 2026-10-11: Hover titles on the collapsed rail

### Implemented

- While the rail is collapsed, every entry is wrapped in a desktop `TooltipArea` that reveals the
  destination title in a `RailTooltip` panel after 400 ms. The collapse/expand control gets the same
  treatment using its action label. Expanded entries keep their visible label and show no tooltip.

### Verification

- Temporary UI test: hovering the collapsed Home icon moved the count of `首页` text nodes from 0 to
  1 and the tooltip node rendered its label. The first attempt was invalid because it asserted on
  "Layout Inspector", which the home page also renders as a module card.
- The tooltip is drawn in a separate popup root, so whole-window captures exclude it; the tooltip
  node is captured instead under `build/ui-verification/rail-tooltip.png`.
- `CommercialAppShellTest` asserts the tooltip wiring and that the hover title is gated on the
  collapsed state.
- `:desktop-app:test` passes 152 tests, zero failures.

## 2026-10-11: Collapse control aligned with the navigation icon column

### Implemented

- The rail collapse/expand control now reuses the same geometry as `NavigationRailEntry`: the row
  padding applies only while expanded and the icon is centred while collapsed. Its chevron therefore
  sits on the navigation icon axis instead of floating in the middle of the 216 dp expanded rail.

### Verification

- Rendered both states and measured the bottom of the rail: the chevron cluster is x 24..29
  (centre 26.5) in the 216 dp expanded rail and in the 54 dp collapsed rail, matching every
  navigation icon and the settings gear.
- `CommercialAppShellTest` asserts that both the entry and the collapse control carry the collapsed
  centring rule.
- `:desktop-app:test` passes 151 tests, zero failures.

## 2026-10-10: Collapsed rail narrowed so centring does not move the icons

### Implemented

- `StudioTokens.navigationCollapsedWidth` is now 54 dp instead of 64 dp. With 8 dp rail padding and a
  centred 18 dp icon, 54 dp places the icon's left edge at the same x as the expanded row
  (8 dp rail padding + 10 dp row padding), so collapsing no longer shifts an icon while the icon is
  still centred in the rail. 64 dp cannot satisfy both: a centred icon there lands 5 dp right of the
  expanded position.
- This intentionally departs from the "Navigation Collapsed 64 dp" entry in the design plan's token
  table; the plan document was left unchanged.

### Verification

- Measured both renders: collapsed rail 53 px wide with the icon spanning x 20..33 (centre 26.5, rail
  centre 26.5) and the expanded icon starting at the same x = 20, so the icon neither moves nor sits
  off-centre.
- `StudioComponentsTest` now pins `navigationCollapsedWidth` at 54 dp; `:ui-components:test` and
  `:desktop-app:test` pass.

## 2026-10-10: Compact theme colour swatches

### Implemented

- Settings -> General theme colours are now a four-column grid of 38 dp chips with an 18 dp swatch
  dot and a 12 pt label, replacing two columns of 72 dp cards. The selected chip keeps its colour
  border, tinted container and check mark.

### Verification

- `ApplicationSettingsDialogTest` gained a case pinning the compact swatch metrics
  (`THEME_SWATCH_COLUMNS = 4`, `height(38.dp)`, `size(18.dp)`).
- `:desktop-app:test` passed 151 tests, zero failures.
- Offscreen render at 1400x700 confirmed the two-row swatch grid and the four settings dropdowns
  above it.

## 2026-10-10: Settings becomes a routed page instead of a dialog

### Implemented

- Added `AppDestination.SETTINGS`, so settings is a first-class destination composed in the retained
  content host with its own title, icon, rail entry and selected state.
- Converted `DesktopAppSettingsDialog` into `DesktopAppSettingsPage`: the `DialogWindow`, its dialog
  state, the modal surface and the "Done" footer are gone. The page fills the content host and leads
  with a "Settings" header above its own sub-sidebar.
- The rail settings entry now highlights like every other destination, and both `Cmd/Ctrl+,` and the
  macOS Preferences menu route to the same destination.
- Removed the `showSettings` flag; `openSettings(page)` records the requested sub-page and opens the
  destination, so feature pages can still deep-link into a section.
- Settings no longer needs a shell callback, so `CommercialAppShell.onOpenSettings` and the
  standalone `railSettingsIcon` accessor are gone; the entry uses the destination mapping.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
```

- `:desktop-app:test` passed 150 tests, zero failures.
- Source tests that read the dialog file were repointed at `DesktopAppSettingsPage.kt`; the former
  "large and resizable window" case now asserts the page is routed and that no `DialogWindow` or
  `rememberDialogState` remains, and that `showSettings` is gone.
- Offscreen renders at 1400x760 confirmed the settings page inside the shell with the rail entry
  selected, for both the Chinese General page and the English About page.
- `docs/aps-commercial-ui-implementation/images/reports-settings-v2-*.png` remain untracked user
  references; the settings sub-sidebar still uses the existing sections rather than the nine in that
  design board.

## 2026-10-10: Shell top bar removed and settings moved into the rail

### Implemented

- Removed `StudioTopBar`. The shell is now rail + content + status bar, so feature content gets the
  full window height.
- Settings is a regular rail entry pinned at the bottom below a divider, and reuses the shared
  `NavigationRailEntry` row, so it collapses to the icon-only gear like every other entry. The
  `Cmd/Ctrl+,` shortcut and the macOS Preferences menu still open it.
- The Home rail entry now uses the short "Home" string through
  `AppDestination.navigationLabelResource`; `titleResource` keeps the product name for the window
  title and status bar.
- The command palette is still reachable with `Cmd/Ctrl+K`; the product name, destination, and
  artifact context remain in the status bar. The top bar's workspace-context chips are gone with it
  (the current call site only ever passed an empty context).
- Removed the now-dead `WorkspaceContext.contextLabels()` helper and the top-bar-only string imports.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
```

- `:desktop-app:test` passed 150 tests, zero failures.
- `CommercialAppShellTest` now asserts the top bar is gone and that the settings entry, the divider,
  and the shared rail row exist; a new case covers `navigationLabelResource` for Home.
- `ProfilerThemeSupportTest` checks rail tokens instead of the removed top bar height.
- Offscreen renders of the full shell at 1500x1200 (expanded) and 1100x1200 (collapsed) confirmed the
  divider / settings / chevron order in both states.

## 2026-10-10: Home dashboard two-column redesign

### Scope

- Home page presentation only. The shell, routing, feature state, and the recent-artifact and device
  projections are unchanged.
- Reference: `images/01-home-dashboard.png`. The plan forbids inventing metrics, so only sections
  with a real data source are rendered.

### Implemented

- Two columns above 900 dp: a scrolling main column plus a 320 dp right rail. Narrower widths stack
  the rail below the main column instead of dropping it.
- Hero banner with the welcome title, subtitle, and reference quote over an accent gradient.
- KPI row reduced to the two real projections (connected devices; recent artifacts shown). Added an
  optional icon to `StudioMetricCard`; both cards now share one intrinsic height.
- Quick actions are four icon cards (Start Capture, Import Trace, Analyze HPROF, Run Startup Test)
  that route into existing capture/import flows. They stay a four-up row until the column narrows.
- Analysis modules reuse the shell navigation icons and destination titles in an adaptive 3/2/1 grid.
- Recent sessions table keeps only columns backed by real artifacts: name, package, type, started,
  action.
- Right rail: localized date card and the connected-devices panel (moved out of the main column).
- Sections without a data source (jank alerts, regression counts, recent reports, AI insights) are
  intentionally not rendered.
- Added 18 EN/ZH strings and four vectors (device, history, play, import).

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
./gradlew :ui-components:test --offline --tests "com.androidperformancestudio.ui.studio.StudioIconRenderingTest"
```

- `:desktop-app:test` passed 149 tests, zero failures, including the 9 rewritten
  `AppHomePageLayoutTest` cases.
- `StudioIconRenderingTest` now renders all 19 bundled vectors through `painterResource` and `Icon`
  and asserts each paints visible pixels.
- Offscreen renders at 1500x1400 for both locales confirmed the two-column layout, the four-up quick
  actions, equal-height KPI cards, and real recent-session rows.
- Three source-wiring tests that asserted the previous home markup
  (`RecentArtifactNavigationWiringTest`, `SourceWorkspaceLocalizationTest`) were updated to the new
  wiring while keeping their intent.
- The pre-existing unrelated failures noted in the previous entry are unchanged.

## 2026-10-10: Shell navigation rail icons and icon-only collapse

### Scope

- Presentation-only batch for the shell navigation rail. Controller, Model, capture, archive, and
  routing contracts are unchanged; no destination was added or removed.
- The reference is `images/01-home-dashboard.png`, which shows one line icon per navigation item and
  an icon-only rail.

### Implemented

- Added 15 SVG vectors under `ui-components/src/main/composeResources/drawable/`: one per
  `AppDestination` plus rail collapse/expand chevrons.
- `AppDestination.navigationIconResource` maps every destination to its icon, so an unmapped
  destination is a compile error.
- `StudioNavigationRail` now draws the projected icon beside the label. When the rail is collapsed
  it renders the icon alone and keeps the label as the icon `contentDescription`, replacing the
  previous first-character label truncation.
- Both states share one rail row component. Expanded rows start at a constant offset; the collapsed
  rail drops `contentPadding` to zero and centres the icon, so it lines up with the collapse control.
- The rail footer toggle is now a chevron icon (it already toggled collapse); the top bar control and
  the below-1280 dp auto-collapse are unchanged.
- Added `StudioTokens.navigationIconSize` (18 dp), `navigationIconGap` (10 dp), and
  `navigationItemHorizontalPadding` (10 dp), the fixed leading offset the icon keeps across collapse.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
./gradlew :ui-components:test --offline --tests "com.androidperformancestudio.ui.studio.StudioNavigationIconTest"
```

- `:desktop-app:test` passed 145 tests, zero failures, including three new `CommercialAppShellTest`
  cases: distinct icon per destination, rail icon rendering plus icon-only collapse wiring, and the
  presence of every icon drawable.
- `StudioNavigationIconTest` renders all 15 vectors through `painterResource` and `Icon` and asserts
  each paints visible pixels, so a malformed SVG fails in tests instead of showing a blank rail.
- The shell was rendered offscreen at 1400x640 (expanded) and 900x640 (collapsed). The expanded rail
  shows icon plus label with the selected accent; the collapsed rail shows the icons alone.
- Measured the collapsed rail: every primary icon, the settings gear, and the collapse chevron share
  the same horizontal centre, so the collapsed rail is centred consistently.
- Pre-existing, unrelated failures were left untouched: `UnifiedUiComponentsSourceTest` (the
  `HomeButton.kt` accent assertion, and raw colors in `SourceCodeViewer.kt` / `OneDarkSourceTheme.kt`)
  and `:ui-components:detekt` (22 issues in files this batch did not modify).

## 2026-10-10: Phase 4 — Layout Inspector resize and visibility

### Scope and baseline

- Branch: `feat/aps-commercial-ui`; starting HEAD: `0f4f8b75`.
- The worktree was clean before this batch. No commit or push was requested or performed.
- This batch changes presentation layout only. Controller, Model, capture, archive formats, and source-resolution contracts are unchanged.
- Before editing, the existing `PaneLayoutTest` and `FindingsLayoutTest` passed: 15 tests, no failures.

### Implemented

- Fit and drag calculations now reserve width and splitters only for visible side panels.
- Hidden panel widths remain remembered preferences. Toggling visibility and fitting a smaller window do not overwrite those preferences.
- The hierarchy-only and properties-only workspaces use regular sizing whenever their own visible layout fits, rather than the three-panel minimum.
- Existing both-visible defaults, bounds, and drag directions are preserved.
- Added nine layout regression tests, including a feasible-width matrix for all four visibility combinations, hidden preferences, canvas minimums, drag clamps, and fit idempotence.
- Added a source-wiring regression test for visibility arguments in the workspace and both drag callbacks. This is not a live interaction test.

### Fresh verification

Run from `desktop-viewer`:

```sh
./gradlew \
  :layout-inspector:presentation:test \
  :layout-inspector:application:test \
  :layout-inspector:analysis-engine:test \
  :layout-inspector:protocol-model:test \
  :layout-inspector:compose-inspection-model:test \
  :layout-inspector:compose-inspection-host:test \
  :layout-inspector:adb-gateway:test \
  :desktop-app:test :desktop-app:createDistributable
```

- Passed 446 tests, zero failures/errors/skips: presentation 221, application 31, compose-inspection-host 12, adb-gateway 40, desktop-app 142.
- The other listed Layout Inspector test tasks are `NO-SOURCE`; they do not add coverage.
- `PaneLayout.kt`, `PaneLayoutTest.kt`, and `PanelVisibilityTest.kt` pass ktlint 1.5.0 using a temporary Gradle init-script task; the repository has no Layout Inspector ktlint task.
- The main page is not lint-clean. Comparing the unchanged HEAD copy with the modified file found 670 versus 657 violations, with no added diagnostic/message counts. Only the touched layout calls were locally formatted; unrelated file-wide lint debt was not swept.
- `git diff --check` passed. Independent read-only review found no defects within the visibility-aware layout scope; this is not runtime acceptance.
- The new macOS app image was built under `desktop-app/build/compose/binaries/main/app/AndroidPerfermanceStudio.app`.
- Compared all 360 ZIP entries in the packaged and built Layout Inspector presentation JARs: identical names and entry bytes. The changed `PaneLayout.class` and `LayoutInspectorMainPageKt.class` also match by SHA-256. The complete JAR files have different ZIP metadata/encoding and do not have equal whole-file hashes.

### Packaged GUI acceptance: blocked, not passed

- Regenerated the existing synthetic 10,000-node archive using `APS_SYNTHETIC_ARCHIVE_OUTPUT=/tmp/aps-synthetic-10k-20261010.apinspect` and reran the full presentation suite. This proves synthetic archive round-trip and presentation projection only, not real-device behavior or GUI performance.
- Launched the exact new app-image path. The process remained alive, and its thread dump reached the Compose application event loop.
- Native computer-use attachment to the exact path returned `cgWindowNotFound` on two attempts. Bundle-ID attachment was ambiguous because the installed app and new image share an ID. The optional foreground-launch API was unavailable.
- Consequently no screenshot, archive import, side-panel toggle/drag, or minimum-window interaction has been accepted. Process startup is not a GUI smoke-test pass. The QA-launched process was terminated after collecting the diagnostics.

### Open acceptance gates and continuation

- [ ] Packaged GUI: import the synthetic archive; verify hierarchy/canvas/details/findings selection, visibility toggles, and separator drags.
- [ ] Actual 1280×720 window, resizing, preferred-width restoration, and navigation-rail collapse interactions.
- [ ] Dark/light, language, display-scale, and shortcut behavior in the running workspace.
- [ ] Synthetic 10k-node UI responsiveness and real-device capture/archive behavior.
- [ ] Phase 4 panel extraction and visual redesign parity against the main plan, followed by the remaining phased migration. The other phases were not comprehensively re-audited in this batch.

Do not promote local tests, source-wiring checks, archive fixtures, or app-image creation into full commercial UI or release completion.
