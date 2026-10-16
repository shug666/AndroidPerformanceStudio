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

### Decision 3: Build-time repack of `.deb`/`.rpm` (out-of-the-box, at the source)

The published `.deb`/`.rpm` SHALL contain an executable Trace Processor. Because the Compose packaging plugin (verified empirically and from bytecode) (a) copies resources with Kotlin `File.copyTo`, which strips the executable bit (reproduced: `rwxr-xr-x` → `rw-rw-r--`), and (b) builds the app-image and the package in a single jpackage call within `createDistributable` (dry-run chain `prepareProfilerAppResources → prepareAppResources → createDistributable`; no separate `createAppImage` task to hook), there is no Gradle injection point between image creation and packaging. Therefore the build SHALL set the executable bit by repacking the assembled package in a `doLast` on `createDistributable`/`createReleaseDistributable`: extract the payload, `chmod 0755` the Trace Processor, and repack.

- **`.deb`**: `dpkg-deb -x`/`ar` extraction, chmod, `dpkg-deb --build` (or `ar` repack) — preserves the control archive and metadata; only the binary's mode changes.
- **`.rpm`**: `rpm2cpio`/`cpio` extraction, chmod, repack (or rely on a `%attr`/`%ghost` note). RPM repack is heavier; if a robust rpm repack is impractical, the runtime self-heal (Decision 1) still covers rpm installs out-of-the-box.
- **Why not `fileMode` on the `CopySpec`**: tested — `fileMode = 0b111101101` on the `from(perfettoTools){}` lambda does not compile in this Gradle/Kotlin-DSL version (`Unresolved reference: fileMode`), and even if it did, the plugin's `copyTo` would strip the bit before jpackage reads it.
- **Why not chmod the assembled resource dir**: same reason — the plugin re-copies with `copyTo` (mode-stripping) into the dir jpackage reads, so the bit never reaches the payload.
- **Build-time assertion**: the repack step MUST verify the published payload's Trace Processor is executable and fail the build otherwise, so a regression cannot ship silently. (Decision 1's runtime self-heal is the runtime safety net for already-shipped artifacts.)
- **Scope / non-macOS**: the repack targets Linux `deb`/`rpm`. macOS (`dmg`/`pkg`) bundles are not POSIX-affected in the same way and the existing guard skips them; Windows (`msi`/`exe`) uses `.exe` and is not POSIX-affected. The `:desktop-app:run` dev mode is fixed by chmod-ing the assembled resource dir in `prepareProfilerAppResources.doLast` (the dev runtime reads that dir directly, with no intervening `copyTo`).

### Decision 4: Preserve configured-override precedence and checksum-mismatch semantics

Override first (unchanged). A wrong-checksum packaged binary surfaces `TRACE_PROCESSOR_CHECKSUM_MISMATCH` (not masked by fallback or self-heal).

## Risks / Trade-offs

- **[Risk] `fileMode` is ignored on some filesystems or by an older Gradle** → Mitigation: Decision 2's retimed chmod is an independent second path; the resolver fallback (Decision 3) is a third, runtime safety net. Three layers, each catching the failure the others miss.
- **[Risk] Fallback masks a real packaging regression, letting broken debs ship unnoticed** → Mitigation: the fallback is a runtime resilience measure, not a substitute for a build-time check. The existing `verifyPackagedTraceProcessor` task and `PerfettoFeatureSchemaCompatibilityTest` remain; add an assertion that the packaged binary in the app-resources dir is executable so a regression fails the build, not just the user's install.
- **[Risk] `fileMode` accidentally makes non-binary resources executable** → Mitigation: scope `fileMode` to the `perfetto-tools` `from` block only (Decision 1 scope note), not the whole Sync.
- **[Trade-off] Two mechanisms for the same bit (`fileMode` + retimed chmod)** → accepted: release-artifact correctness outweighs the small redundancy; the mechanisms operate at different stages and are not logically redundant given plugin copy semantics.

## Migration Plan

1. Build-side changes land first (`fileMode`, retimed chmod, build-time executable assertion). These take effect on the next `createDistributable`/`createReleaseDistributable` run; existing installed debs are unaffected until rebuilt and reinstalled.
2. Resolver fallback lands with tests; it is backward-compatible (existing happy path unchanged) and immediately improves already-deployed broken installs once users update the app.
3. **Immediate unblock for affected users** (no rebuild required): `sudo chmod +x /opt/androidperfermancestudio/lib/app/resources/perfetto-tools/trace_processor_shell`, or set `PERFETTO_TRACE_PROCESSOR_PATH` to a `scripts/install-trace-processor.sh`-installed copy. Document this in the change notes.
4. **Rollback**: revert the three changes independently; no data migration, no schema change, no persisted state involved.

## Open Questions

- Confirm the exact Gradle task that produces the shared app-image used by all native formats on this project (is it `createAppImage` exposed, or only `createDistributable`/`createReleaseDistributable`?) — the retimed chmod targets `prepareProfilerAppResources` output which feeds both, so this does not block the fix, but documenting the precise upstream task names would tighten the build-time assertion.
- Whether to also harden the macOS/Windows paths explicitly: on macOS the binary sits inside the `.app` bundle and the same resource copy applies; Windows uses `.exe` and is not subject to POSIX bits. The `fileMode` change is POSIX-only and harmless on Windows; the chmod hook is already guarded by `!windows`.
