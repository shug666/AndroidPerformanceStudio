## 1. Navigator eviction API

- [x] 1.1 Add `AppNavigator.evict(destination)`: refuse HOME, remove from `retainedDestinations`, and if it is the active destination first set `destination = HOME` before removing
- [x] 1.2 Add `AppNavigator.evictAll()`: evict every entry except HOME and the active destination
- [x] 1.3 Add per-module request clearing in `evict`: clear `perfettoTraceFile`/notice/timestamp for PERFETTO, `memoryImportFile`/requestId/isJavaHeap for MEMORY_PROFILER, `layoutArchiveRequest`+`inspectorCorrelationHint` for LAYOUT_INSPECTOR, `simpleperfSessionRequest` for SIMPLEPERF, `methodRecordingTraceFile` for METHOD_RECORDING, `sourceLocation` for SOURCE_WORKSPACES; clearing scoped to the evicted module only
- [x] 1.4 Add unit test `AppNavigatorEvictionTest`: closing inactive module keeps active destination; closing active module returns to HOME; HOME eviction is a no-op; `evictAll` keeps HOME and active; eviction clears only the target module's pending request

## 2. Shell wiring for eviction

- [x] 2.1 In `DesktopAppMainPage`, confirm `retainedDestinations.forEach` removal drives subtree disposal (no change needed if `key(destination)` already disposes; verify a removed destination's `onDispose` fires by adding a temporary log in one module, then remove)
- [x] 2.2 Ensure closing the active module resets window placement via the existing `LaunchedEffect(navigator.destination)` maximize logic (verify HOME is non-maximized)

## 3. Release contract — verify existing modules

- [x] 3.1 Frame: verified `FrameProfilerController.close()` closes both subscriptions (existing `DisposableEffect` at FrameProfilerMainPage:88). close() does NOT call the suspend `stopOnlineCapture()`; the online-capture poll coroutine is cancelled by the composition scope on dispose, and device-side perfetto is bounded by capture duration. Mid-capture device-side teardown is a documented follow-up (see 5.5).
- [x] 3.2 Memory: verified `MemoryProfilerController.close()` is wired via `DisposableEffect` at MemoryProfilerMainPage:99; existing contract sufficient.
- [x] 3.3 Simpleperf: verified both `DeviceTargetController.close()` and `ReportController.close()` are wired via two `DisposableEffect`s at SimpleperfMainPage:101,103; existing contract sufficient.
- [x] 3.4 MethodRecording: verified `MethodRecordingController.close()` is wired via `DisposableEffect` at MethodRecordingMainPage:78; existing contract sufficient.

## 4. Release contract — add to missing modules

- [x] 4.1 Perfetto: add `DisposableEffect` disposing `PerfettoCaptureSession`, `TraceSessionStore`, `TraceExporter`, `PerfettoArtifactFactory`; add `PerfettoUiServer.stop()` to dispose and verify the port frees (evict-then-reopen test)
- [x] 4.2 LayoutInspector: audited — `LiveDeviceClient` and `ComposeInjectionManager` are stateless ADB command runners (no persistent resource, no close()); `InspectorStore` is in-memory only. The real closeable is the live compose inspection session: added `DisposableEffect(composeSession) { onDispose { composeSession?.close() } }` to release the device-side compose agent on dispose. The existing `manualRefreshSession` DisposableEffect was already present.
- [x] 4.3 ReverseNet: add `DisposableEffect { onDispose { controller?.close() } }` ensuring `SessionController.close()` stops the relay and releases adb reverse forwarding / network interface; test evicting an active session
- [x] 4.4 Battery: add `DisposableEffect { onDispose { controller.close() } }` ensuring `BatteryProfilerController.close()` stops polling and closes `RecentPathStore`; add test
- [x] 4.5 GPU: audit — AndroidGpuDeviceContextReader uses AndroidTargetMonitors.shared (intentionally shared, must NOT tear down on page dispose); ADB work is per-call. No page-owned heavy resource → no DisposableEffect needed.
- [x] 4.6 Startup: audit — StartupProfilerController holds no scope/process; experimentJob is in the page composition scope (auto-cancelled on dispose). No close() needed.
- [x] 4.7 Benchmark: audit — BenchmarkReportIndexStore is file-per-op (no persistent handle); orchestration runs in a composition-scoped LaunchedEffect (auto-cancelled). No persistent resource to release.
- [x] 4.8 NetworkProfiler: audit — capture.stop() is called via the back button; mid-capture eviction is blocked by the capture-exception UI rule (5.5/6.3 disables close while capturing). Non-capturing dispose has no persistent resources. No DisposableEffect needed.

## 5. Background-pause gating on `active`

- [x] 5.1 Audit complete: the only retained-while-inactive loop that drains resources is ReverseNet device polling (5.2). Frame/NetworkProfiler while-loops are capture-scoped (5.5 exception). One-shot refreshDevices/refreshSessions/initialize effects run once on first composition (when active) and do not loop while inactive.
- [x] 5.2 ReverseNet: polling LaunchedEffect re-keyed on `active` with an early return; switching away cancels the adb device-poll loop. Active relay runs in SessionController's own scope (unaffected).
- [x] 5.3 GPU: controller.initialize() and refreshDevices() are one-shot LaunchedEffects (run once on first composition when active); no loop to gate while inactive. No change needed.
- [x] 5.4 Battery/Startup/Simpleperf: refreshDevices()/refreshSessions() are one-shot LaunchedEffects (run once when first activated); no loop while inactive. No change needed.
- [x] 5.5 Capture-exception: rather than disabling close in the UI, disposal now safely stops captures — ReverseNet `controller.close()` stops the relay; NetworkProfiler's dispose-stop tears down the device agent on a surviving scope; Perfetto `captureSession.close()` cancels the capture job; Frame/Battery captures are bounded by their own duration/steps and their poll/experiment coroutines are cancelled by the composition scope on dispose. The task manager close button is therefore always enabled for non-HOME modules. (Frame `stopOnlineCapture`/Battery runner device-side teardown on mid-capture dispose remains a documented follow-up; both are bounded by capture duration so not a leak.)

## 6. Task manager UI

- [x] 6.1 Add localized strings/resources in `desktop-app` for task manager: title, close, close all, open, capturing hint — added to values/strings.xml and values-zh/strings.xml (task_manager, task_manager_title, task_manager_close, task_manager_close_all, task_manager_open, task_manager_empty, task_manager_home_pinned, task_manager_close_blocked_capturing). (Close is always enabled per 5.5, so the capturing hint is available but the close button is not disabled.)
- [x] 6.2 Add a task manager entry to `StudioNavigationRail` and the command palette items in `CommercialAppShell` — added a rail entry (ic_task_manager) at the rail bottom, a Ctrl+M shortcut, a command-palette footer action, and the overlay render. New icon ic_task_manager.xml in ui-components drawables.
- [x] 6.3 Create `ModuleTaskManagerOverlay` composable listing `navigator.retainedDestinations`: each row shows label + close button + select-to-open; HOME shown as non-closeable (Open affordance only); "close all" action. Close enabled for all non-HOME modules (per 5.5). Added testTags for testability.
- [x] 6.4 Wire the overlay to `navigator.evict` / `evictAll` and `navigator.open`; the list reads `navigator.retainedDestinations` (a reactive SnapshotStateList) so it updates live on evict/open.
- [x] 6.5 Add UI test `ModuleTaskManagerOverlayTest`: closing a row evicts and the row disappears; close all removes all but HOME and active; opening a row navigates.

## 7. Verification

- [x] 7.1 Manual: open Perfetto + Memory + LayoutInspector + ReverseNet, observe memory/CPU while switching (background pause) and after closing each via task manager (disposal). Confirmed no "address in use" on Perfetto reopen, no ADB zombies after LayoutInspector close — PASS (user verified on device).
- [x] 7.2 Ran `:desktop-app:test` (AppNavigatorEvictionTest 8/8, AppNavigatorTest, ModuleTaskManagerOverlayTest 3/3, CommercialAppShellTest 14/14, HomeCustomizationTest 11/11) and `:perfetto-viewer:perfetto-ui-server:test` (7/7 incl. new port-free test) — all green, no regressions. (Build requires JDK 17 + ANDROID_HOME set.)
- [x] 7.3 `openspec validate add-module-task-manager` → valid.
