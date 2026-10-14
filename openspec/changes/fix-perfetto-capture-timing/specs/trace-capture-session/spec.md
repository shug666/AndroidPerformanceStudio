## MODIFIED Requirements

### Requirement: Trace capture produces a non-empty trace file

A completed trace capture SHALL produce a non-empty Perfetto trace file. The capture SHALL run perfetto in the foreground (no `--background-wait`), so perfetto writes the trace and exits naturally after `duration_ms` elapses; the file is pulled only after the perfetto process has exited, guaranteeing the file is complete.

#### Scenario: default-duration capture produces non-empty trace
- **WHEN** a System Overview capture with the default 10s duration completes without interruption
- **THEN** the pulled trace file MUST be non-empty and parseable

#### Scenario: capture command runs perfetto in foreground
- **WHEN** a capture starts
- **THEN** the device command MUST be `cat <config> | perfetto -c - --txt -o <trace>` (no `--background-wait`), matching the official capture command

#### Scenario: shell timeout covers the capture duration
- **WHEN** a capture with duration D seconds starts
- **THEN** the adb shell call timeout MUST be at least D seconds plus a write-out margin (not the fixed 60s), so long-duration captures do not time out

### Requirement: Capture can be stopped before duration elapses

A user-initiated Stop or cancellation SHALL interrupt an in-progress capture and still recover any partial trace data perfetto had flushed before exiting. Interruption SHALL be driven by cancelling the blocked adb shell call (not by `kill -TERM` on a background perfetto PID).

#### Scenario: user stops a running capture
- **WHEN** the user stops a capture before its duration elapses
- **THEN** the blocked perfetto shell call is cancelled and any partial trace perfetto flushed is pulled and reported

#### Scenario: cancellation does not rely on background PID polling
- **WHEN** a capture is stopped or cancelled
- **THEN** the implementation MUST NOT use `kill -0`/`kill -TERM` on a background perfetto PID to decide completion
