## 1. Build: fix dev-mode resource dir + repack published `.deb`

- [x] 1.1 (superseded — `fileMode` does not compile in this Gradle/Kotlin-DSL version and the plugin's `copyTo` would strip the bit anyway; replaced by 1.5–1.7).
- [x] 1.2 Chmod the assembled `profiler-app-resources/common/perfetto-tools/trace_processor_shell` in `prepareProfilerAppResources.doLast` (fixes the `:desktop-app:run` dev runtime, which reads this dir directly).
- [x] 1.3 Keep the chmod hook guarded for non-Windows hosts (POSIX-only).
- [x] 1.4 Assert in the dev-mode chmod step that the assembled Trace Processor is executable, failing the build on regression.
- [x] 1.5 Add a `doLast` repack on `packageDeb`/`packageRpm`/`createDistributable`/`createReleaseDistributable` (POSIX hosts) that scans the task output dir for the produced `.deb`, extracts payload+control (`dpkg-deb -x`/`-e`), sets the executable bit (`0755`) on `trace_processor_shell`, and repacks (`dpkg-deb --build`) over the original. **Must match `packageDeb`/`packageRpm` — that is what `.github/workflows/package-linux.yml` invokes, not `createDistributable`.**
- [x] 1.6 `.rpm`: no build-time repack (no robust in-place mode fix without `rpmrebuild`); the hook logs a warning and skips, deferring rpm installs to the runtime self-heal (task 2.5).
- [x] 1.7 In the repack `doLast`, re-extract the repacked `.deb` and assert the published payload's `trace_processor_shell` is executable; fail the build otherwise.
- [x] 1.8 Make the repack `doLast` configuration-cache compatible: inline all logic, use only `task.logger`/`task.outputs`, resolve paths to `String` at config time (no script-scope captures — Gradle 9 otherwise fails at cache-store with `cannot serialize Gradle script object references`).

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
- [x] 4.3 (Covered by CI) The dev-mode chmod compiles and is asserted in `prepareProfilerAppResources.doLast`; not run standalone here (requires third_party dists), but the chmod is trivial and build-script compilation is verified.
- [x] 4.4 Produce a `.deb` via CI `:desktop-app:packageDeb` and confirm the repack `doLast` runs and the payload's `trace_processor_shell` is executable. Verified on CI run `38880462482`: log `Repacked ...androidperfermancestudio_0.0.2_amd64.deb: Trace Processor marked executable.` + `BUILD SUCCESSFUL`.
- [x] 4.5 Download the CI `.deb`, extract it, and confirm `<root>/lib/app/resources/perfetto-tools/trace_processor_shell` is executable. Verified: `dpkg-deb -x` of `AndroidPerfermanceStudio-0.0.2-linux-x64.deb` shows the binary at mode `0755`, executable. User installed it (`sudo dpkg -i`) and confirmed Trace Analyzer diagnostics now work.

## 5. Docs

- [x] 5.1 Add a short recovery note (chmod, `PERFETTO_TRACE_PROCESSOR_PATH`, or self-heal-on-first-run) to user-facing docs / changelog for users on already-deployed broken installs.
- [x] 5.2 Update packaging/architecture docs (`desktop-viewer/docs/architecture/DEVELOPMENT.md`) if they describe the chmod step's prior (incorrect) placement; note the repack step and the self-heal runtime behavior.
