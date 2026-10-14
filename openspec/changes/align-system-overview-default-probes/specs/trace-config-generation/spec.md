## ADDED Requirements

### Requirement: System Overview enables android.log by default

The System Overview template's default probe selection SHALL include the `ANDROID_LOG` probe, so the generated trace config contains an `android.log` data source block.

#### Scenario: android.log present in System Overview config
- **WHEN** a trace config is built for the System Overview template with default probes
- **THEN** the generated text-proto MUST contain a `data_sources` entry with `name: "android.log"`

#### Scenario: ANDROID_LOG checkbox checked by default
- **WHEN** the Trace Analyzer capture page initializes with the System Overview template
- **THEN** the `ANDROID_LOG` probe MUST be in the `enabledProbes` set

### Requirement: linux.sys_stats emits stat_counters

When `linux.sys_stats` is generated, the config MUST include `stat_counters: STAT_CPU_TIMES` when CPU usage is enabled, and `stat_counters: STAT_FORK_COUNT` whenever the sys_stats data source is present.

#### Scenario: CPU usage enables STAT_CPU_TIMES
- **WHEN** the `CPU_USAGE` probe is enabled and `linux.sys_stats` is emitted
- **THEN** the `sys_stats_config` block MUST contain `stat_counters: STAT_CPU_TIMES`

#### Scenario: fork count always present with sys_stats
- **WHEN** any sys_stats-producing probe is enabled
- **THEN** the `sys_stats_config` block MUST contain `stat_counters: STAT_FORK_COUNT`

### Requirement: System Overview atrace categories match official Default

The System Overview template SHALL emit all 24 official Default atrace categories: `aidl, am, binder_driver, camera, dalvik, disk, freq, gfx, hal, idle, input, memory, memreclaim, network, power, res, sched, ss, sync, thermal, view, webview, wm, workq`. Other templates SHALL retain their existing atrace category sets.

#### Scenario: System Overview atrace categories
- **WHEN** a trace config is built for the System Overview template with `ATRACE` enabled
- **THEN** the `ftrace_config` block MUST contain exactly the 24 listed `atrace_categories` entries (after dedup)

#### Scenario: Other templates unchanged
- **WHEN** a trace config is built for APP_PERFORMANCE, GFX_PIPELINE, INPUT_LATENCY, or MEMORY_PROFILE
- **THEN** the `atrace_categories` set MUST be unchanged from prior behavior

### Requirement: System Overview uses atrace_apps wildcard without target package

For the System Overview template, when no target package is selected and `ATRACE` is enabled, the config SHALL emit `atrace_apps: "*"`. When a target package is selected, the config SHALL emit the target package instead.

#### Scenario: no target package
- **WHEN** System Overview is selected, `ATRACE` is enabled, and no target package is set
- **THEN** the `ftrace_config` block MUST contain `atrace_apps: "*"`

#### Scenario: target package present
- **WHEN** System Overview is selected and a target package is set
- **THEN** the `atrace_apps` MUST contain the target package and MUST NOT contain `"*"`

### Requirement: CPU_FREQUENCY includes power wildcard and psci idle events

The `CPU_FREQUENCY` probe's ftrace event set SHALL include `power/*`, `power/psci_domain_idle_enter`, and `power/psci_domain_idle_exit`, in addition to its existing events. The `power/*` wildcard is expanded at runtime by traced_probes against the device's tracefs `events/power/` directory (graceful degradation: absent events are silently skipped, never a hard failure).

#### Scenario: psci events present
- **WHEN** the `CPU_FREQUENCY` probe is enabled
- **THEN** the `ftrace_config` block MUST contain `ftrace_events: "power/psci_domain_idle_enter"` and `ftrace_events: "power/psci_domain_idle_exit"`

#### Scenario: power wildcard present
- **WHEN** the `CPU_FREQUENCY` probe is enabled
- **THEN** the `ftrace_config` block MUST contain `ftrace_events: "power/*"`

### Requirement: linux.ftrace symbolizes ksyms and disables generic events on T+

When the `linux.ftrace` data source is emitted, the `ftrace_config` block SHALL unconditionally set `symbolize_ksyms: true` (a long-standing field safe on all versions). It SHALL set `disable_generic_events: true` only when the device Android SDK is >= 33 (Android T), because `disable_generic_events` is an Android-T-introduced field name that causes a hard parse failure under strict `--txt` on pre-T devices; on pre-T devices the field is omitted (default: generic events allowed). This combination with the `power/*` wildcard is safe: traced_probes keeps events with compile-time proto translations (including `power/psci_domain_idle_enter/exit`, defined in `power.proto`) and only drops generic events lacking a proto, which does not fail the capture.

#### Scenario: kernel symbols symbolized unconditionally
- **WHEN** `linux.ftrace` is emitted on any device
- **THEN** the `ftrace_config` block MUST contain `symbolize_ksyms: true`

#### Scenario: generic events disabled on Android T+
- **WHEN** any ftrace-producing probe is enabled and the device `androidSdk >= 33`
- **THEN** the `ftrace_config` block MUST contain `disable_generic_events: true`

#### Scenario: generic events field omitted pre-T to avoid hard failure
- **WHEN** any ftrace-producing probe is enabled and the device `androidSdk < 33` (or unknown)
- **THEN** the `ftrace_config` block MUST NOT contain `disable_generic_events`

#### Scenario: disable_generic_events and power wildcard coexist on T+
- **WHEN** `CPU_FREQUENCY` (providing `power/*` and psci events) is enabled and `androidSdk >= 33`
- **THEN** the `ftrace_config` block MUST contain both `disable_generic_events: true` and `ftrace_events: "power/*"`, matching the official Default configuration

### Requirement: Trace config omits flush_period_ms

Generated trace configs for non-custom templates SHALL NOT emit `flush_period_ms`. Perfetto handles periodic flushing automatically; manual high-frequency flushing is discouraged by the official proto spec.

#### Scenario: no flush period in generated config
- **WHEN** a trace config is built for any non-custom template
- **THEN** the generated text-proto MUST NOT contain `flush_period_ms`


### Requirement: Process memory polling is an independent toggle

A `PROCESS_MEMORY_POLL` probe SHALL exist in the Memory group. When enabled, the `linux.process_stats` data source MUST emit `proc_stats_poll_ms: 1000`. When only `PROCESS_STATS` is enabled (without `PROCESS_MEMORY_POLL`), the `linux.process_stats` data source MUST emit `scan_all_processes_on_start: true` and `record_thread_names: true` but MUST NOT emit `proc_stats_poll_ms`.

#### Scenario: poll probe enables polling
- **WHEN** `PROCESS_MEMORY_POLL` is enabled
- **THEN** the `process_stats_config` block MUST contain `proc_stats_poll_ms: 1000`

#### Scenario: process stats without poll
- **WHEN** `PROCESS_STATS` is enabled and `PROCESS_MEMORY_POLL` is not enabled
- **THEN** the `process_stats_config` block MUST NOT contain `proc_stats_poll_ms`

#### Scenario: System Overview defaults include poll
- **WHEN** the capture page initializes with System Overview
- **THEN** `PROCESS_MEMORY_POLL` MUST be in the `enabledProbes` set

### Requirement: Buffer fill policy is configurable

A `PerfettoBufferPolicy` enum (`RING_BUFFER`, `DISCARD`) SHALL be a capture-level setting. The generated config MUST emit `fill_policy` matching the selected policy. All templates SHALL default to `RING_BUFFER` (preserving current behavior and avoiding DISCARD tail-truncation risk at the default 32MB buffer); DISCARD is offered as an opt-in for short complete-snapshot captures. Switching templates SHALL NOT reset the buffer policy.

#### Scenario: System Overview defaults to RING_BUFFER
- **WHEN** the capture page initializes with System Overview
- **THEN** the buffer policy state MUST be RING_BUFFER and the generated config MUST contain `fill_policy: RING_BUFFER`

#### Scenario: all templates default to RING_BUFFER
- **WHEN** a trace config is built for any template with default settings
- **THEN** the generated config MUST contain `fill_policy: RING_BUFFER`

#### Scenario: user can switch to DISCARD
- **WHEN** the user selects DISCARD for any template capture
- **THEN** the generated config MUST contain `fill_policy: DISCARD`

#### Scenario: switching templates does not reset policy
- **WHEN** the user sets DISCARD then switches from System Overview to APP_PERFORMANCE
- **THEN** the buffer policy MUST remain DISCARD

#### Scenario: composer default preserves prior behavior
- **WHEN** `PerfettoCaptureDocument` is constructed without specifying a buffer policy
- **THEN** the composer MUST emit `fill_policy: RING_BUFFER`
