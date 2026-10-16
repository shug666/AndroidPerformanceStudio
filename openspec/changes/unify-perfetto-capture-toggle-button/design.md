## Context

The Trace Analyzer capture page (`PerfettoCapturePage.kt`) presents capture controls in the Configuration panel's action row. Today the row contains up to three `PerfettoCompactButton` instances: "导出配置" (always), "开始采集" (always present, `enabled = canStartCapture`, `selected = canStartCapture`), "停止" (only when `captureState is Recording`), and "在 Perfetto UI 中打开" (only when `Completed`).

The capture state machine (`PerfettoCaptureState`) transitions `Idle → Preparing → Recording → Pulling → Completed` (with `Failed` reachable from any active phase). The session (`PerfettoCaptureSession`) only meaningfully supports stop during `Recording`: it sends `SIGTERM` to device-side perfetto, which flushes and exits, after which the partial trace is pulled and the session lands in `Completed` (stop preserves data; it does not discard). `Preparing` (pushing a config file) and `Pulling` (pulling the trace) cannot be interrupted because `AdbClient.push`/`pull` expose no cancellation hook — only the `runDeviceCapture` shell wires `isCancellationRequested`.

`PerfettoCompactButton` (`PerfettoMacOsUi.kt`) is a shared control used at 25 call sites across dialogs and pages. It has a single `selected: Boolean` that toggles between `primary` fill and `surfaceVariant` neutral. There is no destructive/error color variant; notably the existing "停止" button renders in neutral (not selected), so it has never been visually emphasized.

## Goals / Non-Goals

**Goals:**
- Replace the separate start/stop buttons with a single state-driven toggle button.
- Keep behavior identical to today: start when a capture can begin, stop only when recording (the only reliably interruptible phase).
- Visually distinguish the stop action as destructive, since after consolidation it becomes the sole actionable control during a live capture.
- Touch only the presentation layer and one shared component; leave session semantics and callback signatures untouched.

**Non-Goals:**
- Adding cancellation support for `Preparing`/`Pulling` (requires `AdbClient` cancellation hooks and a data-loss UX decision — separate change).
- Changing what "stop" does (it still flushes + pulls a partial trace → `Completed`).
- Altering the "在 Perfetto UI 中打开" or "导出配置" actions.
- Adding new localized strings for intermediate states (the status line already communicates the phase).

## Decisions

### Decision 1: Single button, state-driven label/action
The action row renders one capture button whose text and click handler follow `captureState`:

| State | Text | Enabled | Action |
|-------|------|---------|--------|
| Idle / Completed / Failed | 开始采集 | `canStartCapture` | `onStartCapture(config, serial)` |
| Preparing | 开始采集 | false (disabled) | — |
| Recording | 停止 | true | `onStopCapture()` |
| Pulling | 开始采集 | false (disabled) | — |

**Rationale:** `canStartCapture` (via `isCaptureStartAllowed`) is already false during `Preparing`/`Recording`/`Pulling`, so reusing it as the `enabled` gate makes the disabled-start label fall out for free during `Preparing` and `Pulling`. The `Recording` branch overrides to an enabled stop. This is the minimal diff that produces option A (safe toggle) from the exploration.

**Alternative considered:** Show distinct disabled labels ("准备中…"/"拉取中…") during intermediate states. Rejected because the `CaptureStatus` composable already renders `preparing_capture`/`pulling_trace_from_device` plus a progress bar directly below the row; a second copy in a 24dp button is redundant and would require two new short strings that don't exist today.

**Alternative considered:** Hide the button entirely during intermediate states. Rejected because a button that disappears and reappears causes layout shift and loses the affordance's position; a disabled-in-place button is steadier.

### Decision 2: Route click by state, keep both callbacks
The button's `onClick` is `if (captureState is Recording) onStopCapture else { startLambda }`, where `startLambda` is the existing `selectedDeviceSerial?.let { onStartCapture(createCaptureConfig(...), it) }` block. `onStartCapture` and `onStopCapture` parameters and their `PerfettoMainPage` wiring are unchanged — no signature churn, no presenter refactor.

### Decision 3: Add `danger` variant to `PerfettoCompactButton`
Add `danger: Boolean = false` to `PerfettoCompactButton`. When `danger && selected` (or `danger && enabled` for a non-selected destructive button), the background/border use `MaterialTheme.colorScheme.error` and the text uses `onError` (or a readable error contrast). The stop button passes `danger = true`.

**Rationale:** After consolidation, "停止" is the only clickable control during a live capture; a neutral-gray stop (today's appearance) gives no signal that it terminates an in-progress recording. Red is the established destructive idiom. Defaulting `danger = false` means all 25 existing call sites compile unchanged.

**Alternative considered:** Leave the stop button neutral to match today exactly (visual zero-regression). Rejected — the exploration surfaced that today's neutral stop was never an intentional choice, just the default of a button with no `selected` flag; consolidation is the right moment to give the destructive action appropriate emphasis.

**Color detail:** The stop button is rendered with `selected = false` + `danger = true` + `enabled = true`, so it reads as an outlined red button (error-colored border/text on neutral fill) rather than a solid red block — consistent with the component's existing outlined aesthetic and less alarming than a filled red button.

### Decision 4: `canStartCapture` reused unchanged
`isCaptureStartAllowed` already encodes "device selected, capabilities ready, custom config ready, not in an active phase." It continues to drive the start branch's `enabled`/`selected`. No logic moves into the session layer.

## Risks / Trade-offs

- **[Disabled "开始采集" during `Preparing`/`Pulling` may read as "I can start now but it's greyed out"]** → Mitigation: the status line directly below already says "正在准备采集…"/"正在从设备拉取轨迹…" with a progress bar, disambiguating why the button is disabled. Acceptable redundancy.
- **[Stop → `Pulling` → possibly `Failed` if the pull fails]**: after clicking stop the button briefly shows disabled "开始采集" during `Pulling`, then either `Completed` (button → enabled "开始采集") or `Failed` (button → enabled "开始采集"). The transition is consistent; no stuck state. → Mitigation: none needed; the state mapping covers this path. Verified against `PerfettoCaptureSession.stopCapture` which can `setFailed` on pull failure.
- **[`danger` variant broadens a shared component]** → Mitigation: additive optional parameter defaulting off; existing call sites and dialog buttons (All/None/OK/Cancel) are visually identical. A render snapshot test already exists for the page; the new variant is exercised only by the stop state, which current tests don't render — adding a `Recording`-state render assertion in tasks guards against regression.
- **[No new strings, so no localization test impact]** → Mitigation: the existing `PerfettoLocalizationTest` asserts `start_capture`/`stop` strings, which are reused unchanged.

## Migration Plan

Single-PR, presentation-only change. No data migration, no API change, no session-layer change. Deploy is a build of the `perfetto-presentation` + `perfetto-app` modules. Rollback is reverting the two files (`PerfettoCapturePage.kt`, `PerfettoMacOsUi.kt`); no persisted state is affected.
