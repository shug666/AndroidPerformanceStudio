## 1. Shared button component

- [x] 1.1 Add `danger: Boolean = false` parameter to `PerfettoCompactButton` in `PerfettoMacOsUi.kt`
- [x] 1.2 When `danger && enabled && !selected`, render the button with `MaterialTheme.colorScheme.error` border and text on the neutral `surfaceVariant` fill (outlined-error style), keeping the existing height/shape/padding
- [x] 1.3 Verify all 25 existing `PerfettoCompactButton` call sites compile and render unchanged (no call site passes `danger`)

## 2. Capture page action row

- [x] 2.1 In `PerfettoConfigurationPanel` (`PerfettoCapturePage.kt` ~lines 871-903), remove the separate "停止" `PerfettoCompactButton` and the separate "开始采集" button; replace with a single toggle button
- [x] 2.2 Compute the toggle button's state from `captureState`: `isRecording = captureState is PerfettoCaptureState.Recording`
- [x] 2.3 Set the toggle button's `text` to `localizedStringResource(Res.string.stop, language)` when recording, else `localizedStringResource(Res.string.start_capture, language)`
- [x] 2.4 Set `enabled = if (isRecording) true else canStartCapture` and `selected = if (isRecording) false else canStartCapture`
- [x] 2.5 Set `danger = isRecording` so the stop state renders in the error color treatment
- [x] 2.6 Set `onClick`: when recording invoke `onStopCapture()`; otherwise run the existing `selectedDeviceSerial?.let { onStartCapture(createCaptureConfig(...), it) }` block (reuse the current start lambda verbatim)
- [x] 2.7 Leave the "导出配置" and "在 Perfetto UI 中打开" buttons in place and unchanged

## 3. Tests

- [x] 3.1 Run existing `PerfettoCapturePageRenderTest` and `PerfettoCapturePageLayoutTest`; confirm both still pass (they render only `Idle` and assert no stop-button existence)
- [x] 3.2 Add a render test asserting that in `Recording` state exactly one capture control button is present and its text is "停止"
- [x] 3.3 Add a render test asserting that in `Recording` state the stop button renders with the error color (snapshot/RGB assertion matching the existing screenshot pattern, or a semantics check)
- [x] 3.4 Add a render test asserting that in `Preparing` and `Pulling` states the single button shows "开始采集" and is disabled
- [x] 3.5 Run `PerfettoLocalizationTest`; confirm `start_capture`/`stop` string assertions still pass (no new strings added)

## 4. Build & verify

- [x] 4.1 Build the `perfetto-presentation` and `perfetto-app` modules (`./gradlew :perfetto-viewer:perfetto-presentation:build :perfetto-viewer:perfetto-app:build` or equivalent)
- [ ] 4.2 Launch the desktop viewer, start a capture, confirm the button toggles from "开始采集" (primary) → "停止" (red outlined) → disabled "开始采集" during pull → "开始采集" (primary) on completion, and that "在 Perfetto UI 中打开" still appears on `Completed`
- [x] 4.3 Confirm `onStartCapture`/`onStopCapture` signatures and `PerfettoMainPage` wiring are unchanged (diff should not touch `PerfettoMainPage.kt` capture callbacks)
