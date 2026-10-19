## Why

The Trace Analyzer capture page renders "开始采集" and "停止" as two separate buttons in the same row. "开始采集" is always present (merely disabled mid-capture) while "停止" only appears during `Recording`, leaving an inconsistent control surface where the start button flickers between enabled/disabled and the stop button pops in and out. Users must track two controls to operate a single two-phase action (start, then stop). A single toggle button that reflects the current operation reduces visual clutter and aligns with the established record/stop idiom used across profilers.

## What Changes

- Collapse the "开始采集" and "停止" buttons in the Configuration panel's action row into a single toggle button whose label and action follow the capture state: "开始采集" (start) when idle/ready to start, "停止" (stop) when recording.
- During intermediate states the session cannot reliably interrupt (`Preparing`, `Pulling`), the single button shows the disabled start label and relies on the existing status line + progress bar to communicate the in-progress phase — no new cancellation capability is introduced.
- Introduce an optional `danger` (red) visual variant on `PerfettoCompactButton` so the "停止" state is visually distinguished as a destructive action. This is additive: the new parameter defaults off, so the 25 existing call sites are unchanged.
- The "在 Perfetto UI 中打开" action on `Completed` remains a separate button in the same row (it is a distinct action, not part of the start/stop toggle).
- No change to `PerfettoCaptureSession` semantics: start and stop behavior, and their signatures (`onStartCapture`/`onStopCapture`), are unchanged. The page routes the single button's click to the existing callbacks based on state.

## Capabilities

### New Capabilities
<!-- None — this modifies an existing capability. -->

### Modified Capabilities
- `trace-capture-session`: the user-facing capture control surface changes from two separate start/stop buttons to a single state-driven toggle button, with a destructive visual treatment for the stop action.

## Impact

- **UI**: `PerfettoCapturePage.kt` — the `PerfettoConfigurationPanel` action row (~lines 871-903) merges the start and stop buttons into one; `isCaptureStartAllowed` is reused to drive enabled/selected. `PerfettoMainPage.kt` wiring is unchanged (both callbacks retained).
- **Shared component**: `PerfettoMacOsUi.kt` — `PerfettoCompactButton` gains a `danger: Boolean = false` parameter that switches the background/border/text to the error color when selected. No existing call site needs modification.
- **Localization**: existing strings (`start_capture`="开始采集", `stop`="停止") are reused; no new strings required (intermediate-state labels reuse the disabled start label, with the status line already explaining the phase).
- **Tests**: `PerfettoCapturePageRenderTest` and `PerfettoCapturePageLayoutTest` render only in `Idle` and assert no stop-button existence, so they pass unchanged; the layout test's `source.contains("PerfettoCompactButton(")` still holds. No test asserts both buttons coexist.
- **Out of scope**: cancelling a capture during `Preparing`/`Pulling` (requires `AdbClient.push`/`pull` cancellation hooks and a data-loss UX decision) — to be proposed as a separate change if desired.
