## 1. Build: fix dev-mode resource dir + repack published .deb/.rpm

- [x] 1.1 (superseded — `fileMode` does not compile in this Gradle/Kotlin-DSL version and the plugin's `copyTo` would strip the bit anyway; replaced by 1.5–1.7).
- [x] 1.2 Retime the existing `setExecutable` fixup off `createDistributable`/`createReleaseDistributable`'s `doLast` and onto `prepareProfilerAppResources.doLast`, targeting the assembled `profiler-app-resources/common/perfetto-tools/trace_processor_shell`. (Fixes the `:desktop-app:run` dev runtime, which reads this dir directly.)
- [x] 1.3 Keep the chmod hook guarded for non-Windows hosts (POSIX-only), preserving the existing `!System.getProperty("os.name").lowercase().contains("win")` condition.
- [x] 1.4 Add a build-time assertion in the retimed chmod step that the assembled Trace Processor is executable, failing the build on regression.
- [x] 1.5 Add a `doLast` repack step on `createDistributable`/`createReleaseDistributable` (POSIX hosts) that, after jpackage produces the `.deb`, extracts its data archive, sets the executable bit (`0755`) on the packaged `trace_processor_shell`, and repacks the `.deb` (preserving the control archive and metadata).
- [x] 1.6 Extend the repack to `.rpm` (e.g. `rpm2cpio`/`cpio` extract → chmod → repack); if a robust rpm repack is impractical, document that rpm installs rely on the runtime self-heal (task 2.5).
- [x] 1.7 In the repack `doLast`, assert the published payload's `trace_processor_shell` is executable and fail the build otherwise.

## 2. Runtime: fallback + self-heal for a non-executable packaged copy

- [x] 2.1 In `TraceProcessorToolResolver.kt`, extract the packaged-vs-installed selection so that a packaged file which is a regular file with the pinned SHA-256 but `!Files.isExecutable` does not short-circuit to failure; instead proceed to evaluate the `installed` copy.
- [x] 2.2 When the `installed` copy is an executable regular file with the pinned SHA-256, return it as the resolved tool.
- [x] 2.3 When no usable copy exists in either location, fail with `TRACE_PROCESSOR_NOT_EXECUTABLE` whose message names the packaged path and the `scripts/install-trace-processor.sh` / `PERFETTO_TRACE_PROCESSOR_PATH` recovery options.
- [x] 2.4 Preserve the configured-override precedence (override still wins first) and the existing `TRACE_PROCESSOR_CHECKSUM_MISMATCH` behavior for a wrong-checksum packaged file (do not hide it behind the fallback).
- [x] 2.5 Add a self-heal path: when the packaged copy is a regular file with the pinned SHA-256 but not executable, and no executable checksum-valid `installed` copy exists, copy the packaged binary into the user-writable `installedToolsPath/trace_processor_shell`, set the executable bit on the copy, and return the copy. Skip the copy if the target already exists, is executable, and has the pinned checksum.
- [x] 2.6 Ensure self-heal is gated on a checksum-valid packaged binary (a wrong-checksum packaged file still surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH`), and that the configured override still takes precedence over self-heal.

## 3. Tests

- [x] 3.1 Packaged present, regular, pinned SHA-256, not executable; installed copy executable + pinned SHA-256 → resolver returns the installed copy, no error.
- [x] 3.2 Packaged present, regular, pinned SHA-256, not executable; self-heal cannot write the user cache → resolver fails with `TRACE_PROCESSOR_NOT_EXECUTABLE` mentioning recovery options.
- [x] 3.3 Packaged absent; installed copy executable + pinned SHA-256 → resolver returns the installed copy (regression guard).
- [x] 3.4 Override set and valid → resolver returns the override and ignores a non-executable packaged copy (precedence guard).
- [x] 3.5 Packaged present with a wrong SHA-256 → resolver surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH`, not the fallback/self-heal.
- [x] 3.6 Packaged present, regular, pinned SHA-256, not executable; no installed copy; self-heal succeeds → resolver copies to the user-writable cache, chmods, returns the copy, no error.
- [x] 3.7 Self-heal overwrites a corrupt/unusable cache copy from the good packaged bytes (`REPLACE_EXISTING` + chmod); when the cache copy is already valid the installed check returns it first (3.1), so no redundant copy.
- [x] 3.8 Self-heal yields to the configured override and does not engage on a wrong-checksum packaged binary.

## 4. Build & runtime verification

- [x] 4.1 Run `:platform-perfetto:platform-perfetto:test` (resolver tests incl. self-heal) and confirm green. (DONE for fallback; rerun after self-heal.)
- [x] 4.2 Run `:desktop-app:test` (incl. `PerfettoFeatureSchemaCompatibilityTest`, `SharedCoreModulesContractTest`) and confirm green.
- [ ] 4.3 Run `:desktop-app:prepareProfilerAppResources` and assert the assembled `perfetto-tools/trace_processor_shell` is mode `0755` (dev-mode fix).
- [ ] 4.4 Produce a `deb` via `:desktop-app:createDistributable`, run the repack `doLast`, and confirm the `.deb` payload's `trace_processor_shell` is executable (`dpkg-deb -c` shows `0755`).
- [ ] 4.5 Install/extract the repacked `deb` in a clean dir and confirm `<root>/lib/app/resources/perfetto-tools/trace_processor_shell` is executable.

## 5. Docs

- [x] 5.1 Add a short recovery note (chmod, `PERFETTO_TRACE_PROCESSOR_PATH`, or self-heal-on-first-run) to user-facing docs / changelog for users on already-deployed broken installs.
- [x] 5.2 Update packaging/architecture docs (`desktop-viewer/docs/architecture/DEVELOPMENT.md`) if they describe the chmod step's prior (incorrect) placement; note the repack step and the self-heal runtime behavior.
