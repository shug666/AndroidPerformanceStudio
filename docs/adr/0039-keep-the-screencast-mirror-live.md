# Keep the screencast mirror live with the ffmpeg CLI decoder

Status: Accepted (2026-11-27)

Extended by: [0040](0040-cap-mirror-texture-uploads.md)

Extends: [ADR 0038](0038-port-screencast-and-reverse-tethering-to-the-rust-desktop.md)

## Context

ADR 0038 replaced the Kotlin in-process libavcodec chain with a spawned
`ffmpeg` CLI and left the latency behavior of that pipeline unverified. On real
hardware the mirror was unusable in two ways that look unrelated but share one
cause:

- the canvas stayed black for several seconds after the session reached
  `Running`, and on a still screen it could stay black indefinitely;
- once pixels appeared, the mirror updated about once per second, and
  interaction felt frozen rather than merely slow.

Measurement (a headless probe over the real scrcpy v3.1 protocol against a
TB312FU tablet, 2136×1280, 24 access units/s, ~97 KB per access unit):

| metric | before | after |
|---|---|---|
| first frame after `start()` | 7.79 s | 2.5–3.5 s (of which 1.5–2.6 s is the device-side server boot) |
| frames per second while streaming | 0.7 | 10–23 (device-bound) |
| steady-state decoder lag | ~50 access units | 2 access units (~83 ms) |
| displayed frames, native 1920×1200, active swipes | 16 fps (debug build) | 26–29 fps (release build) |
| displayed frames, HD 1600 px @ 60 fps | n/a (resync storm) | 35–42 fps |

Three independent mechanisms were responsible:

1. **Stream-info probing.** `avformat_find_stream_info` buffered ~50 packets
   before the first decoded frame was emitted. `-probesize 32` removes that
   buffer (first frame 2.2 s → 0.7 s).
2. **Frame-threaded decoding.** The default thread count holds `nproc - 1`
   decoded frames before releasing the first one; on a 16-core host that is a
   further second of delay. `-threads 1` removes it (0.7 s → **0.10 s**). Slice
   threading is not an option because MediaCodec emits one slice per frame, and
   one decode thread still sustains ~72 fps at this resolution (measured), well
   above any preset's frame rate.
3. **A blocking decode call.** `decode` waited up to 100 ms for a frame that a
   *later* access unit had to release, which capped the loop at ten frames per
   second and starved the socket reader. The decoder now takes only the newest
   already-available frame and returns immediately.

Two more effects were found while verifying smoothness:

- **Build profile.** The decoded frame is handed to the canvas as a full BGRA
  image, and an unoptimized build copies and converts it so slowly that the
  display loop drops to ~16 fps while the decoder is delivering 30. The same
  session shows 26–29 fps in a release build; "still not smooth" in a `cargo
  run` session is usually this, not the mirror pipeline.
- **A fixed catch-up threshold punished honest shortfalls.** The backlog guard
  fired at eight access units per window regardless of stream rate, so a 60 fps
  preset whose decoder sustained 52 fps was repeatedly reset to the next
  keyframe — far more visible than the shortfall itself. The threshold now
  scales with the stream rate (a quarter of the access units in the window),
  which turned that preset from a resync storm into a steady 35–42 fps.

A fourth effect is not fixable inside ffmpeg: the H.264 parser ends a frame only
when it sees the *next* frame's start code, so the last access unit of a burst
is always held. A still screen therefore never paints its first frame, and the
last frame of an interaction would be one behind. The session asks the server
for a fresh keyframe (`RESET_VIDEO`) once per activity burst and once when the
session starts; that extra unit releases the held one. A settled screen costs
nothing, unlike the previous once-per-second reset that produced a 1 fps
slideshow.

## Decision

- Pin the latency-critical decoder options in `aps-screencast::decoder`
  (`-probesize 32 -analyzeduration 0 -threads 1`) next to the measured numbers,
  and treat them as load-bearing: dropping any of them reintroduces seconds of
  delay.
- Decode is eager and non-blocking: `decode` returns the newest complete frame
  and discards older ones, so a slow display can never build a queue.
- The session owns the latency policy (module docs in `session`):
  one `RESET_VIDEO` per activity burst plus one at startup, a bounded startup
  assist, and a backlog guard that drops buffered access units to the next
  keyframe and restarts the decoder with the cached codec config when the
  pipeline falls more than eight access units behind in a one-second window.
- `ScrcpySession::metrics()` exposes the pipeline counters (first-frame latency,
  frames, drops, nudges, resyncs, decoder and read time, startup phases) so the
  behavior is measurable from the app and from the `live_probe` example instead
  of being judged by eye.
- Quality presets are re-budgeted for host-side software decoding: FLUENT is
  1280 px / 30 fps (was 10 fps, which reads as a broken mirror), HD stays
  1600 px / 60 fps, NATIVE keeps device resolution at 30 fps, and the default
  bitrate drops from 20 to 12 Mbps to ease device-encoder pressure.

## Consequences

- The mirror is usable for interaction: first frame within the device's own
  start-up time plus a keyframe round trip, and a steady 2-access-unit lag while
  frames flow.
- `-threads 1` trades decode throughput for latency, so a future preset above
  ~2.7 MP at 60 fps would need a re-measurement (or in-process decoding) rather
  than a bigger preset table.
- The remaining ceiling is the raw BGRA pipe: at native resolution and 60 fps
  the frame traffic alone is ~500 MB/s, so above ~30 fps at native size the
  pipe and the per-frame copies, not the H.264 decoder, decide the frame rate.
  Moving the pixel-format conversion out of ffmpeg (NV12 output plus a Rust
  conversion, or a hardware decoder that keeps frames on the GPU) is the next
  step if native-resolution 60 fps is required.
- The burst nudge costs the device one extra IDR per interaction burst. Removing
  it requires a decoder that accepts access units directly (in-process
  libavcodec or a platform decoder), which stays the planned follow-up if the
  remaining one-frame hold-back ever becomes visible in practice.
- `aps-screencast` now has two diagnostic examples: `live_probe` (session
  timings and counters) and `dump_stream` (writes one real stream to disk for
  offline decoder work). Both are the reproduction harness for this ADR.
