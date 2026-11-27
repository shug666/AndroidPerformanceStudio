# Port Screencast and Reverse Tethering to the Rust desktop

Status: Accepted (2026-11-23)

Extended by: [ADR 0039](0039-keep-the-screencast-mirror-live.md) (the CLI
decoder's latency behavior: probe budget, thread count and the keyframe nudge).

Extends: [ADR 0037](0037-port-winscope-capture-to-the-rust-desktop.md) onto the
remaining fork-only features after upstream removed the legacy Kotlin
`desktop-viewer` tree.

## Context

ADR 0037 ported Winscope. Three fork-only capabilities remained without a Rust
equivalent and would have been lost with the legacy tree:

- **device-screencast** — scrcpy-server v3.1 device mirroring with touch /
  scroll / key / text injection and an H.264 decode pipeline (the Kotlin chain
  was VAAPI hard decode → in-process FFmpeg → jcodec fallback, with FLUENT /
  HD / NATIVE quality presets).
- **reverse-net** — reverse tethering via a fork of Genymobile gnirehtet's
  relay-java (Apache 2.0): a loopback relay at `127.0.0.1:31416` behind
  `adb reverse localabstract:gnirehtet`, carrying raw IPv4 packets after a
  4-byte big-endian client id handshake, plus DNS AAAA stripping (isolated
  mode), IPv6 leak auditing, Private DNS detection and a tunnel event stream.
- **legacy shell leftovers** — task-manager overlay, home-layout tests, a
  failure-summary helper and per-page test files that were all bound to the
  removed Compose navigator and are covered by existing Rust shell behavior.

## Decision

- Port each capability into a dedicated gpui-free crate following the
  aps-winscope template:
  - `rust-desktop/crates/aps-screencast` — control writer (all scrcpy v3.1
    message layouts, big-endian), device-message reader, video stream reader
    (64 B device name + 12 B metadata + `ptsAndFlags` framing, 16 MiB cap),
    session state machine (Idle/Connecting/Running/Disconnected/Failed with a
    CAS start guard and the Kotlin close order), the launcher (embedded
    scrcpy-server jar via `include_bytes!`, push/reverse/app_process argv with
    the zero-padded socket name vs non-padded `scid` distinction, reverse
    rollback and the cancel→500 ms→reverse --remove→rm -f cleanup), and the
    three quality presets.
  - `rust-desktop/crates/aps-reversenet` — the relay on mio 1.2.2 (client id
    handshake, IPv4 total-length framing, the simplified TCP state machine
    with in-order-only delivery, UDP 120 s idle recycling, 60 s cleanup),
    DNS message parsing with AAAA stripping, the leak auditor and Private DNS
    detector (both wired, fixing the Kotlin dead-code gaps), a bounded event
    bus, and the session controller state machine with the seven adb
    operations and the vendored gnirehtet 2.5.1 APK (23 904 bytes,
    `include_bytes!`, checksum-by-length on extraction).
- **Decode backend deviation**: the Kotlin in-process JavaCPP libavcodec chain
  (plus VAAPI and the jcodec fallback) collapses to a spawned system `ffmpeg`
  CLI (`-f h264 -i pipe:0 -f rawvideo -pix_fmt bgra pipe:1`). This matches the
  repository's tool-spawning discipline (trace_processor, adb) and keeps
  ~100 MB of per-platform native libraries out of the installers. When no
  ffmpeg binary is found the page fails with an explicit message instead of
  degrading. The Kotlin VAAPI pool's hard-coded 2560×1600 failure mode
  disappears with the backend.
- The page tier gains `Destination::Screencast` and `Destination::ReverseNet`
  (navigation rail, home module cards, en/zh catalogs, language refresh and
  route menus), the same wiring template as Winscope. The screencast canvas
  maps pointer events through the Kotlin contain-fit math (`containViewport` /
  `mapToScreen`, letterbox points never inject) and renders decoded BGRA
  frames directly (Skia N32 layout, zero channel swap).
- The architecture allowlist classifies both crates as adb-locator consumers
  only; `rust-desktop/ARCHITECTURE.md` records the direction.
- The remaining legacy Kotlin leftovers leave the tree once the ports land;
  protocol documents and vendored upstream licenses stay in git history.

## Consequences

- Both fork-only capabilities ship on the Rust desktop without the legacy
  Gradle stack; screencast installers no longer grow by FFmpeg native
  libraries, and reverse-net keeps the vendored official APK untouched
  (Apache 2.0 notice travels with the crate).
- In-crate tests cover the wire contracts (41 screencast tests including an
  end-to-end ffmpeg decode when a binary is present; 48 reversenet tests
  including the loopback echo round-trip and session state machine).
- Real-device smoke tests from the Kotlin suites remain opt-in follow-ups.
