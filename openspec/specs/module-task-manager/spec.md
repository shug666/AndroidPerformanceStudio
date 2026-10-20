## ADDED Requirements

### Requirement: Module eviction removes a module from the shell
The shell SHALL allow closing any open feature module. Closing a module SHALL remove it from `AppNavigator.retainedDestinations`, causing the module's composed subtree to leave composition and its `DisposableEffect` disposal hooks to run. Reopening a closed module SHALL start from the module's initial state, with no retained data from the prior session.

#### Scenario: Closing a non-active module
- **WHEN** the user closes a module that is currently retained but not the active destination
- **THEN** the module is removed from `retainedDestinations`
- **AND** the active destination is unchanged
- **AND** the module's `onDispose` release hooks run

#### Scenario: Closing the active module returns to home
- **WHEN** the user closes the module that is currently the active destination
- **THEN** the shell first navigates to HOME
- **AND** removes the closed module from `retainedDestinations`
- **AND** the window returns to the HOME (non-maximized) placement

#### Scenario: Reopening a closed module starts fresh
- **WHEN** a module was closed and the user opens it again
- **THEN** the module composes from its initial state
- **AND** it does not restore traces, captures, or selections from the closed session

#### Scenario: Home cannot be closed
- **WHEN** the task manager is shown
- **THEN** HOME SHALL not be closeable (no close affordance)
- **AND** an attempt to evict HOME SHALL be a no-op

### Requirement: Close all open modules
The shell SHALL provide a "close all" action that closes every open module except HOME and the currently active module.

#### Scenario: Close all
- **WHEN** the user triggers "close all"
- **THEN** every module in `retainedDestinations` except HOME and the active module is removed and disposed
- **AND** the active destination remains visible

### Requirement: Eviction clears the target module's pending navigator request
When a module is closed, the navigator SHALL clear any pending request routed specifically to that module, so reopening does not re-trigger a stale intent. Clearing SHALL be scoped to the evicted module only and SHALL NOT clear requests targeting other modules.

#### Scenario: Evicting Perfetto clears its trace intent
- **WHEN** Perfetto holds a pending `perfettoTraceFile` and the user closes Perfetto
- **THEN** `perfettoTraceFile`, `perfettoTraceNotice`, and `perfettoTimestampNanos` are cleared
- **AND** pending requests for other modules (if any) are untouched

#### Scenario: Evicting one module does not clear another's handoff
- **WHEN** a Frame-to-Perfetto correlation has set a Perfetto trace intent and the user closes Battery
- **THEN** the Perfetto trace intent is unchanged

### Requirement: Inactive modules pause background work by default
A feature module that is retained but not the active destination SHALL pause background work — including device discovery/refresh, polling loops, and live capture readiness checks — gated on the module's `active` flag. An in-progress capture/relay session SHALL NOT be silently paused if doing so would corrupt data.

#### Scenario: Inactive module stops polling
- **WHEN** a module with a `while(isActive)` polling loop is retained but not active
- **THEN** the polling loop does not execute until the module becomes active again

#### Scenario: Active capture is not interrupted by background pause
- **WHEN** a module is actively capturing and the user switches away (module becomes inactive)
- **THEN** the capture continues (or the module opts out of pause while capturing)
- **AND** no capture data is lost or corrupted by the pause

### Requirement: Every feature module registers a release hook
Every feature module's main page SHALL register a `DisposableEffect` whose `onDispose` releases the module's heavy resources, so that eviction actually frees memory and device resources. The release SHALL stop subprocesses (ADB/AGI), stop embedded servers, close sqlite connections, cancel polling coroutines, and drop large parsed artifacts.

#### Scenario: Disposal runs on eviction
- **WHEN** a module is closed
- **THEN** its controller's release method is invoked
- **AND** subprocesses it spawned are terminated
- **AND** sqlite connections it opened are closed
- **AND** any embedded server it started is stopped and its port freed

#### Scenario: Perfetto UI server is stopped on close
- **WHEN** Perfetto is closed after its UI server was started
- **THEN** the UI server is stopped and its port is released
- **AND** reopening Perfetto rebinds the server without an "address in use" error

### Requirement: Task manager UI lists open modules and allows closing them
The shell SHALL provide a task manager entry (navigation rail and command palette) that opens an overlay listing the modules currently in `retainedDestinations`. Each listable module SHALL have a close affordance. HOME SHALL be listed as non-closeable. Selecting a module row SHALL navigate to that module. The overlay SHALL provide a "close all" action.

#### Scenario: Opening the task manager
- **WHEN** the user opens the task manager from the navigation rail or command palette
- **THEN** an overlay lists every module in `retainedDestinations`
- **AND** each module except HOME shows a close button
- **AND** a "close all" action is available

#### Scenario: Closing a module from the task manager
- **WHEN** the user clicks a module's close button in the task manager
- **THEN** the module is evicted per the eviction requirement
- **AND** the overlay updates to remove that module

#### Scenario: Active module close is disabled while capturing
- **WHEN** a module reports an in-progress capture and the user opens the task manager
- **THEN** that module's close affordance is disabled (or warns) until the capture stops
