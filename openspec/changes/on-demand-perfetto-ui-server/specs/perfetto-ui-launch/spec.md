## ADDED Requirements

### Requirement: Opening a trace SHALL NOT start the local Perfetto UI service

The Trace Analyzer SHALL NOT start `PerfettoUiServer` (or any local HTTP service) and SHALL NOT launch a browser as a side effect of opening a trace, regardless of entry point (file open, recent session, capture completion, or external navigation into the page). Starting the local Perfetto UI service SHALL occur only as a result of an explicit user action (see Open in Perfetto UI / Open in local Perfetto UI).

#### Scenario: User opens a trace file via the File menu
- **WHEN** the user opens a `.pftrace` file through the File menu
- **THEN** the trace becomes active in the Trace Analyzer workspace, and no local HTTP service is started and no browser window is opened

#### Scenario: Capture completes
- **WHEN** a Perfetto capture completes and its trace is shown in the workspace
- **THEN** no local HTTP service is started and no browser window is opened

#### Scenario: External navigation with an initial trace
- **WHEN** the application navigates to the Trace Analyzer with an initial trace file (e.g. from another profiler destination)
- **THEN** the trace is activated without starting the local Perfetto UI service or launching a browser

### Requirement: Open in Perfetto UI SHALL load the trace through the official Perfetto UI

The "Open in Perfetto UI" action SHALL start the local trace-serving endpoint, then open the system browser at the official Perfetto UI with a `?url=` parameter pointing at the local trace endpoint (`https://ui.perfetto.dev/#!/?url=http://localhost:<port>/trace`). The trace endpoint SHALL serve the active trace bytes with `Access-Control-Allow-Origin: *` so the official UI can fetch them.

#### Scenario: Default open action
- **WHEN** the user invokes "Open in Perfetto UI" with an active trace
- **THEN** the local trace-serving endpoint starts (or is reused if already running), and the system browser opens `https://ui.perfetto.dev/#!/?url=http://localhost:<actual-port>/trace` where `<actual-port>` is the port the endpoint actually bound

#### Scenario: Official UI fetches the trace
- **WHEN** the official Perfetto UI requests the trace from the local endpoint
- **THEN** the endpoint responds with the active trace bytes, CORS header `Access-Control-Allow-Origin: *`, and content type `application/octet-stream`

#### Scenario: No trace is active
- **WHEN** the user invokes "Open in Perfetto UI" without an active trace
- **THEN** the action reports an error and does not start the endpoint or open a browser

### Requirement: Local Perfetto UI assets SHALL be served only via explicit menu action

A menu item ("Open in local Perfetto UI") SHALL serve locally available Perfetto UI assets and open the browser at the locally hosted UI (`http://localhost:<port>/#!/?url=http://localhost:<port>/trace`). The menu item SHALL be enabled only when local UI assets are discoverable (`PerfettoUiServer.tryFindUiAssetsDir()` returns a directory containing `index.html`); otherwise it SHALL be disabled.

#### Scenario: Local UI assets available
- **WHEN** local UI assets are discoverable and the user invokes "Open in local Perfetto UI"
- **THEN** the local service starts serving both the UI assets and the `/trace` endpoint, and the browser opens the locally hosted UI pointed at the local trace endpoint

#### Scenario: Local UI assets not available
- **WHEN** local UI assets are not discoverable
- **THEN** the "Open in local Perfetto UI" menu item is disabled and cannot start the service

### Requirement: Trace endpoint SHALL serve on port 9001

The local trace-serving endpoint SHALL bind `127.0.0.1:9001`. This port is the only origin the official ui.perfetto.dev Content-Security-Policy allows fetching from (the same port record_android_trace reuses). Browser URLs SHALL reference `http://127.0.0.1:9001/trace_file.perfetto-trace` with `referrer=record_android_trace`.

#### Scenario: Port 9001 is free
- **WHEN** the service starts and port 9001 is not in use
- **THEN** the endpoint binds `127.0.0.1:9001` and the browser URL references `http://127.0.0.1:9001/trace_file.perfetto-trace`

#### Scenario: Port 9001 is occupied
- **WHEN** the service starts and binding port 9001 fails
- **THEN** the start fails with a `SERVER_START_FAILED` error surfaced to the user; the service MUST NOT fall back to another port, because the official UI cannot fetch from any other origin

### Requirement: The embedded fallback page SHALL be removed

The service SHALL NOT provide the iframe embed fallback page (postMessage-based trace injection into `ui.perfetto.dev`, including `mode=embedded` and zoom postMessage logic). Requests to the service root in trace-only mode SHALL NOT serve an embed page.

#### Scenario: Root request in trace-only mode
- **WHEN** the service is running in trace-only mode (official UI flow) and a client requests the service root
- **THEN** the service does not serve an embed page

### Requirement: Trace diagnostics SHALL NOT depend on the Perfetto UI service

Trace diagnostics SHALL continue to resolve and launch `trace_processor_shell` on demand when a diagnostic query runs, independent of whether the Perfetto UI service is running.

#### Scenario: Diagnostic query without the UI service
- **WHEN** the user runs a trace diagnostic query while the Perfetto UI service has never been started
- **THEN** the diagnostic executes successfully via `trace_processor_shell` without requiring the Perfetto UI service
