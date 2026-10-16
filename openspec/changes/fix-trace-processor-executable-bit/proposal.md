## Why

Trace Analyzer diagnostics that depend on the native Trace Processor (CPU scheduling hotspots, `sched_switch` timeline, `sched_waking` events, run-queue pressure, main-thread blocking, CPU frequency distribution, CPU Contention, etc.) are unusable on Linux `.deb`/`.rpm` installs. The packaged `trace_processor_shell` binary lands on disk with mode `0644` (not executable), so `TraceProcessorToolResolver` rejects it with `Trace Processor is not executable` and every dependent diagnostic fails. The binary itself is correct (SHA-256 matches the pinned manifest) — only the executable bit is missing. Users who run `scripts/install-trace-processor.sh` to work around it get no relief, because the resolver short-circuits to the non-executable packaged copy before considering the user-installed fallback.

## What Changes

- Set `fileMode = 0b111101101` (`0755`) on the `perfetto-tools` `CopySpec` in `prepareProfilerAppResources`, so the packaged Trace Processor is executable from the moment it enters the app-resources directory — before Gradle Sync, jpackage's resource copy, and `.deb`/`.rpm` packaging can strip the bit.
- Move the existing `setExecutable` chmod from the `createDistributable`/`createReleaseDistributable` `doLast` (which runs *after* jpackage has already frozen the `.deb` payload) to a point that precedes jpackage's resource consumption, as a defense-in-depth guarantee that the bit survives into the packaged artifact regardless of how resources are copied.
- Harden `TraceProcessorToolResolver.resolve()`: when the packaged binary exists and is a regular file whose SHA-256 matches the pinned manifest but is not executable, fall back to the user-installed copy (`~/.android-performance-studio/tools/perfetto/<version>/...`) instead of failing outright, so a manual install can recover a broken packaged install.

## Capabilities

### New Capabilities
- `trace-processor-tool-resolution`: Locating, validating (version + SHA-256), and falling back between the packaged and user-installed Trace Processor binary so dependent diagnostics can run.

### Modified Capabilities
<!-- None — no existing spec covers Trace Processor resolution. -->

## Impact

- **Build**: `desktop-viewer/desktop-app/build.gradle.kts` — `prepareProfilerAppResources` Sync `CopySpec` gains `fileMode`; the post-packaging `setExecutable` hook is retimed. Affects every native distribution target (`Deb`, `Rpm`, and transitively `Dmg`/`Pkg`/`Msi`/`Exe` whose app-images share the same resource copy path).
- **Runtime**: `desktop-viewer/platform-perfetto/platform-perfetto/src/main/kotlin/com/androidperformancestudio/platform/perfetto/TraceProcessorToolResolver.kt` — `resolve()` and `verifiedPinned` gain a fallback path.
- **Tests**: `TraceProcessorToolResolverTest` — new cases for the executable-bit fallback; `build.gradle.kts` packaging checks (`PerfettoFeatureSchemaCompatibilityTest`, `SharedCoreModulesContractTest`) reviewed for the retimed chmod.
- **No API/dependency changes.** No impact on trace capture or query schemas — the pinned version (`v57.2`) and checksums are unchanged.
