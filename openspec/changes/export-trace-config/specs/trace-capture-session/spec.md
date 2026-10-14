## ADDED Requirements

### Requirement: Current trace configuration can be exported as a textproto file

The Configuration panel SHALL provide an "Export config" action that writes the current configuration to a `.txtpb` file. The exported content SHALL be the `PerfettoConfigTextBuilder.build()` output for the configuration the user would start a capture with (same `createCaptureConfig` assembly). A save dialog SHALL prompt for the destination, defaulting the filename to the selected template (e.g. `system-overview-config.txtpb`).

#### Scenario: export writes the generated textproto to a chosen file
- **WHEN** the user clicks "Export config" and chooses a destination file
- **THEN** the file content MUST equal `PerfettoConfigTextBuilder.build(config)` for the current configuration

#### Scenario: export uses a template-derived default filename
- **WHEN** the export save dialog opens with the System Overview template selected
- **THEN** the default filename MUST be `system-overview-config.txtpb`

#### Scenario: export reflects current settings including device SDK
- **WHEN** the user exports while a T+ device is connected and ATRACE is enabled
- **THEN** the exported textproto MUST contain `disable_generic_events: true`

#### Scenario: export without a connected device omits version-gated fields
- **WHEN** the user exports while no device is connected (androidSdk unknown)
- **THEN** the exported textproto MUST NOT contain `disable_generic_events`

#### Scenario: custom template exports the user-provided textproto verbatim
- **WHEN** the user exports with the Custom template and custom config text set
- **THEN** the exported file MUST equal the custom config text verbatim

#### Scenario: export failure surfaces an error
- **WHEN** writing the export file fails
- **THEN** an error message MUST be shown to the user
