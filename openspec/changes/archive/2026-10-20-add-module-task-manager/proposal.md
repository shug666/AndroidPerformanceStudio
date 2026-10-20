## Why

The desktop shell never evicts a feature module once it has been opened. `AppNavigator.retainedDestinations` only grows (`AppDestination.kt:166-169`), so every visited profiler stays composed forever — hidden via `alpha = 0` but retaining its parsed traces, sqlite handles, ADB subprocesses, and background coroutines in memory. The app therefore "eats more memory the longer you use it," even though module initialization on the home screen is already correctly lazy. A mobile/tablet-style "recent tasks" manager — close individual modules, or all of them — is the natural fix, and the `retainedDestinations` list is already the data model for it.

## What Changes

- Add **hard eviction**: closing a module removes it from `AppNavigator.retainedDestinations`, so the `key(destination)` subtree leaves composition and Compose disposes it. Reopening returns to the module's initial state. (HOME remains non-evictable / pinned.)
- Add **default background pausing**: feature modules' `LaunchedEffect`/polling/ADB/`while(isActive)` work is gated on the page's `active` flag, so a retained-but-inactive module stops consuming CPU and device resources. Closing a module is not required to get this relief.
- **Standardize the release contract across all feature modules**: every module's `MainPage` registers a `DisposableEffect { onDispose { controller.close()/release() } }` so that eviction actually frees heavy resources. Today only Frame, Memory, Simpleperf, and MethodRecording implement this; Perfetto (incl. `PerfettoUiServer`), LayoutInspector (ADB + compose live sessions), ReverseNet (polling loop), Battery, GPU, Startup, Benchmark, and NetworkProfiler do not.
- Add a **module task manager UI**: a shell-level entry (navigation rail / command palette) opens an overlay listing `retainedDestinations` with per-item close buttons and "close all". Closing the active module navigates back to HOME and resets window maximization.
- Eviction clears the corresponding pending navigator request (`perfettoTraceFile`, `memoryImportFile`, `layoutArchiveRequest`, `simpleperfSessionRequest`, `methodRecordingTraceFile`, `inspectorCorrelationHint`, `sourceLocation`) so a reopened module does not re-trigger stale intents — extending the existing `clearPerfettoTrace()` precedent.

## Capabilities

### New Capabilities
- `module-task-manager`: Lifecycle and memory control for feature modules in the desktop shell — eviction of retained modules, default pausing of background work for inactive modules, a unified release contract per module, and a task-manager UI to view and close open modules.

### Modified Capabilities
<!-- No existing spec-level behavior changes; this introduces a new cross-cutting capability. -->

## Impact

- `desktop-app`: `AppNavigator` (evict API + request clearing), `DesktopAppMainPage` (retained-set changes drive disposal + active gating), `CommercialAppShell` (task manager entry), new task-manager overlay composable.
- Feature module `MainPage`s (Perfetto, LayoutInspector, ReverseNet, Battery, GPU, Startup, Benchmark, NetworkProfiler — plus audit of existing Frame/Memory/Simpleperf/MethodRecording): add/verify `DisposableEffect` release hooks and `active` gating on background work.
- Module controllers/`close()` paths: ensure `close()`/`release()` stops UI servers, ADB subprocesses, polling coroutines, and sqlite connections. ReverseNet and LayoutInspector are the highest-risk.
- No Gradle/build-structure changes; all edits are in existing source. No persistence schema changes (eviction is in-memory UI state).
