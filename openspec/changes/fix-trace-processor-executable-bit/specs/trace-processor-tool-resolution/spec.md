## ADDED Requirements

### Requirement: Packaged Trace Processor is executable in native distributions

The Trace Processor binary packaged into native distributions (`deb`, `rpm`, and the shared app-image that feeds `dmg`/`pkg`/`msi`/`exe`) SHALL be a regular file with the executable bit set, so it can be launched by the host process runner without an out-of-band `chmod`. Because the Compose packaging plugin strips POSIX modes when it copies resources (`File.copyTo`) and `createDistributable` builds the app-image and the package in a single jpackage call (leaving no Gradle hook between image creation and packaging), the build SHALL guarantee the executable bit by repacking the assembled package after jpackage finishes: extracting the payload, setting the executable bit on the Trace Processor, and repacking. The repack SHALL be a build-time step on the packaging tasks (`createDistributable`/`createReleaseDistributable`) and SHALL fail the build if the published payload's Trace Processor is not executable.

#### Scenario: deb-installed binary is executable
- **WHEN** a user installs the Linux `.deb` distribution and inspects `<install-root>/lib/app/resources/perfetto-tools/trace_processor_shell`
- **THEN** the file MUST be a regular file with an executable mode (at least `0755` for the owner), and `Files.isExecutable` SHALL return true

#### Scenario: rpm-installed binary is executable
- **WHEN** a user installs the Linux `.rpm` distribution and inspects the installed `trace_processor_shell`
- **THEN** the file MUST be a regular file with an executable mode (at least `0755` for the owner)

#### Scenario: build fails if the published binary is not executable
- **WHEN** the repack step cannot make the packaged Trace Processor executable in the published artifact
- **THEN** the build MUST fail rather than ship a non-executable Trace Processor

### Requirement: Resolver falls back to the installed copy when the packaged copy is not executable

When the packaged Trace Processor exists as a regular file whose SHA-256 matches the pinned manifest but is not executable, `TraceProcessorToolResolver` SHALL fall back to the user-installed copy (`~/.android-performance-studio/tools/perfetto/<pinned-version>/trace_processor_shell`) and use it if that copy is executable and checksum-valid, rather than failing immediately. This guarantees that a manual `scripts/install-trace-processor.sh` run can recover a broken packaged install. The resolver SHALL still fail with a clear error only when no usable (executable, checksum-valid) copy exists in either location.

#### Scenario: packaged copy present but not executable, installed copy usable
- **WHEN** the packaged `trace_processor_shell` is a regular file with the pinned SHA-256 but `Files.isExecutable` is false, and the user-installed copy is an executable regular file with the pinned SHA-256
- **THEN** the resolver MUST return the installed copy as the resolved tool and MUST NOT surface a `TRACE_PROCESSOR_NOT_EXECUTABLE` error

#### Scenario: packaged copy absent, installed copy usable
- **WHEN** the packaged `trace_processor_shell` does not exist and the user-installed copy is an executable regular file with the pinned SHA-256
- **THEN** the resolver MUST return the installed copy as the resolved tool (existing behavior preserved)

### Requirement: Resolver self-heals a non-executable packaged copy when no installed copy exists

When the packaged Trace Processor is a regular file whose SHA-256 matches the pinned manifest but is not executable, and no executable checksum-valid installed copy exists, the resolver SHALL recover out-of-the-box by copying the packaged binary into a user-writable cache location (`~/.android-performance-studio/tools/perfetto/<pinned-version>/trace_processor_shell`), setting the executable bit on the copy, and resolving the copy — without requiring the user to run `scripts/install-trace-processor.sh`. This self-heal SHALL only engage when the packaged bytes are checksum-valid (so a genuinely wrong packaged binary still surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH`), and SHALL preserve the configured-override precedence. The self-heal copy becomes the user-installed copy for subsequent resolutions.

#### Scenario: packaged copy not executable and no installed copy, self-heal succeeds
- **WHEN** the packaged `trace_processor_shell` is a regular file with the pinned SHA-256 but not executable, and no executable checksum-valid installed copy exists, and the user-writable cache location is writable
- **THEN** the resolver MUST copy the packaged binary to the cache location, set the executable bit, and return the copy as the resolved tool, and MUST NOT surface an error

#### Scenario: self-heal does not mask a wrong-checksum packaged binary
- **WHEN** the packaged `trace_processor_shell` has a SHA-256 that does not match the pinned manifest
- **THEN** the resolver MUST surface `TRACE_PROCESSOR_CHECKSUM_MISMATCH` and MUST NOT self-heal or fall back

#### Scenario: self-heal yields to the configured override
- **WHEN** `PERFETTO_TRACE_PROCESSOR_PATH` is set to a valid binary and the packaged copy is not executable
- **THEN** the resolver MUST return the override and MUST NOT self-heal

### Requirement: Configured override remains the highest-priority resolution path

A Trace Processor path supplied via the `androidperformancestudio.traceProcessorPath` system property or the `PERFETTO_TRACE_PROCESSOR_PATH` environment variable SHALL take precedence over both the packaged and user-installed copies. The override SHALL be probed with `--version` and accepted only if it reports the pinned Trace Processor version; otherwise the resolver SHALL fail with `TRACE_PROCESSOR_OVERRIDE_INVALID` / `TRACE_PROCESSOR_INCOMPATIBLE`. The new executable-bit fallback and self-heal SHALL NOT alter this precedence.

#### Scenario: override set and valid
- **WHEN** `PERFETTO_TRACE_PROCESSOR_PATH` points to an executable binary reporting the pinned version
- **THEN** the resolver MUST return that binary and MUST NOT consult the packaged or installed copies
