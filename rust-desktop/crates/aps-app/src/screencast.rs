//! Device screencast workspace: mirror a device screen over the embedded
//! scrcpy-server v3.1 protocol, render decoded BGRA frames on a GPUI canvas
//! and inject touch/scroll/key/text back to the device. Port of the legacy
//! Compose screencast page (see the port spec `/tmp/aps-port-spec-screencast.md`
//! §6/§8 and ADR 0038); session/protocol fidelity lives in `aps-screencast`.

use crate::studio;
use aps_adb::{AdbClient, AdbLocator};
use aps_platform::CancellationToken;
use aps_screencast::launcher::{AdbOutput, AdbRunner};
use aps_screencast::session::FrameReceiver;
use aps_screencast::{BgraFrame, ScrcpySession, ScrcpySessionState, ScreencastQuality};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
        input::{Input, InputEvent, InputState},
    },
    prelude::*,
    *,
};
use std::sync::Arc;
use std::sync::mpsc::TryRecvError;
use std::time::Duration;

/// Device discovery cadence (the Kotlin controller polled every 2 s; 1 s keeps
/// the picker responsive without saturating adb).
const DEVICE_POLL: Duration = Duration::from_millis(1_000);
/// Idle wait of the frame pump between drain attempts while the stream is
/// quiet; the first frame after an idle stretch may arrive up to this late.
/// Frames themselves are pumped immediately (no pacing) so the display shows
/// every decoded frame at stream cadence instead of the poll cadence.
const FRAME_PUMP_IDLE_WAIT: Duration = Duration::from_millis(8);
/// Keycodes reused from the control contract (spec §2.3).
const KEYCODE_BACK: i32 = 4;
const KEYCODE_HOME: i32 = 3;
const KEYCODE_APP_SWITCH: i32 = 187;
const KEYCODE_POWER: i32 = 26;
/// Android keycodes for the adb fallback of the screen toggle: wake lights the
/// display, sleep turns it off (no scrcpy control socket exists off-cast).
const KEYCODE_WAKEUP: i32 = 224;
const KEYCODE_SLEEP: i32 = 223;
const ACTION_DOWN: u8 = 0;
const ACTION_UP: u8 = 1;
const ACTION_MOVE: u8 = 2;
const ACTION_HOVER_MOVE: u8 = 7;
const BUTTON_PRIMARY: i32 = 1;
const BUTTON_SECONDARY: i32 = 2;

#[derive(Clone, PartialEq, Eq)]
struct DeviceOption {
    serial: String,
    display_name: String,
}

/// Result of one frame-pump drain pass.
#[derive(Clone, Copy, PartialEq, Eq)]
enum PumpOutcome {
    /// At least one frame arrived: already promoted and repainted.
    Frames,
    /// Channel empty (stream quiet or between frames).
    Idle,
    /// The receiver is dead (session ended) or the workspace generation moved
    /// on: the pump exits.
    Disconnected,
}

/// Contain-fit viewport of the current frame inside the canvas bounds
/// (spec §8.1 `containViewport`).
struct Viewport {
    origin: Point<Pixels>,
    size: Size<Pixels>,
}

pub(crate) struct ScreencastWorkspace {
    language: Language,
    devices: Vec<DeviceOption>,
    selected: Option<String>,
    quality: ScreencastQuality,
    session: Option<Arc<ScrcpySession>>,
    state: ScrcpySessionState,
    frames: Option<FrameReceiver>,
    /// Latest decoded frame promoted to a render image; BGRA bytes are consumed
    /// directly by GPUI (Skia N32 layout, matching the Kotlin pipeline).
    frame: Option<Arc<RenderImage>>,
    /// Decoded frame dimensions (RenderImage exposes no size accessor).
    frame_size: Option<(u32, u32)>,
    screen_off: bool,
    power_key_active: bool,
    error: Option<String>,
    /// Last finalized save (screenshot/recording path), already formatted.
    notice: Option<String>,
    /// A screenshot capture or its save dialog is in flight.
    screenshot_busy: bool,
    /// Destination of the active recording (`None` = not recording; mirrored
    /// from the session's `is_recording` by the poll as a safety net).
    recording: Option<String>,
    /// A recording start/stop or its save dialog is in flight.
    record_busy: bool,
    generation: u64,
    canvas_bounds: Option<Bounds<Pixels>>,
    pointer_pressed: bool,
    /// Frames promoted to the canvas since the last poll tick, and the tick's
    /// start: the pair yields the display frame rate shown in the status line.
    promoted_since_tick: u32,
    tick_started_at: std::time::Instant,
    /// Measured display frame rate (frames promoted per second).
    display_fps: u32,
    /// The mirror owns the whole window (shell-rendered overlay; ESC exits).
    fullscreen: bool,
    /// Focus anchor of the overlay so ESC reaches it without a global binding.
    fullscreen_focus: FocusHandle,
    send_input: Entity<InputState>,
}

impl ScreencastWorkspace {
    pub(crate) fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let send_input = cx.new(|cx| InputState::new(window, cx));
        cx.subscribe_in(
            &send_input,
            window,
            |this, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { .. }) {
                    this.send_text(window, cx);
                }
            },
        )
        .detach();
        let mut workspace = Self {
            language,
            devices: Vec::new(),
            selected: None,
            quality: ScreencastQuality::DEFAULT,
            session: None,
            state: ScrcpySessionState::Idle,
            frames: None,
            frame: None,
            frame_size: None,
            screen_off: false,
            power_key_active: false,
            error: None,
            notice: None,
            screenshot_busy: false,
            recording: None,
            record_busy: false,
            generation: 0,
            canvas_bounds: None,
            pointer_pressed: false,
            promoted_since_tick: 0,
            tick_started_at: std::time::Instant::now(),
            display_fps: 0,
            fullscreen: false,
            fullscreen_focus: cx.focus_handle(),
            send_input,
        };
        workspace.refresh_devices(cx);
        cx.notify();
        workspace
    }

    /// Whether the shell should render the full-window mirror overlay.
    pub(crate) fn is_fullscreen(&self) -> bool {
        self.fullscreen
    }

    /// Toggles the full-window mirror. Only a live session can be expanded:
    /// an idle or failed canvas has nothing to show fullscreen.
    fn toggle_fullscreen(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !matches!(self.state, ScrcpySessionState::Running { .. }) {
            return;
        }
        self.fullscreen = !self.fullscreen;
        if self.fullscreen {
            // The overlay is the only focusable surface left; anchoring focus
            // here is what routes ESC to `exit_fullscreen`.
            window.focus(&self.fullscreen_focus, cx);
        }
        cx.notify();
    }

    /// Leaves the full-window mirror (ESC, the corner button, or a session that
    /// ended underneath it).
    pub(crate) fn exit_fullscreen(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        if !self.fullscreen {
            return;
        }
        self.fullscreen = false;
        cx.notify();
    }

    pub(crate) fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        self.language = language;
        cx.notify();
    }

    /// Bounded-cadence poll: syncs the session state and the recording
    /// indicator. Frames are drained by [`Self::spawn_frame_pump`] at stream
    /// cadence, never by this poll.
    fn spawn_poll(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(DEVICE_POLL).await;
            let alive = this
                .update(cx, |this, cx| {
                    if this.generation != generation {
                        return false;
                    }
                    let mut keep_polling = true;
                    let running = matches!(this.state, ScrcpySessionState::Running { .. });
                    // Display rate: what the canvas actually received, which is
                    // the number the user perceives (the session's own counters
                    // say what left the decoder).
                    let elapsed = this.tick_started_at.elapsed();
                    if elapsed >= std::time::Duration::from_millis(900) {
                        let fps = (f64::from(this.promoted_since_tick)
                            / elapsed.as_secs_f64())
                        .round() as u32;
                        this.promoted_since_tick = 0;
                        this.tick_started_at = std::time::Instant::now();
                        if fps != this.display_fps {
                            this.display_fps = fps;
                            cx.notify();
                        }
                    }
                    if let Some(session) = this.session.clone() {
                        let state = session.state();
                        if state != this.state {
                            // A mirror that ends is the moment its pipeline
                            // numbers are worth keeping: they say whether the
                            // session was live (frames, first-frame latency) or
                            // stalled (drops, resyncs).
                            // Only an *unexpected* end is worth logging; a
                            // user stop is intentional and clears the state
                            // itself.
                            if matches!(this.state, ScrcpySessionState::Running { .. })
                                && matches!(
                                    state,
                                    ScrcpySessionState::Disconnected
                                        | ScrcpySessionState::Failed(_)
                                )
                            {
                                let metrics = session.metrics();
                                eprintln!(
                                    "[screencast] mirror ended: {} frames over {} ms (first frame {} ms, {} dropped, {} nudges, {} resyncs, {} decoded)",
                                    metrics.frames_delivered,
                                    metrics
                                        .last_frame_ms
                                        .saturating_sub(metrics.started_at_ms),
                                    metrics.first_frame_ms,
                                    metrics.frames_dropped,
                                    metrics.nudges,
                                    metrics.resyncs,
                                    metrics.frames_decoded,
                                );
                            }
                            // The overlay would otherwise keep showing the last
                            // frame of a session that is gone.
                            if this.fullscreen
                                && !matches!(state, ScrcpySessionState::Running { .. })
                            {
                                this.fullscreen = false;
                            }
                            this.state = state;
                            cx.notify();
                        }
                        // Safety net: a recording that the session finalized
                        // on its own (device disconnect, stream end) must
                        // release the UI indicator.
                        if this.recording.is_some() && !session.is_recording() {
                            this.recording = None;
                            cx.notify();
                        }
                        let is_active = session.is_active();
                        keep_polling = is_active || running;
                    }
                    keep_polling
                })
                .unwrap_or(false);
            if !alive {
                break;
            }
        })
        .detach();
    }

    /// Frame pump: drains the decoded-frame channel on a bounded ~8 ms cadence
    /// and repaints whenever the newest frame changed — the display loop
    /// mirrors scrcpy's own (newest frame wins; bursts coalesce). Runs until
    /// the session ends or a new generation replaces it.
    ///
    /// Every iteration MUST await: this future runs on the foreground
    /// executor, and a never-yielding loop would monopolize the main thread,
    /// starving effect flushing — `cx.notify()` would never reach a repaint
    /// (blank canvas). The 8 ms wait costs at most one frame of latency while
    /// keeping each drain batch idempotent (promote newest, drop the rest).
    fn spawn_frame_pump(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            loop {
                let alive = this
                    .update(cx, |this, cx| {
                        if this.generation != generation {
                            return false;
                        }
                        !matches!(this.drain_frame(cx), PumpOutcome::Disconnected)
                    })
                    .unwrap_or(false);
                if !alive {
                    break;
                }
                cx.background_executor().timer(FRAME_PUMP_IDLE_WAIT).await;
            }
        })
        .detach();
    }

    fn refresh_devices(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async move {
            let cancel = CancellationToken::new();
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            let client = AdbClient::new(location);
            let devices = client
                .list_devices(&cancel)
                .map_err(|error| error.to_string())?;
            Ok::<_, String>(
                devices
                    .into_iter()
                    .filter(|device| device.is_online())
                    .map(|device| {
                        let display_name = device.model().to_owned();
                        DeviceOption {
                            serial: device.serial.clone(),
                            display_name: if display_name.is_empty() {
                                device.serial.clone()
                            } else {
                                display_name
                            },
                        }
                    })
                    .collect::<Vec<_>>(),
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(devices) => {
                        this.devices = devices;
                        this.error = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                // Keep the selection stable; fall back to the first device like
                // the Kotlin controller did.
                let still_there = this.selected.as_ref().is_some_and(|selected| {
                    this.devices.iter().any(|device| &device.serial == selected)
                });
                if !still_there {
                    this.selected = this.devices.first().map(|device| device.serial.clone());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Drains the frame channel and promotes the newest frame to a render
    /// image; extra frames are dropped (the display semantics keep only the
    /// latest frame). Reports whether frames arrived, the channel went quiet,
    /// or the session ended.
    fn drain_frame(&mut self, cx: &mut Context<Self>) -> PumpOutcome {
        let Some(receiver) = self.frames.as_ref() else {
            return PumpOutcome::Disconnected;
        };
        let mut latest = None;
        let mut connected = true;
        loop {
            match receiver.try_recv() {
                Ok(frame) => latest = Some(frame),
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    connected = false;
                    break;
                }
            }
        }
        let promoted = latest.is_some();
        if let Some(frame) = latest {
            self.promoted_since_tick += 1;
            self.frame_size = Some((frame.width, frame.height));
            self.frame = Some(Arc::new(to_render_image(frame)));
            cx.notify();
        }
        if !connected {
            self.frames = None;
            return PumpOutcome::Disconnected;
        }
        if promoted {
            PumpOutcome::Frames
        } else {
            PumpOutcome::Idle
        }
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        let Some(serial) = self.selected.clone() else {
            self.error = Some(self.language.text(TextKey::ScreencastNoDevice).to_owned());
            cx.notify();
            return;
        };
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.error = None;
        self.notice = None;
        if let Some(session) = self.session.take() {
            session.stop();
        }
        self.frames = None;
        let quality = self.quality;
        let task = cx.background_executor().spawn(async move {
            let config = aps_screencast::SessionConfig::new(serial, quality);
            Ok::<_, String>(Arc::new(ScrcpySession::new(config)?))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    if let Ok(session) = result {
                        session.stop();
                    }
                    return;
                }
                match result {
                    Ok(session) => {
                        this.frames = Some(session.start());
                        this.session = Some(session);
                        this.state = ScrcpySessionState::Connecting;
                        this.screen_off = false;
                        this.power_key_active = false;
                        this.spawn_poll(generation, cx);
                        this.spawn_frame_pump(generation, cx);
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn stop(&mut self, cx: &mut Context<Self>) {
        // A stopped session has no surface to show fullscreen.
        self.fullscreen = false;
        self.generation = self.generation.wrapping_add(1);
        if let Some(session) = self.session.take() {
            session.stop();
        }
        self.frames = None;
        self.frame = None;
        self.frame_size = None;
        self.state = ScrcpySessionState::Idle;
        self.notice = None;
        self.recording = None;
        cx.notify();
    }

    fn running_size(&self) -> Option<(u32, u32)> {
        match &self.state {
            ScrcpySessionState::Running { width, height, .. } => Some((*width, *height)),
            _ => None,
        }
    }

    /// Whether the scrcpy control socket is live: control messages ride the
    /// session while casting and fall back to adb otherwise.
    fn is_running(&self) -> bool {
        matches!(self.state, ScrcpySessionState::Running { .. })
    }

    /// Contain-fit viewport for the latest frame inside the canvas bounds
    /// (spec §8.1); the `img` element uses `ObjectFit::Contain`, so this
    /// computation mirrors the displayed letterbox rect.
    fn viewport(&self) -> Option<Viewport> {
        let bounds = self.canvas_bounds?;
        let (frame_w, frame_h) = self.frame_size?;
        let canvas_w = f32::from(bounds.size.width);
        let canvas_h = f32::from(bounds.size.height);
        if canvas_w <= 0. || canvas_h <= 0. || frame_w == 0 || frame_h == 0 {
            return None;
        }
        let screen_ratio = frame_w as f32 / frame_h as f32;
        let canvas_ratio = canvas_w / canvas_h;
        let (offset_x, offset_y, width, height) = if screen_ratio > canvas_ratio {
            let width = canvas_w;
            let height = width / screen_ratio;
            (0., (canvas_h - height) / 2., width, height)
        } else {
            let height = canvas_h;
            let width = height * screen_ratio;
            ((canvas_w - width) / 2., 0., width, height)
        };
        Some(Viewport {
            origin: bounds.origin + point(px(offset_x), px(offset_y)),
            size: size(px(width), px(height)),
        })
    }

    /// Canvas → device coordinate mapping (spec §8.1 `mapToScreen`); letterbox
    /// points return None and never inject.
    fn map_to_device(&self, canvas_point: Point<Pixels>) -> Option<(i32, i32)> {
        let viewport = self.viewport()?;
        let (screen_w, screen_h) = self.running_size()?;
        let rel_x = (canvas_point.x - viewport.origin.x) / viewport.size.width;
        let rel_y = (canvas_point.y - viewport.origin.y) / viewport.size.height;
        if !(0.0..=1.0).contains(&rel_x) || !(0.0..=1.0).contains(&rel_y) {
            return None;
        }
        Some((
            (rel_x * screen_w as f32).round() as i32,
            (rel_y * screen_h as f32).round() as i32,
        ))
    }

    fn send_touch(&mut self, action: u8, canvas_point: Point<Pixels>, pressure: f32, buttons: i32) {
        let Some((x, y)) = self.map_to_device(canvas_point) else {
            return;
        };
        if let Some(session) = self.session.as_ref() {
            session.send_touch(action, x, y, pressure, buttons);
        }
    }

    fn send_scroll(&mut self, canvas_point: Point<Pixels>, scroll_x: f32, scroll_y: f32) {
        let Some((x, y)) = self.map_to_device(canvas_point) else {
            return;
        };
        // The UI scroll delta is content-flow positive; scrcpy's positive units
        // move the content up, so negate like the Compose layer did (§8.2).
        if let Some(session) = self.session.as_ref() {
            session.send_scroll(x, y, -scroll_x, -scroll_y);
        }
    }

    fn tap_navigation_key(&mut self, keycode: i32, cx: &mut Context<Self>) {
        if self.is_running() {
            if let Some(session) = self.session.as_ref() {
                session.tap_keycode(keycode);
            }
        } else {
            self.adb_inject_keycode(keycode, cx);
        }
        cx.notify();
    }

    fn toggle_screen_power(&mut self, cx: &mut Context<Self>) {
        let turn_on = self.screen_off;
        self.screen_off = !turn_on;
        if self.is_running() {
            // `on` lights the display without sleeping (session §6.3 target).
            if let Some(session) = self.session.as_ref() {
                session.set_display_power(turn_on);
            }
        } else {
            let keycode = if turn_on {
                KEYCODE_WAKEUP
            } else {
                KEYCODE_SLEEP
            };
            self.adb_inject_keycode(keycode, cx);
        }
        cx.notify();
    }

    fn toggle_power_key(&mut self, cx: &mut Context<Self>) {
        self.power_key_active = !self.power_key_active;
        if self.is_running() {
            if let Some(session) = self.session.as_ref() {
                session.tap_keycode(KEYCODE_POWER);
            }
        } else {
            self.adb_inject_keycode(KEYCODE_POWER, cx);
        }
        cx.notify();
    }

    /// Sends one keycode to the selected device through a direct
    /// `adb shell input keyevent`: the fallback whenever the scrcpy control
    /// socket does not exist (before the first cast, while connecting, after a
    /// drop). Failures surface in the error banner; success clears nothing.
    fn adb_inject_keycode(&mut self, keycode: i32, cx: &mut Context<Self>) {
        let Some(serial) = self.selected.clone() else {
            return;
        };
        let task = cx.background_executor().spawn(async move {
            let runner = AdbRunner::locate(None)?;
            let output = runner.shell(&serial, &["input", "keyevent", &keycode.to_string()]);
            if output.success {
                Ok(())
            } else {
                Err(bounded_adb_error(&output))
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            if let Err(error) = result {
                this.update(cx, |this, cx| {
                    this.error = Some(error);
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    /// Captures the device screen as a PNG and saves it through the standard
    /// save dialog. The capture (`adb exec-out screencap -p`, native
    /// resolution regardless of the cast quality) starts immediately so the
    /// dialog opens while the device works; cancelling the dialog discards
    /// the capture. The saved path lands in the notice line.
    fn capture_screenshot(&mut self, cx: &mut Context<Self>) {
        if self.screenshot_busy {
            return;
        }
        let Some(serial) = self.selected.clone() else {
            return;
        };
        self.screenshot_busy = true;
        let language = self.language;
        let directory = aps_platform::home_directory().unwrap_or_else(|_| std::env::temp_dir());
        let suggested = suggested_screenshot_name(&serial, unix_millis());
        let capture = cx.background_executor().spawn(async move {
            let runner = AdbRunner::locate(None)?;
            let bytes = runner.exec_out_bytes(&serial, &["screencap", "-p"])?;
            if !is_png(&bytes) {
                return Err(language
                    .text(TextKey::ScreencastScreenshotInvalid)
                    .to_owned());
            }
            Ok(bytes)
        });
        let picker = cx.prompt_for_new_path(&directory, Some(&suggested));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let captured = capture.await;
            let outcome = match chosen {
                Ok(Ok(Some(path))) => match captured {
                    Ok(bytes) => {
                        let destination = path.clone();
                        let write = cx.background_executor().spawn(async move {
                            std::fs::write(&destination, &bytes).map_err(|_| {
                                language
                                    .text(TextKey::ScreencastScreenshotWriteFailed)
                                    .to_owned()
                            })
                        });
                        write.await.map(|_| Some(path))
                    }
                    Err(error) => Err(error),
                },
                // Cancelled: discard quietly; picker failure: surface it.
                Ok(Ok(None)) => Ok(None),
                _ => Err(language
                    .text(TextKey::ScreencastScreenshotPickerFailed)
                    .to_owned()),
            };
            this.update(cx, |this, cx| {
                this.screenshot_busy = false;
                match outcome {
                    Ok(Some(path)) => {
                        this.error = None;
                        this.notice = Some(this.language.format(
                            TextKey::ScreencastScreenshotSaved,
                            &[("path", &path.display().to_string())],
                        ));
                    }
                    Ok(None) => {}
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Starts a recording: the save dialog runs first (the session streams
    /// straight into the chosen MP4 once confirmed), then the session tees
    /// the video stream into a remuxing ffmpeg process. Requires a running
    /// cast (the video stream is the recording source).
    fn start_recording(&mut self, cx: &mut Context<Self>) {
        if self.record_busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        self.record_busy = true;
        let language = self.language;
        let directory = aps_platform::home_directory().unwrap_or_else(|_| std::env::temp_dir());
        let serial = self.selected.clone().unwrap_or_default();
        let suggested = suggested_recording_name(&serial, unix_millis());
        let picker = cx.prompt_for_new_path(&directory, Some(&suggested));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let outcome = match chosen {
                Ok(Ok(Some(path))) => {
                    let started = cx
                        .background_executor()
                        .spawn(async move { session.start_recording(&path).map(|_| path) })
                        .await;
                    match started {
                        Ok(path) => Ok(Some(path)),
                        Err(error) => Err(language.format(
                            TextKey::ScreencastRecordUnavailable,
                            &[("error", &bounded_error_text(&error))],
                        )),
                    }
                }
                // Cancelled: discard quietly; picker failure: surface it.
                Ok(Ok(None)) => Ok(None),
                _ => Err(language
                    .text(TextKey::ScreencastScreenshotPickerFailed)
                    .to_owned()),
            };
            this.update(cx, |this, cx| {
                this.record_busy = false;
                match outcome {
                    Ok(Some(path)) => {
                        this.error = None;
                        this.recording = Some(path.display().to_string());
                    }
                    Ok(None) => {}
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Stops the active recording: the session closes the muxer's stdin and
    /// waits for the MP4 trailer off the UI thread, then the saved path lands
    /// in the notice line.
    fn stop_recording(&mut self, cx: &mut Context<Self>) {
        let Some(session) = self.session.clone() else {
            return;
        };
        self.record_busy = true;
        let task = cx.background_executor().spawn(async move {
            session
                .stop_recording()
                .map(|result| result.map(|path| path.display().to_string()))
        });
        cx.spawn(async move |this, cx| {
            let outcome = task.await;
            this.update(cx, |this, cx| {
                this.record_busy = false;
                this.recording = None;
                match outcome {
                    Some(Ok(path)) => {
                        this.error = None;
                        this.notice = Some(
                            this.language
                                .format(TextKey::ScreencastRecordSaved, &[("path", &path)]),
                        );
                    }
                    // The session ended first and already finalized the file;
                    // nothing to report here.
                    Some(Err(error)) => this.error = Some(error),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn send_text(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let text = self.send_input.read(cx).value().to_string();
        if text.trim().is_empty() {
            return;
        }
        if let Some(session) = self.session.as_ref() {
            session.paste_text(&text);
        }
        self.send_input.update(cx, |input, cx| {
            input.set_value("", window, cx);
        });
        cx.notify();
    }

    fn select_quality(&mut self, quality: ScreencastQuality, cx: &mut Context<Self>) {
        if quality == self.quality {
            return;
        }
        self.quality = quality;
        // Encoder options are start-time-only: restart the session when active.
        if self.session.is_some() {
            self.start(cx);
        }
        cx.notify();
    }
}

/// Flattens an adb failure into one bounded line for the error banner (device
/// stderr is external text; the first non-empty line keeps it short).
fn bounded_adb_error(output: &AdbOutput) -> String {
    bounded_error_text(&output.merged())
}

/// Bounds any adb-side failure text to its first non-empty line, 200 chars.
fn bounded_error_text(text: &str) -> String {
    let line = text
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or("adb command failed");
    let mut text: String = line.chars().take(200).collect();
    if line.chars().count() > 200 {
        text.push('…');
    }
    text
}

/// PNG file signature; `screencap -p` must open its stdout stream with it.
const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];

fn is_png(bytes: &[u8]) -> bool {
    bytes.starts_with(&PNG_SIGNATURE)
}

/// Serials may contain `:` (TCP devices); only file-name-safe characters
/// survive sanitization.
fn sanitized_serial(serial: &str) -> String {
    serial
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// `screencap_<sanitized serial>_<unix millis>.png`.
fn suggested_screenshot_name(serial: &str, unix_millis: u128) -> String {
    format!("screencap_{}_{}.png", sanitized_serial(serial), unix_millis)
}

/// `record_<sanitized serial>_<unix millis>.mp4`.
fn suggested_recording_name(serial: &str, unix_millis: u128) -> String {
    format!("record_{}_{}.mp4", sanitized_serial(serial), unix_millis)
}

fn unix_millis() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis())
        .unwrap_or_default()
}

/// Wraps BGRA bytes into GPUI's render image; the byte order matches GPUI's
/// BGRA render format (Skia N32), so no channel swap is needed.
fn to_render_image(frame: BgraFrame) -> RenderImage {
    let width = frame.width.max(1);
    let height = frame.height.max(1);
    let mut pixels = frame.bgra;
    pixels.resize((width as usize) * (height as usize) * 4, 0);
    let buffer = image::ImageBuffer::from_raw(width, height, pixels)
        .unwrap_or_else(|| image::ImageBuffer::new(width, height));
    RenderImage::new([image::Frame::new(buffer)])
}

/// Full-window mirror overlay: the shell mounts it as the last child of the
/// window root, so it covers the title bar and navigation rail and the frame
/// gets every pixel the window has.
impl ScreencastWorkspace {
    pub(crate) fn fullscreen_overlay(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let frame = self.frame.clone();
        let bounds_handle = cx.entity().downgrade();

        let mut surface = div()
            .id("screencast-fullscreen-surface")
            .relative()
            .size_full()
            .child(
                // Same probe contract as the inline canvas: the overlay surface
                // owns the pointer mapping while it is up.
                canvas(
                    move |bounds, _, cx: &mut App| {
                        bounds_handle
                            .update(cx, |this: &mut ScreencastWorkspace, _| {
                                if this.fullscreen {
                                    this.canvas_bounds = Some(bounds);
                                }
                            })
                            .ok();
                    },
                    |_, _, _, _| {},
                )
                .absolute()
                .top_0()
                .left_0()
                .size_full(),
            );
        if let Some(frame) = frame {
            surface = surface.child(
                img(ImageSource::Render(frame))
                    .object_fit(ObjectFit::Contain)
                    .size_full(),
            );
        }
        let surface = Self::mirror_pointer(surface, cx);

        div()
            .id("screencast-fullscreen")
            .absolute()
            .inset_0()
            // Opaque backdrop: the fit letterbox is black like a cinema screen
            // and nothing of the page underneath shows through.
            .bg(rgb(0x000000))
            .track_focus(&self.fullscreen_focus)
            .on_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
                if event.keystroke.key == "escape" {
                    this.exit_fullscreen(window, cx);
                }
            }))
            .child(surface)
            .child(
                div().absolute().top_3().right_3().child(
                    Button::new("screencast-exit-fullscreen")
                        .label(language.text(TextKey::ScreencastExitFullscreen))
                        .on_click(
                            cx.listener(|this, _, window, cx| this.exit_fullscreen(window, cx)),
                        ),
                ),
            )
            // A faint status strip keeps the frame rate readable without
            // taking corner space away from the mirror.
            .child(
                div()
                    .absolute()
                    .bottom_2()
                    .left_0()
                    .right_0()
                    .flex()
                    .justify_center()
                    .child(
                        div()
                            .text_size(px(typography::SUPPORTING))
                            .text_color(rgba(0xffffffcc))
                            .child(format!(
                                "{} · {} fps",
                                self.status_text(language),
                                self.display_fps
                            )),
                    ),
            )
            .into_any_element()
    }
}

impl ScreencastWorkspace {
    /// Pointer mapping of the mirror surface, shared by the inline canvas and
    /// the fullscreen overlay so both inject identically: press/drag/release
    /// map to `ACTION_*`, the wheel to scroll, and a double click expands the
    /// mirror to the whole window.
    fn mirror_pointer(area: Stateful<Div>, cx: &mut Context<Self>) -> Stateful<Div> {
        area.on_mouse_down(
            MouseButton::Left,
            cx.listener(|this, event: &MouseDownEvent, _, _| {
                this.pointer_pressed = true;
                this.send_touch(ACTION_DOWN, event.position, 1.0, BUTTON_PRIMARY);
            }),
        )
        .on_mouse_down(
            MouseButton::Right,
            cx.listener(|this, event: &MouseDownEvent, _, _| {
                this.send_touch(ACTION_DOWN, event.position, 1.0, BUTTON_SECONDARY);
            }),
        )
        .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, _| {
            let (action, pressure, buttons) = if this.pointer_pressed {
                (ACTION_MOVE, 1.0, BUTTON_PRIMARY)
            } else {
                (ACTION_HOVER_MOVE, 0.0, 0)
            };
            this.send_touch(action, event.position, pressure, buttons);
        }))
        .on_mouse_up(
            MouseButton::Left,
            cx.listener(|this, event: &MouseUpEvent, _, cx| {
                this.pointer_pressed = false;
                this.send_touch(ACTION_UP, event.position, 0.0, 0);
                cx.notify();
            }),
        )
        .on_mouse_up(
            MouseButton::Right,
            cx.listener(|this, event: &MouseUpEvent, _, _| {
                this.send_touch(ACTION_UP, event.position, 0.0, 0);
            }),
        )
        .on_scroll(cx.listener(|this, event: &ScrollEvent, _, _| {
            let (scroll_x, scroll_y) = match event.delta {
                ScrollDelta::Lines(delta) => (delta.x, delta.y),
                ScrollDelta::Pixels(delta) => (f32::from(delta.x) / 40., f32::from(delta.y) / 40.),
            };
            this.send_scroll(event.position, scroll_x, scroll_y);
        }))
        .on_click(cx.listener(|this, event: &ClickEvent, window, cx| {
            if event.click_count() >= 2 {
                this.toggle_fullscreen(window, cx);
            }
        }))
    }
}

impl Render for ScreencastWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.language;
        let colors = studio::StudioColors::from_theme(cx);
        let theme = cx.theme().clone();
        let state = self.state.clone();
        let is_running = matches!(state, ScrcpySessionState::Running { .. });
        let is_connecting = state == ScrcpySessionState::Connecting;
        let frame = self.frame.clone();

        // Bounds probe: capture the canvas rect on first layout so pointer
        // events map onto device coordinates.
        let bounds_handle = cx.entity().downgrade();
        let mut canvas_area = div()
            .id("screencast-canvas")
            .relative()
            .flex_1()
            .min_h(px(280.))
            .overflow_hidden()
            .rounded(px(8.))
            .border_1()
            .border_color(colors.border)
            .bg(colors.panel)
            .child(
                canvas(
                    move |bounds, _, cx: &mut App| {
                        bounds_handle
                            .update(cx, |this: &mut ScreencastWorkspace, _| {
                                // The fullscreen overlay reports the bounds that
                                // pointer events are mapped against; the inline
                                // canvas must not clobber them meanwhile.
                                if !this.fullscreen {
                                    this.canvas_bounds = Some(bounds);
                                }
                            })
                            .ok();
                    },
                    |_, _, _, _| {},
                )
                // Probe out of flow: in flow it would either collapse to zero
                // height (degenerate bounds → every touch dropped) or, sized
                // full, push the video img below the visible area. Anchored
                // absolute it overlays the whole canvas and reports the real
                // bounds.
                .absolute()
                .top_0()
                .left_0()
                .size_full(),
            )
            .when(is_running && frame.is_some(), |area| {
                area.child(
                    img(ImageSource::Render(frame.expect("frame checked")))
                        .object_fit(ObjectFit::Contain)
                        .size_full(),
                )
            })
            .when(is_connecting, |area| {
                area.child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            div()
                                .text_size(px(typography::BODY))
                                .text_color(theme.muted_foreground)
                                .child(language.text(TextKey::ScreencastConnecting)),
                        ),
                )
            })
            .when(is_running, |area| Self::mirror_pointer(area, cx));

        match &state {
            ScrcpySessionState::Idle => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastIdleTitle)),
                        )
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(theme.muted_foreground)
                                .child(language.text(TextKey::ScreencastIdleHint)),
                        )
                        .child(
                            Button::new("screencast-start-overlay")
                                .primary()
                                .label(language.text(TextKey::ScreencastStart))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            ScrcpySessionState::Disconnected => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastDisconnected)),
                        )
                        .child(
                            Button::new("screencast-reconnect")
                                .primary()
                                .label(language.text(TextKey::ScreencastReconnect))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            ScrcpySessionState::Failed(message) => {
                canvas_area = canvas_area.child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap_2()
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .child(language.text(TextKey::ScreencastFailed)),
                        )
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(theme.warning)
                                .child(crate::studio::selectable(message.clone())),
                        )
                        .child(
                            Button::new("screencast-retry")
                                .primary()
                                .label(language.text(TextKey::ScreencastRetry))
                                .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                        ),
                );
            }
            _ => {}
        }

        let mut body = studio::page(&colors)
            .child(self.toolbar(language, is_running, &theme, cx))
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(crate::studio::selectable(self.status_text(language))),
            );

        if let Some(notice) = &self.notice {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(crate::studio::selectable(notice.clone())),
            );
        }

        if let Some(error) = &self.error {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.warning)
                    .child(crate::studio::selectable(
                        language.format(TextKey::ScreencastErrorPrefix, &[("error", error)]),
                    )),
            );
        }

        body = body
            .child(canvas_area)
            .child(self.send_bar(language, &colors, is_running, cx));
        body
    }
}

impl ScreencastWorkspace {
    fn toolbar(
        &self,
        language: Language,
        is_running: bool,
        theme: &gpui_kit::component::Theme,
        cx: &Context<Self>,
    ) -> AnyElement {
        // Device control stays usable whenever a device is connected: while
        // casting the keys ride the scrcpy control socket, otherwise they fall
        // back to `adb shell input` (see `adb_inject_keycode`).
        let has_device = self.selected.is_some();
        let mut row = div().flex().flex_wrap().items_center().gap_2();

        for (label, keycode, key_id) in [
            (TextKey::ScreencastBack, KEYCODE_BACK, "nav-back"),
            (TextKey::ScreencastHome, KEYCODE_HOME, "nav-home"),
            (
                TextKey::ScreencastRecents,
                KEYCODE_APP_SWITCH,
                "nav-recents",
            ),
        ] {
            row = row.child(
                Button::new(key_id)
                    .label(language.text(label))
                    .disabled(!has_device)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.tap_navigation_key(keycode, cx);
                    })),
            );
        }
        row = row
            .child(
                Button::new("screencast-power")
                    .label(language.text(TextKey::ScreencastPowerKey))
                    .disabled(!has_device)
                    .on_click(cx.listener(|this, _, _, cx| this.toggle_power_key(cx))),
            )
            .child(
                Button::new("screencast-screen")
                    .label(if self.screen_off {
                        language.text(TextKey::ScreencastScreenOn)
                    } else {
                        language.text(TextKey::ScreencastScreenOff)
                    })
                    .disabled(!has_device)
                    .on_click(cx.listener(|this, _, _, cx| this.toggle_screen_power(cx))),
            )
            .child(
                Button::new("screencast-screenshot")
                    .label(language.text(TextKey::ScreencastScreenshot))
                    .disabled(!has_device || self.screenshot_busy)
                    .on_click(cx.listener(|this, _, _, cx| this.capture_screenshot(cx))),
            )
            .child(if self.recording.is_some() {
                Button::new("screencast-record")
                    .label(language.text(TextKey::ScreencastRecordStop))
                    .disabled(self.record_busy)
                    .on_click(cx.listener(|this, _, _, cx| this.stop_recording(cx)))
            } else {
                Button::new("screencast-record")
                    .label(language.text(TextKey::ScreencastRecord))
                    .disabled(!is_running || self.record_busy)
                    .on_click(cx.listener(|this, _, _, cx| this.start_recording(cx)))
            });

        // Device picker: compact toggle buttons over the online devices; the
        // picker is locked while a session runs.
        for (index, device) in self.devices.iter().enumerate() {
            let is_selected = self.selected.as_deref() == Some(device.serial.as_str());
            let serial = device.serial.clone();
            row = row.child(
                Button::new(("screencast-device", index))
                    .selected(is_selected)
                    .label(device.display_name.clone())
                    .disabled(is_running)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.selected = Some(serial.clone());
                        cx.notify();
                    })),
            );
        }
        if self.devices.is_empty() {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(theme.muted_foreground)
                    .child(language.text(TextKey::ScreencastNoDevice)),
            );
        }

        row = row.child(
            div()
                .text_size(px(typography::SUPPORTING))
                .text_color(theme.muted_foreground)
                .child(language.text(TextKey::ScreencastQuality)),
        );
        for preset in [
            ScreencastQuality::Fluent,
            ScreencastQuality::Hd,
            ScreencastQuality::Native,
        ] {
            let label = format!("{} · {}fps", preset.label(), preset.max_fps());
            row = row.child(
                Button::new(("screencast-quality", preset as usize))
                    .selected(self.quality == preset)
                    .label(label)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.select_quality(preset, cx);
                    })),
            );
        }
        row = row.child(
            Button::new("screencast-refresh")
                .label(language.text(TextKey::ScreencastRefresh))
                .disabled(is_running)
                .on_click(cx.listener(|this, _, _, cx| this.refresh_devices(cx))),
        );
        if is_running {
            row = row.child(
                Button::new("screencast-fullscreen")
                    .label(language.text(TextKey::ScreencastFullscreen))
                    .on_click(
                        cx.listener(|this, _, window, cx| this.toggle_fullscreen(window, cx)),
                    ),
            );
            row = row.child(
                Button::new("screencast-stop")
                    .label(language.text(TextKey::ScreencastStop))
                    .on_click(cx.listener(|this, _, _, cx| this.stop(cx))),
            );
        }
        row.into_any_element()
    }

    fn status_text(&self, language: Language) -> String {
        match &self.state {
            ScrcpySessionState::Idle => language.text(TextKey::ScreencastIdleHint).to_owned(),
            ScrcpySessionState::Connecting => {
                language.text(TextKey::ScreencastEstablishing).to_owned()
            }
            ScrcpySessionState::Running {
                device_name,
                width,
                height,
            } => {
                let base = language.format(
                    TextKey::ScreencastCasting,
                    &[
                        ("device", &device_name),
                        ("width", &width.to_string()),
                        ("height", &height.to_string()),
                    ],
                );
                if self.display_fps > 0 {
                    format!("{base} · {} fps", self.display_fps)
                } else {
                    base
                }
            }
            ScrcpySessionState::Disconnected => {
                language.text(TextKey::ScreencastDisconnected).to_owned()
            }
            ScrcpySessionState::Failed(message) => {
                language.format(TextKey::ScreencastErrorPrefix, &[("error", message)])
            }
        }
    }

    fn send_bar(
        &self,
        language: Language,
        colors: &studio::StudioColors,
        is_running: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        div()
            .flex()
            .items_center()
            .gap_2()
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::ScreencastSendHint)),
            )
            .child(
                Input::new(&self.send_input)
                    .disabled(!is_running)
                    .flex_1()
                    .h(px(28.))
                    .rounded(px(4.))
                    .bg(colors.field)
                    .border_1()
                    .border_color(colors.border),
            )
            .child(
                Button::new("screencast-send")
                    .label(language.text(TextKey::ScreencastSend))
                    .disabled(!is_running)
                    .on_click(cx.listener(|this, _, window, cx| this.send_text(window, cx))),
            )
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn png_signature_check_accepts_real_signature_and_rejects_truncated_output() {
        let mut png = PNG_SIGNATURE.to_vec();
        png.extend_from_slice(b"rest of the file");
        assert!(is_png(&png));
        assert!(is_png(&PNG_SIGNATURE));
        assert!(!is_png(&[]));
        assert!(!is_png(&PNG_SIGNATURE[..4]));
        // `adb shell` CRLF corruption turns 0x0a into 0x0d 0x0a.
        let mut corrupted = PNG_SIGNATURE;
        corrupted[6] = 0x0d;
        assert!(!is_png(&corrupted));
    }

    #[::core::prelude::v1::test]
    fn suggested_screenshot_name_sanitizes_tcp_serials_and_keeps_extension() {
        assert_eq!(
            suggested_screenshot_name("emulator-5554", 1_763_948_379_123),
            "screencap_emulator-5554_1763948379123.png"
        );
        // TCP serials contain a colon, which is invalid in Windows file names.
        assert_eq!(
            suggested_screenshot_name("192.168.1.5:5555", 42),
            "screencap_192.168.1.5_5555_42.png"
        );
    }

    #[::core::prelude::v1::test]
    fn suggested_recording_name_uses_the_mp4_extension() {
        assert_eq!(
            suggested_recording_name("FAKE001", 1_763_948_379_123),
            "record_FAKE001_1763948379123.mp4"
        );
        assert_eq!(
            suggested_recording_name("192.168.1.5:5555", 7),
            "record_192.168.1.5_5555_7.mp4"
        );
    }
}
