## ADDED Requirements

### Requirement: Sys-stats poll intervals are configurable per probe

The `CPU_USAGE`, `CPU_FREQUENCY`, `KERNEL_MEMINFO`, and `VMSTAT` probes SHALL each accept a poll interval (milliseconds) that is emitted as the corresponding `*_period_ms` field in `sys_stats_config` (`stat_period_ms`, `cpufreq_period_ms`, `meminfo_period_ms`, `vmstat_period_ms` respectively). When no interval is configured for a probe, the builder SHALL fall back to the existing defaults (1000/250/1000/1000). Configured intervals SHALL be greater than 10ms.

#### Scenario: configured interval overrides default
- **WHEN** a capture config sets `pollIntervals[CPU_FREQUENCY] = 500` and `CPU_FREQUENCY` is enabled
- **THEN** the `sys_stats_config` block MUST contain `cpufreq_period_ms: 500`

#### Scenario: unconfigured interval falls back to default
- **WHEN** `CPU_USAGE` is enabled and `pollIntervals` is null or has no entry for `CPU_USAGE`
- **THEN** the `sys_stats_config` block MUST contain `stat_period_ms: 1000`

#### Scenario: interval below minimum is rejected
- **WHEN** a poll interval value of 10 or less is supplied
- **THEN** the UI MUST reject or coerce it to a value greater than 10ms

### Requirement: Atrace categories are selectable per capture

The `ATRACE` probe SHALL expose a fixed set of atrace categories for selection, matching the official perfetto UI `ATRACE_CATEGORIES` list (37 categories). The selected set, when provided, SHALL override the template preset and be emitted as `atrace_categories` entries in `ftrace_config`. When no category set is configured, the template preset SHALL be used (System Overview = the 24 `isDefault:true` categories, etc.). Categories unsupported by the device are silently ignored by perfetto (matching official behavior).

#### Scenario: selected categories override template preset
- **WHEN** `ATRACE` is enabled and `atraceCategories = {am, gfx, view}` is configured
- **THEN** the `ftrace_config` MUST contain exactly `atrace_categories: "am"`, `"gfx"`, `"view"` and no others

#### Scenario: unconfigured categories fall back to template preset
- **WHEN** `ATRACE` is enabled for System Overview and `atraceCategories` is null
- **THEN** the `ftrace_config` MUST contain the System Overview template preset (24 categories)

#### Scenario: switching templates resets selected categories
- **WHEN** the user switches trace templates
- **THEN** the atrace category selection MUST reset to the new template's preset

#### Scenario: selectable categories match the official fixed set
- **WHEN** the ATRACE probe category dialog is open
- **THEN** it MUST offer the 37 official atrace categories (not a device-specific subset)

### Requirement: Probe sub-parameters are edited in a dialog, not inline

Probes with sub-parameters (poll interval; atrace categories) SHALL expose them via a config button on the probe card that opens a dialog, rather than expanding inline in the data sources panel. The atrace category dialog SHALL lay categories out in a fixed 3-column grid (equal-width columns) with All/None/Default quick actions and a selection count. The dialog applies changes on confirm and discards on cancel; when closed the data sources panel stays compact.

#### Scenario: config button opens dialog only for probes with sub-parameters
- **WHEN** a probe with sub-parameters (e.g. ATRACE, CPU_USAGE) is enabled
- **THEN** a config button SHALL appear on its probe card, and no sub-parameter content SHALL render inline while the dialog is closed

#### Scenario: atrace dialog categories are laid out in three columns
- **WHEN** the atrace category dialog is open
- **THEN** the category rows MUST each contain at most 3 items with equal-width columns aligned by x position

#### Scenario: atrace dialog provides bulk actions
- **WHEN** the atrace category dialog is open
- **THEN** All, None, and Default quick actions SHALL be available alongside the per-category checkboxes

### Requirement: additional categories input is limited to ftrace events

The "Additional categories / events" text input SHALL only contribute ftrace events (entries containing `/`); atrace category contributions (entries without `/`) are removed because atrace categories are now selected via the ATRACE probe's category multi-select.

#### Scenario: additional input contributes only ftrace events
- **WHEN** the user enters `sched/sched_switch, am` in the additional input
- **THEN** `sched/sched_switch` MUST be added to ftrace events and `am` MUST NOT be added to atrace categories
