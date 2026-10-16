## Why

Trace Analyzer diagnostics that depend on the native Trace Processor (CPU scheduling hotspots, `sched_switch` timeline, `sched_waking` events, run-queue pressure, main-thread blocking, CPU frequency distribution, CPU Contention, etc.) are unusable on Linux `.deb`/`.rpm` installs. The packaged `trace_processor_shell` binary lands on disk with mode `0644` (not executable), so `TraceProcessorToolResolver` rejects it with `Trace Processor is not executable` and every dependent diagnostic fails. The binary itself is correct (SHA-256 matches the pinned manifest) — only the executable bit is missing. Users who run `scripts/install-trace-processor.sh` to work around it get no relief, because the resolver short-circuits to the non-executable packaged copy before considering the user-installed fallback.

## What Changes

- Repack the published `.deb` after jpackage produces it: a `doLast` on `packageDeb`/`packageRpm`/`createDistributable`/`createReleaseDistributable` extracts the archive, sets the executable bit (`0755`) on the `trace_processor_shell`, and repacks it, then asserts the published payload is executable. This is necessary because the Compose packaging plugin's resource copy (`File.copyTo`) strips the POSIX executable bit, and there is no Gradle hook between app-image creation and packaging. (The originally-proposed `fileMode = 0b111101101` on the `CopySpec` was abandoned: it does not compile in this Gradle/Kotlin-DSL version and `copyTo` would strip the bit anyway — see design.)
- Chmod the assembled resource binary in `prepareProfilerAppResources.doLast` so the `:desktop-app:run` dev runtime (which reads that dir directly) works.
- Harden `TraceProcessorToolResolver.resolve()`: when the packaged binary is a regular file whose SHA-256 matches the pinned manifest but is not executable, fall back to an existing user-installed copy; if none exists, self-heal by copying the good packaged bytes into the user-writable cache (`~/.android-performance-studio/tools/perfetto/<version>/...`) and chmod-ing the copy — out-of-the-box, no user action. A wrong-checksum packaged binary still surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH`; the configured override keeps top precedence.

## Capabilities

### New Capabilities
- `trace-processor-tool-resolution`: Locating, validating (version + SHA-256), and falling back between the packaged and user-installed Trace Processor binary so dependent diagnostics can run.

### Modified Capabilities
<!-- None — no existing spec covers Trace Processor resolution. -->

## Impact

- **Build**: `desktop-viewer/desktop-app/build.gradle.kts` — `prepareProfilerAppResources.doLast` chmods the assembled resource binary (fixes the `:desktop-app:run` dev runtime); a self-contained `doLast` repack hook on `packageDeb`/`packageRpm`/`createDistributable`/`createReleaseDistributable` extracts the produced archive, sets the executable bit on the Trace Processor, and repacks it. (The `fileMode = 0b111101101` approach originally proposed does not work: it does not compile in this Gradle/Kotlin-DSL version and the Compose plugin's `File.copyTo` strips the bit before jpackage reads it — see design.) The repack hook must be configuration-cache compatible (no script-scope captures), or Gradle 9 fails the build at cache-store time even though the artifact is correct.
- **CI**: `.github/workflows/package-linux.yml` builds via `:desktop-app:packageDeb :desktop-app:packageRpm` — **not** `createDistributable`. The repack hook must match those task names or it never runs on CI (the first attempted fix only matched `createDistributable` and shipped 0644 debs). Verified: CI run `38880462482` repacks the `.deb` and the published payload's `trace_processor_shell` is `0755`.
- **Runtime**: `TraceProcessorToolResolver.kt` — `resolve()` gains an installed-copy fallback and a self-heal copy for a non-executable-but-checksum-valid packaged binary.
- **Tests**: `TraceProcessorToolResolverTest` — 8 new cases; desktop contract tests green.
- **`.rpm`**: no build-time repack (no robust in-place mode fix without `rpmrebuild`); rpm installs rely on the runtime self-heal.
- **No API/dependency changes.** No impact on trace capture or query schemas — the pinned version (`v57.2`) and checksums are unchanged.
