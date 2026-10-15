## MODIFIED Requirements

### Requirement: System Overview default omits process memory polling

The System Overview template's default probe selection SHALL NOT include `PROCESS_MEMORY_POLL`, so the generated `linux.process_stats` data source contains only `scan_all_processes_on_start` (and `record_thread_names`), without `proc_stats_poll_ms` — matching the official Default preset. Users may still manually enable `PROCESS_MEMORY_POLL` to turn on polling.

#### Scenario: System Overview default omits proc_stats_poll_ms
- **WHEN** a trace config is built for the System Overview template with default probes
- **THEN** the `process_stats_config` block MUST NOT contain `proc_stats_poll_ms`

#### Scenario: manually enabling poll still works
- **WHEN** `PROCESS_MEMORY_POLL` is enabled (manually)
- **THEN** the `process_stats_config` block MUST contain `proc_stats_poll_ms: 1000`

## ADDED Requirements

### Requirement: Android log buffer selection

The `ANDROID_LOG` probe SHALL expose the set of Android log buffers for selection, matching the official `AndroidLogId` values (Main, Radio, Events, System, Crash, Stats, Security, Kernel). When no buffer set is configured, the `android.log` data source SHALL be emitted with an empty `android_log_config` (all buffers, matching official default). When a non-empty set is configured, it SHALL be emitted as `log_ids` entries restricting capture to the selected buffers.

#### Scenario: unconfigured buffers capture all (default)
- **WHEN** `ANDROID_LOG` is enabled and `logBuffers` is null or empty
- **THEN** the generated config MUST contain `name: "android.log"` with no `log_ids` entries (all buffers captured)

#### Scenario: selected buffers restrict capture
- **WHEN** `ANDROID_LOG` is enabled and `logBuffers = {LID_CRASH, LID_SYSTEM}`
- **THEN** the `android_log_config` MUST contain `log_ids: LID_CRASH` and `log_ids: LID_SYSTEM` and no others

#### Scenario: switching templates resets log buffer selection
- **WHEN** the user switches trace templates
- **THEN** the log buffer selection MUST reset to empty (all buffers)

#### Scenario: log buffer dialog offers the official set
- **WHEN** the ANDROID_LOG probe log buffer dialog is open
- **THEN** it MUST offer the 8 official AndroidLogId buffers
