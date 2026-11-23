# aps-winscope

Winscope system-trace capture for the Rust desktop: an APS-owned implementation
of the AOSP Winscope ADB proxy protocol (client version `4.0.8`) plus loopback
hosting of the bundled Winscope viewer closure.

- Protocol contract (source of truth): `docs/winscope-proxy-protocol.md`
- Decision record: `docs/adr/0037-port-winscope-capture-to-the-rust-desktop.md`
- Viewer bundle: `assets/winscope-viewer/`, integrity gated by
  `assets/winscope-viewer-manifest.json`
- The bundled viewer is a fixed artifact; upgrading it is an explicit action
  (re-align the protocol, regenerate the checksum manifest via
  `scripts/generate-winscope-closure-manifest.py`, refresh
  `THIRD-PARTY-LICENSES.txt`).

The crate has no GPUI dependency: `aps-app` renders the Winscope page and owns
the [`WinscopeService`](src/service.rs) lifecycle (start/stop/open viewer,
browser-driven capture mirror, keep-alive watchdog, app-exit cleanup).
