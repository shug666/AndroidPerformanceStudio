## Why

APS binds a fixed port (9001) for the Perfetto UI trace-serving endpoint and the native trace_processor engine, and that port is exclusive to a single running instance. When a second instance is launched (common during development, or after a crash leaves a stale process), it starts but every "Open in Perfetto UI" / "Open with native engine" action silently fails to bind 9001 — the user sees buttons that do nothing, with no explanation. The root cause is the absence of any single-instance guard: nothing tells the user another instance already owns the port.

## What Changes

- Add a single-instance guard at the application entry point (`Main.kt`): before starting the Compose window, the app acquires a lock on a dedicated, stable liveness token (a bound loopback socket on a fixed port distinct from 9001, held for the JVM's lifetime). If acquisition fails, a second instance is already running.
- When a second instance is detected, the app SHALL surface a clear message ("Another Android Performance Studio instance is already running. Please use the existing window.") and exit without starting the UI, instead of launching a broken second window.
- The liveness token port is fixed and documented (9002), dedicated solely to instance existence — it is never used for trace serving or any other business traffic, so it does not collide with the 9001 stop/start lifecycle.
- On normal JVM exit (window close, crash, kill), the OS releases the socket and the token frees automatically — no cleanup hooks or stale-lock self-heal are required.
- **BREAKING**: launching a second APS instance no longer opens a (broken) window; it shows a message and exits. Users who intentionally ran two instances side by side (rare, and already broken for trace viewing) must close the first.

## Capabilities

### New Capabilities
- `app-single-instance`: governs how Android Performance Studio enforces a single running instance via a liveness token, and how a second launch is rejected with a user-facing message.

### Modified Capabilities
_(无)_

## Impact

- `desktop-viewer/desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/Main.kt`: acquire liveness token before `application { }`; on failure, show a message dialog (or print + exit on headless) and return without composing the window.
- New small component for the liveness token (socket bind + release), likely in `desktop-app` or a shared module; unit-testable with a free-port variant.
- No changes to `PerfettoUiServer` / `NativeTraceProcessorServer` / port 9001 logic — the guard is upstream of and orthogonal to the trace-serving port.
- Build/packaging: no launcher-script changes (jpackage native launcher is opaque); the guard is purely in the JVM entry point.
- Tests: liveness token acquire/release, second-acquire-fails, message-on-second-instance.
