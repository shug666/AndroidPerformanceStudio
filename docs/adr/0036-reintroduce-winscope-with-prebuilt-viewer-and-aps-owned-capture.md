# Reintroduce Winscope with a prebuilt viewer and APS-owned capture

Status: Accepted (2026-11-11)

Supersedes: the removal stance of [ADR 0035](0035-remove-the-winscope-workspace-and-upstream-viewer.md)
for the viewer delivery path; supersedes the source-build approach of
[ADR 0033](0033-package-upstream-winscope-as-an-optional-browser-viewer.md).

## Context

ADR 0035 removed Winscope entirely because the delivery path at the time — the AOSP
submodule plus its Node/pnpm/Go production build wired into Gradle — was the heaviest
and most brittle dependency in the repository. It explicitly left the door open:
"if the browser viewer returns, prefer a smaller delivery path over a full source
build in Gradle."

Users still need the Winscope workflow: capturing WindowManager/SurfaceFlinger/
transitions/IME/ViewCapture/input system traces and inspecting them in the Winscope
UI. Today that requires a local AOSP source tree (`npm run start` plus the in-tree
`winscope_proxy.py`), which is unavailable on ordinary Windows/Ubuntu machines.

A prebuilt AOSP Winscope production closure (~75 MB: inlined Angular `index.html`,
webpack `js/` chunks, `trace_processor.wasm`, `engine_bundle.js`) is available as a
fixed artifact. Reverse-engineering and a headless-browser spike verified its
client-side protocol facts:

- The viewer hardcodes its ADB proxy detection at `http://localhost:5544` and
  validates a `Winscope-Proxy-Version: 4.0.8` response header (major equal, minor
  greater, or equal minor with patch >= client).
- The viewer reads its auth token from the URL query parameter `?token=` or from
  localStorage (`adb.proxyKey`), sends it as `Winscope-Token`, and offers a
  paste-token retry dialog on 403.
- The protocol surface is seven endpoints (`/devices`, `/status`, `/fetch`,
  `/start`, `/end`, `/dump`, `/checkwayland`), fully specified by the bundled
  reference `winscope_proxy.py` (v4.0.8).

Because the bundle is pinned, the protocol only has to be aligned once; it cannot
drift underneath us.

## Decision

- Reintroduce Winscope as the `desktop-viewer/winscope-viewer` composite build with
  three Kotlin modules and one resource module: `winscope-app` (page + wiring),
  `winscope-capture-service` (proxy protocol server, static viewer host, browser
  launch), `winscope-device-capture` (device command decision table over the shared
  AdbClient), and `winscope-resources` (the packaged viewer closure).
- **APS implements the proxy protocol in Kotlin**; the Python `winscope_proxy.py` is
  never packaged or executed. Capture executes through the existing `AdbClient` and
  the `su root` semantics of the reference implementation: modern perfetto data-source
  path first (fused `.perfetto-trace`), fallback to the legacy per-source commands
  (`cmd window tracing`, `service call SurfaceFlinger`, …) with collection from
  `/data/misc/wmtrace/` etc., exactly as the reference decision table dictates.
- The bundled viewer closure ships as a fixed, checksum-manifested resource. Upgrading
  the bundle is a manual action: replace the closure, re-run
  `scripts/generate-winscope-closure-manifest.py`, re-align the protocol if the
  client version changed, and refresh the third-party license summary. Packaging
  strips `winscope_proxy.py`, launch scripts, the Google Analytics external script
  tag, and remote font imports; the host verifies checksums at startup and refuses to
  serve a mismatched closure.
- The viewer is launched in the system browser from an explicit user action with the
  session token injected via `?token=`; the proxy binds `127.0.0.1:5544` only (the
  client's hardcoded origin), serves `Cache-Control: no-store`, never lists
  directories, and generates a session-level token that dies with the service. This
  follows the service-lifecycle disciplines established by the Perfetto UI launch
  design.
- Capture artifacts returned through `/fetch` are also archived locally with device,
  timestamp, and target metadata, so later analysis passes can reference the same
  evidence.

## Rejected alternatives

- **Spawn the Python proxy:** requires Python 3.10+ on Windows, splits ADB ownership,
  and leaves traces in the proxy's temp directories instead of APS sessions.
- **Rebuild the submodule source (ADR 0033 path):** ADR 0035 already removed it for
  good cause; a pinned binary closure needs no Node/pnpm/Go toolchain.
- **Patch the bundle for auto-load:** the prebuilt minified bundle is not a
  maintainable patch surface; the `?token=` handshake already provides zero-friction
  handoff.
- **Native in-app capture UI:** the browser UI already provides the full configuration
  surface; duplicating it adds a second control plane to keep in sync.

## Consequences and risks

- Installers grow by roughly 75 MB pre-compression.
- The proxy protocol implementation must match the reference's payload shapes; the
  pinned bundle freezes the requirement, and `docs/winscope-proxy-protocol.md` is the
  alignment contract, with contract tests covering token/version/no-store/no-listing
  and payload shapes.
- Device capture requires a rooted or userdebug device; the engine probes root before
  anything else and reports the failure to the viewer.
- Both APS's own Perfetto capture and the Winscope modern path allocate device
  `traced` sessions; the Winscope engine cleans stale winscope sessions before
  starting and applies the reference's five-session limit with legacy fallback.
- Material Icons render as ligature text when the system browser is offline because
  the remote font import was stripped; the UI remains functional.
- The bundle's AOSP provenance commit is unknown (not embedded); the manifest records
  the build-date hint until a confirmed provenance is provided.

## Acceptance criteria

1. Starting the Winscope service from its page binds only `127.0.0.1:5544` and a
   local viewer port, generates a session token, and opens the viewer with `?token=`
   on explicit user action.
2. The bundled viewer completes its handshake against the Kotlin implementation
   (version header accepted, token honored, `/devices` polled) — verified end-to-end
   with a headless browser against the real implementation.
3. Capture on a rooted device runs the modern perfetto path when data sources are
   available and the legacy commands otherwise, collects files, returns them through
   `/fetch`, and archives the same bytes locally.
4. The packaged closure contains no `winscope_proxy.py`, no launch scripts, no
   analytics script tag, and no remote font imports; startup refuses a mismatched
   closure.
5. Stopping the service or exiting the app invalidates the token and releases device
   tracing sessions (keep-alive timeout sweep).
