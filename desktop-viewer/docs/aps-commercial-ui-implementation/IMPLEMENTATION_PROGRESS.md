# Commercial UI — implementation and validation progress

This is a continuation checkpoint for the [phased implementation plan](android-performance-studio-commercial-ui-implementation.md), not an assertion that the full commercial UI is finished.

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
- Both states share one start-aligned row and one constant `contentPadding`, so collapsing never
  moves an icon horizontally; the icon only loses its label.
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
- Measured the icon left edge for all 11 primary rows in both renders: identical x-range in every row,
  so collapse does not shift an icon horizontally.
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
