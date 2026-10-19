# trace-capture-session

The user-facing capture control surface of the Perfetto Trace Analyzer capture page: how the start and stop actions are presented and how they map onto the capture state machine.

## Requirements

### Requirement: Capture start and stop are controlled by a single state-driven toggle button

The Configuration panel action row SHALL present a single capture toggle button (in addition to the separate "导出配置" and "在 Perfetto UI 中打开" actions) whose label and click action follow the current `PerfettoCaptureState`. The button SHALL show "开始抓取" and invoke start when a capture is not in progress, and SHALL show "停止抓取" and invoke stop when the capture is `Recording`. The button SHALL be disabled (showing "开始抓取") during `Preparing` and `Pulling`, since those phases cannot be interrupted. The existing status line and progress bar continue to communicate the in-progress phase.

#### Scenario: idle state shows an enabled start button
- **WHEN** the capture state is `Idle` and a device is selected with capabilities ready
- **THEN** the single capture button MUST display "开始抓取", be enabled, and clicking it MUST invoke `onStartCapture` with the current configuration

#### Scenario: recording state shows an enabled stop button
- **WHEN** the capture state is `Recording`
- **THEN** the single capture button MUST display "停止抓取", be enabled, and clicking it MUST invoke `onStopCapture`

#### Scenario: preparing state shows a disabled start button
- **WHEN** the capture state is `Preparing`
- **THEN** the single capture button MUST display "开始抓取" and be disabled (not clickable)

#### Scenario: pulling state shows a disabled start button
- **WHEN** the capture state is `Pulling`
- **THEN** the single capture button MUST display "开始抓取" and be disabled (not clickable)

#### Scenario: completed state returns to an enabled start button
- **WHEN** the capture state is `Completed`
- **THEN** the single capture button MUST display "开始抓取" and be enabled, so a new capture can begin

#### Scenario: failed state returns to an enabled start button
- **WHEN** the capture state is `Failed`
- **THEN** the single capture button MUST display "开始抓取" and be enabled, so the capture can be retried

### Requirement: The stop action is rendered with a destructive visual treatment

The capture toggle button SHALL render the "停止抓取" (stop) state using a destructive color treatment (error-colored border and text on the neutral fill) to distinguish it as the action that terminates an in-progress capture. All other states of the toggle button, and all other uses of the shared compact button component, SHALL be visually unchanged.

#### Scenario: stop button uses the error color
- **WHEN** the capture state is `Recording`
- **THEN** the "停止抓取" button MUST render with an error-colored border and text (distinct from the primary-colored start button and the neutral disabled states)

#### Scenario: start button keeps the primary treatment
- **WHEN** the capture state is `Idle` with a device selected
- **THEN** the "开始抓取" button MUST render with the primary fill (selected) treatment

### Requirement: The separate stop button is removed from the action row

The action row SHALL NOT render a second, separate "停止" button alongside the start button. Stop is reachable only through the single toggle button's `Recording` state.

#### Scenario: only one capture control button is rendered during recording
- **WHEN** the capture state is `Recording`
- **THEN** exactly one capture control button (the "停止抓取" toggle) MUST be present in the action row, not two

#### Scenario: only one capture control button is rendered when idle
- **WHEN** the capture state is `Idle`
- **THEN** exactly one capture control button (the "开始抓取" toggle) MUST be present in the action row, not two

### Requirement: Capture toggle labels are width-equal to avoid layout shift

The start and stop toggle labels SHALL have equal rendered width in each supported language so the button does not shift when toggling between start and stop. In Simplified Chinese both labels are four characters ("开始抓取" / "停止抓取"); in English both labels are the same length ("Start Capture" / "Stop Capture").

#### Scenario: Chinese start and stop labels are equal width
- **WHEN** the application language is Simplified Chinese
- **THEN** the start label "开始抓取" and the stop label "停止抓取" MUST both be four characters (equal width)

#### Scenario: English start and stop labels are equal width
- **WHEN** the application language is English
- **THEN** the start label "Start Capture" and the stop label "Stop Capture" MUST be the same length (equal width)
