## Context

The desktop shell (`desktop-app`) composes feature modules by iterating `AppNavigator.retainedDestinations`, wrapping each in `key(destination) { ... Box(alpha/visibility) ... }` (`DesktopAppMainPage.kt:211-488`). Inactive modules are hidden visually but remain fully composed. `AppNavigator.activate()` only appends to `retainedDestinations` and never removes (`AppDestination.kt:166-169`), so the set only grows.

Module initialization on the home screen is already lazy: cold start retains only `HOME`, and `AppHomePage` gates its own `LaunchedEffect` work on `active` (`AppHomePage.kt:202-216`). The memory problem is therefore not startup-time eager init, but the absence of any teardown for visited modules — compounded by inconsistent release contracts across modules.

Release-contract audit (current state):

| Module | `DisposableEffect{onDispose(close)}` | Notes |
|---|---|---|
| FrameProfiler | ✅ | `FrameProfilerMainPage:88`, cancels subscriptions |
| MemoryProfiler | ✅ | `MemoryProfilerMainPage:99` |
| Simpleperf | ✅ | `SimpleperfMainPage:101,103` (two controllers) |
| MethodRecording | ✅ | `MethodRecordingMainPage:78` |
| Perfetto | ❌ | Holds `PerfettoUiServer` (HTTP server), `PerfettoCaptureSession`, `TraceSessionStore` |
| LayoutInspector | ❌ | `LiveDeviceClient`, `AdbProcessRunner`, `ComposeLiveSession`, `InspectorStore` (4030-line page) |
| ReverseNet | ❌ | `while(isActive)` polling loop keyed on `controller`, not on page `active` |
| Battery | ❌ | `BatteryProfilerController`, no close hook |
| GPU | ❌ | `AgiController` + ADB reader, `LaunchedEffect(Unit)` unguarded |
| Startup | ❌ | `StartupProfilerController` |
| Benchmark | ❌ | sqlite `BenchmarkReportIndexStore` left open |
| NetworkProfiler | ❌ | `SqliteNetworkStore` opened per-op via `.use{}`; `pollJob` cancellable |

Existing precedent for clearing cross-module intent on leave: `DesktopAppMainPage.kt:165-172` calls `navigator.clearPerfettoTrace()` when leaving PERFETTO.

## Goals / Non-Goals

**Goals:**
- A user can close any open module (single or all) and have its resources actually freed (composed subtree disposed, `onDispose` releases controllers/stores/subprocesses/polling).
- An inactive-but-retained module defaults to paused background work — no CPU polling, no ADB traffic, no live capture — without needing to be closed.
- One consistent release contract every feature module follows, enforced by review/tests.
- A simple shell-level task manager UI to view open modules and close them.

**Non-Goals:**
- Soft eviction (retain light navigation state, release only heavy data). Hard eviction only; reopening returns to initial state. Deferred to a future change.
- Per-module memory-in-bytes accounting. No accurate JVM object accounting; the UI shows open/closed state and self-reported status, not MB figures.
- Card thumbnails / swipe gestures. v1 is a list with close buttons.
- Persisting the retained set across app restarts. Eviction is in-memory UI state; restart always begins at HOME.
- Changes to Gradle module structure.

## Decisions

### Decision 1: Eviction = remove from `retainedDestinations`, let Compose dispose
Closing a module removes it from `AppNavigator.retainedDestinations`. On next composition the `key(destination)` subtree for that entry leaves composition, triggering every `DisposableEffect.onDispose` in that subtree. No shell-level `release()` call is needed — the framework is the disposal engine.

- **Why:** reuses the exact mechanism already wiring retained pages; no new lifecycle abstraction. Garbage of the subtree becomes unreachable and collectable; native resources are freed by each module's `onDispose`.
- **Alternative considered:** a shell-managed `ModuleSession.release()` registry where eviction explicitly calls `release()` on each module. Rejected — duplicates Compose's disposal, forces every module to register into a registry, more coupling for no benefit since `onDispose` already fires on subtree removal.
- **Constraint:** depends on every module actually having an `onDispose` that frees heavy resources (Decision 3). Eviction without the contract only helps GC, not native/server resources.

### Decision 2: Inactive modules pause background work via `active` gating
`DesktopAppMainPage` already passes `active` (and Frame/Perfetto/BatteryNetwork already take `isActive`). Modules' long-running `LaunchedEffect`s / `while(isActive)` loops / device-refresh work SHALL be gated on the page `active` flag, not on `Unit`/`controller`. ReverseNet's `while(isActive)` (coroutine `isActive`, not page `active`) is the key fix.

- **Why:** gives immediate relief even before the user closes anything; the single biggest behavioral win. Decouples "memory pressure" from "user must close modules."
- **Alternative considered:** pause everything centrally via `DisposableEffect` that cancels a per-module scope when `active=false`. Rejected — each module already owns its coroutines; re-keying `LaunchedEffect(active)` is localized and idiomatic.
- **Edge:** a module mid-capture (Perfetto/Frame online capture, ReverseNet active session) MUST NOT be silently paused if it would corrupt a capture. Gating pauses *discovery/refresh/polling*, not an in-progress capture; capture continues or the module opts out of auto-pause while capturing (see Risks).

### Decision 3: Uniform release contract via `DisposableEffect { onDispose { close() } }`
Every feature module's `MainPage` creates its controller(s) in `remember` and registers `DisposableEffect(controller) { onDispose(controller::close) }`. Modules without a single controller (Benchmark, NetworkProfiler) group their closeable resources and dispose them together. This is the existing Frame/Memory/Simpleperf/MethodRecording pattern, applied to the 8 missing modules.

- **Why:** minimal, local, no new interface to design or adopt; matches the 4 modules already doing it. Compose invokes `onDispose` exactly when eviction removes the subtree.
- **Alternative considered:** introduce a `MemoryReleasable` marker interface implemented by all controllers and invoked by the shell. Rejected for v1 — adds a cross-module contract type and adoption churn for no extra power over `onDispose`.
- **Each module's `close()` MUST release:** subprocess handles (ADB/AGI), HTTP/UI servers (PerfettoUiServer stop + port release), sqlite connections, polling coroutines (cancellation via controller-owned scope), live compose inspection sessions, large parsed artifacts (drop references to enable GC; for mmap'd traces, close the channel/reader).

### Decision 4: Navigator gains `evict(destination)` and request-clearing
`AppNavigator.evict(destination)`:
- refuses to evict `HOME` (pinned) and the currently-active destination (caller must navigate to HOME first, or `evict` of the active module auto-navigates to HOME — see Decision 5).
- removes from `retainedDestinations`.
- clears any pending request the shell routes to that module, extending `clearPerfettoTrace()`: clears `perfettoTraceFile` only when evicting PERFETTO, `memoryImportFile` for MEMORY_PROFILER, `layoutArchiveRequest`+`inspectorCorrelationHint` for LAYOUT_INSPECTOR, `simpleperfSessionRequest` for SIMPLEPERF, `methodRecordingTraceFile` for METHOD_RECORDING, `sourceLocation` for SOURCE_WORKSPACES.
- exposes `evictAll()` that evicts every non-HOME, non-active destination.

- **Why:** prevents a reopened module from re-triggering a stale intent (e.g. re-importing a trace the user just closed the viewer for). Mirrors the existing `clearPerfettoTrace()` precedent, generalized.
- **Alternative considered:** clear all requests on any eviction unconditionally. Rejected — clearing a request for a *different* module could drop a still-valid cross-module handoff (e.g. evicting Battery should not clear a pending Perfetto trace from a Frame correlation).

### Decision 5: Closing the active module navigates to HOME
If the user closes the currently-active module, `evict` first sets `destination = HOME` (and `shouldMaximizeWindow()` already leaves HOME unmaximized), then removes the destination. The window-maximize `LaunchedEffect(navigator.destination)` (`DesktopAppMainPage.kt:165-172`) already handles the placement reset. Closing a non-active module does not change the current destination.

### Decision 6: Task manager UI is a shell overlay, list-only
A new entry in `CommercialAppShell` (navigation rail button + command-palette item) opens an overlay listing `retainedDestinations` (excluding the pinned HOME, or showing HOME as a non-closeable header). Each row: module label + close button. A "close all" action. Selection of a row navigates to that module (existing `navigator.open`). The overlay reuses `AlertDialog`/`Surface` styling consistent with `StudioCommandPalette`.

- **Why:** lowest-effort path to the capability; the data model (`retainedDestinations`) already exists. List is sufficient for memory control; thumbnails add cost without new leverage.
- **Alternative considered:** an always-visible "open modules" strip in the status bar. Rejected — consumes permanent chrome space; an on-demand overlay is enough for v1.

## Risks / Trade-offs

- **[In-progress capture corrupted by pause/dispose]** → When a module is actively capturing (Perfetto/Frame online capture, ReverseNet active relay, Battery polling run), pausing background work or evicting mid-capture can lose data or leave a device in a bad state. Mitigation: while a module reports "capturing", the task manager disables its close button (and the module opts out of auto-pause), and eviction/closing first calls `controller.stopOnlineCapture()`/equivalent before disposal. Modules MUST make `close()` safe to call mid-capture.
- **[PerfettoUiServer port not released]** → A `PerfettoUiServer` left running holds a port and a background thread. Mitigation: `PerfettoMainPage` gets `DisposableEffect { onDispose { uiServer.stop() } }`; verify port actually frees (test: evict Perfetto, reopen, server rebinds without "address in use").
- **[ReverseNet relay leaks the tunnel]** → ReverseNet maintains a live reverse-tethering session; eviction must tear it down cleanly, not just cancel coroutines. Mitigation: `SessionController.close()` MUST stop the relay and release the network interface / adb reverse forwarding; test evicting an active session.
- **[LayoutInspector ADB subprocess zombies]** → 4030-line page with `LiveDeviceClient`/`AdbProcessRunner`/`ComposeLiveSession`; disposal must kill subprocesses and detach compose injection. Mitigation: audit and ensure each closeable is registered in `onDispose`; test evicting after a compose inspection session.
- **[Stale cross-module handoff dropped]** → Evicting a module clears its own pending request, but a *source* module may have already set a handoff for a *target* that the user then evicts (e.g. Frame set a Perfetto correlation, user evicts Perfetto). Mitigation: clearing is per-target-module (Decision 4), so evicting Perfetto clears only Perfetto's trace intent; if the user reopens Perfetto the Frame correlation is gone — acceptable for v1 (documented), soft-eviction would preserve it.
- **[Eviction tests are hard to assert on memory]** → JVM heap assertions are flaky. Mitigation: test observable proxies — after eviction, `retainedDestinations` no longer contains the destination; the module's `close()` was invoked (spy/counter); `PerfettoUiServer` port is free; ADB subprocess count drops. Do not assert on `Runtime.freeMemory()`.
- **[Hidden-but-retained modules still reference large parsed data]** → Background pause (Decision 2) stops CPU/network, not held memory. Mitigation: this is acceptable and expected; closing via the task manager (Decision 1) is the memory-release path. Soft-eviction is a future non-goal that would address retained-but-heavy without full close.

## Open Questions

- Should `SETTINGS` be evictable? It holds no heavy resources; leaving it always-retained is harmless. Proposal: evictable but low priority (close button present, no functional risk). Confirm in tasks.
- Does any module's `controller.close()` currently block on I/O (e.g. joining an ADB process)? If so, `onDispose` runs on the composition dispatcher and could jank. Needs a quick audit during implementation; move blocking cleanup to a launched IO coroutine if found.
- Naming/entry point for the task manager in the nav rail and its localized string/resource — to be added to `desktop-app` resources during implementation.
