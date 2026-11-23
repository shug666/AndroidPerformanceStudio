# Port Winscope capture to the Rust desktop

Status: Accepted (2026-11-22)

Extends: [ADR 0036](0036-reintroduce-winscope-with-prebuilt-viewer-and-aps-owned-capture.md)
onto the `rust-desktop` workspace after upstream removed the legacy Kotlin
`desktop-viewer` tree (v0.8.x Rust rewrite).

## Context

ADR 0036 delivered Winscope as a Kotlin module inside the legacy Compose desktop
(`desktop-viewer/winscope-viewer`): an APS-owned capture engine implementing the
AOSP `winscope_proxy.py` (v4.0.8) protocol plus loopback hosting of the prebuilt
viewer closure. Upstream has since removed the entire legacy Compose desktop in
favor of the `rust-desktop` GPUI workspace, which had no Winscope equivalent —
the fork's only end-to-end Winscope delivery path would have been lost with the
merge.

The functional contract did not change with the language migration:
`docs/winscope-proxy-protocol.md` remains the wire contract (source of truth),
the bundled viewer closure stays a fixed version-4.0.8 artifact, and the
checksum manifest continues to gate hosting.

## Decision

- Port the capture stack to a new dedicated crate
  `rust-desktop/crates/aps-winscope` with no GPUI dependency so the protocol
  engine stays unit-testable: targets registry, perfetto config text, adb
  transport (bounded probe shells, long-lived trace shells with cooperative
  cancellation, streamed fetch), TTL device catalog, capture engine
  (start/end/dump/fetch/keep-alive sweep), the loopback proxy server on the
  client-fixed port 5544, and the verified viewer host on 8000+.
- HTTP serving uses `std::net::TcpListener` worker pools in the style of the
  existing `firefox_viewer` loopback server; no async runtime or web framework
  enters the dependency tree. JSON uses the workspace-pinned `serde_json`;
  hashing uses `sha2`; fetch compression uses the already-linked `flate2`;
  base64 MIME wrapping (76-char lines, Python `encodebytes` compatible) is
  implemented locally.
- The Winscope page joins the single navigation catalog
  (`Destination::Winscope`) with its entry icon, a home module card and en/zh
  string catalogs. The page mirrors browser-driven capture state on a bounded
  poll cadence; capture configuration stays in the browser viewer (unchanged
  from ADR 0036).
- App-exit safety replaces the JVM shutdown hook: the service registers itself
  in a process-wide cleanup registry invoked from the application quit path,
  ending in-progress captures and releasing both loopback ports.
- The prebuilt viewer closure moves to
  `rust-desktop/crates/aps-winscope/assets/winscope-viewer` (git history
  follows the rename; no new bytes are stored) and release packaging installs
  it next to `firefox-profiler` under `resources/` for Linux (deb/rpm), macOS
  (Contents/Resources) and Windows (portable zip/MSI).
- The legacy Kotlin winscope module sources remain in git history only; the
  in-tree copy under `desktop-viewer/winscope-viewer` is retired as part of
  this port.

## Consequences

- Winscope works on the Rust desktop without the legacy Compose stack; the
  protocol implementation is exercised by 49 in-crate tests (declaration order,
  availability probes, config text, script template, fetch encoding, token
  gate, reference error shapes, closure verification).
- Future ADB/protocol changes must still update
  `docs/winscope-proxy-protocol.md` first and re-align both implementations of
  the contract (bundle client and this proxy).
- The remaining legacy fork-only modules (device screencast, reverse network
  profiler) are NOT ported by this decision; they stay recoverable in git
  history and as unported in-tree leftovers until a follow-up decision.
