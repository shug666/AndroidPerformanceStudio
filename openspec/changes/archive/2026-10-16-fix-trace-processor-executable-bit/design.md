## Context

The Trace Analyzer diagnostics (CPU scheduling hotspots, `sched_switch`, `sched_waking`, run-queue pressure, main-thread blocking, CPU frequency, CPU Contention) all run SQL against the native Perfetto Trace Processor shell. On Linux `.deb`/`.rpm` installs these features are dead: the packaged `trace_processor_shell` lands at mode `0644`, so `TraceProcessorToolResolver.verifiedPinned` rejects it with `TRACE_PROCESSOR_NOT_EXECUTABLE`. The binary is correct (SHA-256 matches the pinned `v57.2` manifest) — only the executable bit is missing.

Root cause, verified by decompiling the Compose Desktop packaging plugin (`AbstractJPackageTask.prepareWorkingDir`, `AbstractJvmToolOperationTask.run`):

```
install-trace-processor.sh  chmod +x  → build/perfetto-tools/...     [0755]
  │  Gradle Sync (prepareProfilerAppResources, no fileMode) → 0644
  ▼
generated/.../common/perfetto-tools/...                              [0644]
  │  jpackage prepareWorkingDir: FilesKt.copyTo (no mode preservation)
  ▼
compose/tmp/.../packagedResourcesDir/...                             [0644]
  │  jpackage --type deb (payload frozen)
  ▼
.deb → dpkg → /opt/.../perfetto-tools/trace_processor_shell          [0644]
```

Two compounding defects:
1. `prepareProfilerAppResources`'s `from(perfettoTools) { into("perfetto-tools") }` sets no `fileMode`, and Kotlin `File.copyTo` (used by the plugin's resource walk) does not preserve POSIX modes — so the bit is lost at the first copy.
2. The existing `setExecutable` fixup is a `doLast` on `createDistributable`/`createReleaseDistributable`. The plugin's `run()` `@TaskAction` calls `prepareWorkingDir` then invokes jpackage (which emits the `.deb`) and only *then* returns; `doLast` fires after the `.deb` is already frozen. The chmod mutates a leftover app-image copy, not the published payload.

A secondary defect in `TraceProcessorToolResolver.resolve()`: because the packaged file *exists*, the resolver returns `verifiedPinned(packaged)` and fails there, never reaching the `installed` fallback (lines 95-96). So `scripts/install-trace-processor.sh` cannot recover a broken deb install.

## Goals / Non-Goals

**Goals:**
- Ship `trace_processor_shell` with the executable bit inside `.deb`/`.rpm` (and the shared app-image feeding macOS/Windows formats), so diagnostics work out of the box.
- Make the fix robust to *how* resources are copied (don't depend solely on a single mechanism that a plugin upgrade could silently invalidate).
- Let a user-installed Trace Processor recover a packaged install whose binary lost its bit, so the resolver degrades gracefully instead of hard-failing.

**Non-Goals:**
- Changing the pinned Trace Processor version (`v57.2`) or its checksums.
- Changing trace capture, query schemas, or any diagnostic logic.
- Replacing the resolver's override mechanism or its checksum-verification contract.
- Making `/opt/...` self-healing (the install dir is root-owned and not writable by the app; runtime chmod is not viable there — fallback to the user-writable installed copy is the correct recovery).

## Decisions

### Decision 1: Runtime self-heal copy (primary, out-of-the-box fix)

When the packaged Trace Processor is the correct bytes (SHA-256 matches the pinned manifest) but is not executable, and no usable installed copy exists, the resolver copies the packaged binary into a user-writable cache (`~/.android-performance-studio/tools/perfetto/<pinned-version>/trace_processor_shell`), sets the executable bit on the copy, and resolves the copy.

- **Why this is the primary fix**: the `.deb`/`.rpm` payload ships the binary at 0644 (see Decision 3 for why the build cannot easily prevent this). The user-writable home dir is the one place the app process can `setExecutable`. Copying the checksum-valid packaged bytes there and chmod-ing them makes diagnostics work on a fresh install with zero user action and zero network dependency (unlike `scripts/install-trace-processor.sh`, which downloads).
- **Why copy rather than chmod in place**: `/opt/...` is root-owned; the app cannot `setExecutable` on the packaged path. Copying to the user-writable cache sidesteps the permissions problem.
- **Why gate on a matching packaged SHA-256**: a wrong-checksum packaged binary must still surface `TRACE_PROCESSOR_CHECKSUM_MISMATCH`; self-heal only applies to the right binary that lost its bit.
- **Idempotency**: the self-heal target is the same path the `installed` fallback already checks; once healed, later resolutions hit the existing `installed` branch and skip the copy. The copy is skipped if the target already exists and is executable with the pinned checksum.
- **Override precedence preserved**: the configured override is still evaluated first; self-heal only runs in the packaged-vs-installed path.

### Decision 2: Resolver fallback to an existing installed copy (secondary)

Before self-healing, the resolver still checks an existing user-installed copy and uses it if executable and checksum-valid. This reuses any copy the user already created via `scripts/install-trace-processor.sh` (avoids a redundant copy) and preserves the prior recovery story.

### Decision 3: Build-time repack of `.deb` (out-of-the-box, at the source)

The published `.deb` SHALL contain an executable Trace Processor. Because the Compose packaging plugin (verified empirically and from bytecode) (a) copies resources with Kotlin `File.copyTo`, which strips the executable bit (reproduced: `rwxr-xr-x` → `rw-rw-r--`), and (b) builds the app-image and the package in a single jpackage call within each packaging task, there is no Gradle injection point between image creation and packaging. Therefore the build SHALL set the executable bit by repacking the produced package in a `doLast` on the packaging tasks.

- **Which tasks to hook — `packageDeb`/`packageRpm`, NOT only `createDistributable`**: `.github/workflows/package-linux.yml` builds with `:desktop-app:packageDeb :desktop-app:packageRpm`. These are *separate* jpackage tasks, distinct from `createDistributable`. The first attempted fix only matched `createDistributable`/`createReleaseDistributable`, so on CI the hook never ran and the deb shipped 0644. The hook now matches `packageDeb`/`packageRpm`/`createDistributable`/`createReleaseDistributable`. **Lesson: confirm which task CI actually invokes before wiring a packaging hook.**
- **How the archive is found**: packaging tasks declare a destination `Directory` (`build/compose/binaries/main/{deb,rpm}`), not individual file outputs, so `outputs.files` holds the directory, not the `.deb`. The hook scans the output directory tree for `.deb`/`.rpm` files rather than reading `outputs.files` as files.
- **`.deb` repack**: `dpkg-deb -x`/`-e` extract payload + control, `chmod 0755` the binary, `dpkg-deb --build` repack over the original, then re-extract to assert the published payload is executable. Verified end-to-end on CI (run `38880462482`): `Repacked ...deb: Trace Processor marked executable.` and the downloaded deb's `trace_processor_shell` is `0755`.
- **`.rpm` repack: not implemented.** A robust in-place mode fix requires `rpmrebuild`/`rpmbuild`, which the build host may not have. rpm installs rely on the runtime self-heal (Decision 1). The hook logs a warning for `.rpm` and skips; it does not fail the build.
- **Configuration-cache compatibility — critical.** Gradle 9 stores the configuration cache; a `doLast` closure that captures *script-scope* references (top-level helper functions, the project `logger`) fails at cache-store time with `cannot serialize Gradle script object references` — even when the build itself completed and the artifact is correct (CI run `38877592235` built a correct deb but exited 1 for this reason). The repack action MUST be self-contained: inline all logic into the `doLast { }` block, use only `task.logger` and `task.outputs`, and resolve any needed paths to plain `String`s at configuration time so no script-scope object crosses the cache boundary.
- **Why not `fileMode` on the `CopySpec`**: tested — `fileMode = 0b111101101` on the `from(perfettoTools){}` lambda does not compile in this Gradle/Kotlin-DSL version (`Unresolved reference: fileMode`), and even if it did, the plugin's `copyTo` would strip the bit before jpackage reads it. Abandoned.
- **Why not chmod the assembled resource dir (for the published deb)**: the plugin re-copies with `copyTo` (mode-stripping) into the dir jpackage reads, so the bit never reaches the payload. Chmod-ing the assembled dir only fixes the `:desktop-app:run` dev runtime (which reads that dir directly, no intervening `copyTo`).
- **Scope / non-macOS**: the repack targets Linux `deb`/`rpm`. macOS (`dmg`/`pkg`) and Windows (`msi`/`exe`) are not POSIX-affected; the hook is guarded by `!windows` and only handles `.deb`/`.rpm`.

### Decision 4: Preserve configured-override precedence and checksum-mismatch semantics

Override first (unchanged). A wrong-checksum packaged binary surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH` (not masked by fallback or self-heal).

## Risks / Trade-offs

- **[Risk] The repack hook is attached to the wrong tasks** → Mitigation: it matches the four packaging tasks including `packageDeb`/`packageRpm` (what CI invokes), not only `createDistributable`. The first iteration missed this and shipped 0644 debs; corrected after confirming the CI workflow.
- **[Risk] The repack hook breaks the configuration cache** → Mitigation: the `doLast` action is fully self-contained (inlined logic, `task.logger`/`task.outputs` only, paths resolved to `String` at config time). The first inlined version still captured a script-scope `logger` and a `provider{}` and failed cache-store; corrected.
- **[Risk] Self-heal masks a real packaging regression, letting broken debs ship unnoticed** → Mitigation: self-heal is a runtime resilience net, not a substitute for the build-time check. The repack step asserts the published payload is executable and fails the build otherwise; `verifyPackagedTraceProcessor` and the desktop contract tests remain.
- **[Risk] `.rpm` ships 0644** → accepted: no robust in-place rpm mode fix without `rpmrebuild`; rpm installs are covered by the runtime self-heal. Documented in the hook (warning log) and the resolver.
- **[Trade-off] deb repack + runtime self-heal cover the same fault** → accepted: the deb repack makes fresh installs correct at the source; self-heal covers already-shipped/older debs and rpm. Defense in depth on a release-correctness path is cheap.
- **[Trade-off] deb repack mutates the build output in `doLast`** → accepted: jpackage's single-call build leaves no earlier hook. The repack re-extracts to verify it produced an executable payload, so a malformed repack fails loudly rather than shipping a broken deb.

## Migration Plan

1. Build-side changes (repack hook + dev-mode chmod) take effect on the next CI `packageDeb`/`packageRpm` run. Existing installed debs are unaffected until the user reinstalls.
2. Resolver self-heal + fallback land with tests; backward-compatible (existing happy path unchanged) and immediately improve already-deployed broken installs once users update the app.
3. **Immediate unblock for affected users** (no rebuild required): `sudo chmod +x /opt/androidperfermancestudio/lib/app/resources/perfetto-tools/trace_processor_shell`, or set `PERFETTO_TRACE_PROCESSOR_PATH` to a `scripts/install-trace-processor.sh`-installed copy.
4. **Rollback**: revert the changes independently; no data migration, no schema change, no persisted state involved.

## Resolved Questions

- **Which Gradle task does CI use?** Confirmed: `.github/workflows/package-linux.yml` builds via `:desktop-app:packageDeb :desktop-app:packageRpm` (separate jpackage tasks), not `createDistributable`. The repack hook matches those. (Verified on CI run `38880462482`.)
- **macOS/Windows:** macOS bundles the binary inside the `.app` and the same resource copy applies (the runtime self-heal covers it); Windows uses `.exe` and is not POSIX-affected. The hook is guarded by `!windows` and only handles `.deb`/`.rpm`.
- **Is there a Gradle hook between app-image creation and packaging?** No — each packaging task runs jpackage in a single call. Confirmed via the plugin's `run()` `@TaskAction` bytecode (`prepareWorkingDir` → `ExternalToolRunner.invoke`/jpackage → `checkResult`) and a `createDistributable --dry-run` showing no intermediate `createAppImage` task.
