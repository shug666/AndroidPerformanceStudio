# 0040. Cap the mirror's GPU texture uploads and fit the picture to its viewport

Date: 2026-11-28
Status: accepted
Extends: [0039](0039-keep-the-screencast-mirror-live.md)

## Context

Two defects surfaced once the mirror ran on iGPU hosts for minutes at a time:

1. **The picture did not fit.** The `img` element inside the mirror canvas used
   `.size_full()`, but GPUI's image layout resolves a percentage height against
   the canvas and then applies the image aspect ratio: the element ended up
   `canvas_width x (canvas_width / device_ratio)` — about 1045 px tall in a
   752 px canvas. The overflow clip cut the bottom of the device screen off, so
   the launcher dock and gesture bar were invisible while the picture "filled"
   the canvas. Template matching measured a uniform 0.536 render scale
   (= canvas_width / device_width): width-fit, not contain.
2. **The machine froze mid-cast.** Xorg died after roughly six minutes of
   Native-resolution casting and the session came back to the login screen;
   swap grew to 4 GB beforehand. Every promoted frame uploads a fresh
   1920x1200x4 (~9.2 MB) texture into GPUI's sprite atlas at stream cadence —
   hundreds of MB/s of GPU allocations, sustained.

## Decisions

- The mirror image is placed **at the contain-fit viewport with explicit pixel
  geometry** (`left/top/w/h`), the same rectangle the touch mapping already
  computes (`ScreencastWorkspace::viewport()`). Picture and input now share one
  source of truth, letterboxing replaces cropping, and GPUI's aspect-ratio
  fallback can no longer resize the element.
- Promotions are **throttled to a texture-upload bandwidth cap** of 80 MB/s
  (`PROMOTE_UPLOAD_CAP`), with a 50 ms floor (`PROMOTE_MIN_GAP`). The frame
  pump still drains the socket at stream cadence and keeps only the newest
  frame; skipped promotions park the pump instead of spinning. Effective
  cadence: Fluent ~20 fps, HD ~12 fps, Native ~8 fps.
- **Fluent (1280 px, 30 fps, 4 Mbps) is the default preset** instead of
  Native, so an out-of-the-box cast costs ~4 MB per promotion, not ~9 MB.

## Consequences

- The full device screen — dock and gesture bar included — is always visible;
  the mirror letterboxes like scrcpy instead of cropping.
- A five-minute cast on the affected iGPU host holds: Xorg survives, app RSS
  stabilises (~196 MB), swap stays at 0.
- Heavy presets trade display cadence for stability; hosts with real GPUs can
  raise `PROMOTE_UPLOAD_CAP`. Replacing per-frame full-texture uploads with a
  tiled/diff-based atlas remains the structural fix if Native ever needs
  20 fps again.
- `wgpu-hal` swapchain-semaphore panics observed on window teardown are a
  separate upstream issue and are not addressed here.
