//! Session state machine, ported from `ScrcpySession` (spec §6): the full §1.2
//! startup sequence, the §6.1 state enum (`Idle → Connecting → Running →
//! Disconnected`, `Failed` on startup errors), and the §6.2 thread model —
//! one session IO thread running the video decode loop plus a control-read
//! thread that drains device messages.
//!
//! Deviations forced by the blocking (non-async) Rust model, behavior-
//! equivalent: the coroutine scope becomes a dedicated thread per start; the
//! frame `SharedFlow(extraBufferCapacity=4)` becomes a bounded channel (4) —
//! when the consumer is slower than decode, arriving frames are dropped and
//! the newest one wins (Kotlin suspends the emitter instead); `finish` sets
//! the terminal state before the adb cleanup runs (Kotlin ran cleanup first,
//! keeping the old state visible ~1 s longer).
//!
//! ## Keeping a live mirror live
//!
//! The ffmpeg CLI backend decodes one access unit behind the encoder: the
//! H.264 parser only ends a frame when the *next* frame's start code arrives
//! (see `decoder`). Two consequences drive this module's design:
//!
//! 1. Decoding must never block the socket read. [`FrameDecoder::decode`] only
//!    waits a few milliseconds for a frame that is already in flight, so the
//!    loop keeps draining the tunnel instead of pacing itself to the display.
//! 2. When the device goes quiet one access unit can stay undecoded forever —
//!    a static screen would never paint. Each *burst* of device activity
//!    therefore starts with one `RESET_VIDEO`, which makes the server re-emit
//!    the current screen as a fresh IDR; that extra unit releases the held one.
//!    One nudge per burst (plus the startup assist) is enough, and a settled
//!    screen costs nothing.
//!
//! If decoding cannot keep up, the loop drops buffered access units until the
//! next keyframe, resets the decoder, re-feeds the cached codec config and
//! requests an IDR — bounded catch-up instead of an ever-growing delay.
//!
//! [`ScrcpySession::metrics`] exposes the counters that make all of the above
//! measurable (startup latency, frames, drops, nudges, decoder utilization).
//!
//! The launch pipeline sits behind the [`LaunchEnvironment`] trait so tests
//! can inject a fake launcher/adb (spec §10 `ScrcpySessionTest`).

use crate::control::{
    ControlResult, ControlWriter, KEYCODE_APP_SWITCH, KEYCODE_BACK, KEYCODE_HOME, POINTER_ID_MOUSE,
};
use crate::decoder::{BgraFrame, FrameDecoder};
use crate::device_message::{DeviceMessageReader, drain_device_messages};
use crate::launcher::{ACCEPT_TIMEOUT_MS, AdbRunner, LaunchError, LaunchedServer};
use crate::quality::ScreencastQuality;
use crate::record::VideoRecorder;
use crate::video_stream::{StreamReader, VIDEO_CODEC_H264};
use std::io::ErrorKind;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, SyncSender, TrySendError, sync_channel};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// `SOCKET_BUFFER_SIZE = 2 MiB` (spec §6 constants; §1.2 step 7 receive buffer).
pub const SOCKET_BUFFER_SIZE: usize = 2 << 20;
/// Frame channel capacity (`extraBufferCapacity = 4`, spec §6.2).
pub const FRAME_CHANNEL_CAPACITY: usize = 4;
/// Accept-poll granularity while waiting for the two server connections.
const ACCEPT_POLL: Duration = Duration::from_millis(5);

/// Quiet gap that separates two bursts of device activity. The first access
/// unit after such a gap starts a new burst and gets the "release the held
/// unit" nudge; access units inside a burst are already released by their
/// successor.
const BURST_QUIET: Duration = Duration::from_millis(300);
/// Never nudge more often than this: a nudge costs the device a full IDR.
const NUDGE_MIN_GAP: Duration = Duration::from_millis(400);
/// Startup assist: if no frame has been painted this long after `Running`,
/// nudge regardless of burst detection (a device that starts with a static
/// screen still sends its initial keyframe as a burst, so this is a fallback).
const STARTUP_ASSIST_AFTER: Duration = Duration::from_millis(1_200);
/// Startup assist cap: at most this many fallback nudges before giving up and
/// leaving the session in its (already reported) waiting state.
const STARTUP_ASSIST_LIMIT: u32 = 3;
/// Access units that may pile up behind the decoder before the session treats
/// the pipeline as behind. A healthy stream keeps exactly the decoder's
/// hold-back (2 access units) in flight; beyond this the mirror is falling
/// further behind in real time.
///
/// The threshold scales with the stream rate (a quarter of the access units
/// seen in the window): at 30 fps a shortfall of 8 is a real stall, while at
/// 60 fps a decoder running at 50 fps is merely slower than the device — that
/// should degrade to 50 fps, not trigger a keyframe hunt that costs far more
/// than it recovers.
const BACKLOG_LIMIT_MIN: i64 = 8;
/// Sampling window for the backlog measurement.
const BACKLOG_WINDOW: Duration = Duration::from_secs(1);

/// Snapshot of the mirror pipeline counters.
///
/// Everything that decides whether the mirror feels live is measurable here:
/// how long the first frame took, whether frames are still arriving, how many
/// were dropped to keep latency flat, and how much of the loop the decoder
/// owns.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ScreencastMetrics {
    /// Access units received from the device.
    pub access_units: u64,
    /// Video bytes received from the device (access units + config packets).
    pub bytes_received: u64,
    /// Frames the decoder produced.
    pub frames_decoded: u64,
    /// Frames handed to the display channel.
    pub frames_delivered: u64,
    /// Decoded frames displaced by a newer frame, or dropped because the
    /// display channel was full (both keep latency flat).
    pub frames_dropped: u64,
    /// `RESET_VIDEO` nudges sent (one per activity burst, plus startup assist).
    pub nudges: u64,
    /// Catch-up resynchronizations (buffered access units skipped to the next
    /// keyframe).
    pub resyncs: u64,
    /// Milliseconds from `start()` to the first decoded frame (0 = none yet).
    pub first_frame_ms: u64,
    /// Unix milliseconds of the last decoded frame (0 = none yet).
    pub last_frame_ms: u64,
    /// Cumulative milliseconds spent inside the decoder.
    pub decode_busy_ms: u64,
    /// Cumulative milliseconds spent reading the video stream.
    pub read_busy_ms: u64,
    /// Milliseconds from `start()` until push + reverse + server spawn finished.
    pub launch_ms: u64,
    /// Milliseconds from `start()` until both sockets were accepted (the
    /// device-side server boot dominates a cold start).
    pub connected_ms: u64,
    /// Milliseconds from `start()` until the handshake delivered the device
    /// metadata (encoder configured on the device).
    pub handshake_ms: u64,
    /// Milliseconds from `start()` until the session reached `Running`.
    pub running_ms: u64,
    /// Unix milliseconds when `start()` reset these counters.
    pub started_at_ms: u64,
}

impl ScreencastMetrics {
    /// Milliseconds since the last decoded frame, or `None` before the first.
    pub fn since_last_frame(&self) -> Option<u64> {
        if self.last_frame_ms == 0 {
            return None;
        }
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|elapsed| elapsed.as_millis() as u64)
            .unwrap_or(0);
        Some(now.saturating_sub(self.last_frame_ms))
    }
}

/// Atomic counters behind [`ScreencastMetrics`]; the session thread writes
/// them, any thread can snapshot them.
#[derive(Debug, Default)]
struct MetricCounters {
    access_units: AtomicU64,
    bytes_received: AtomicU64,
    frames_decoded: AtomicU64,
    frames_delivered: AtomicU64,
    frames_dropped: AtomicU64,
    nudges: AtomicU64,
    resyncs: AtomicU64,
    started_at_ms: AtomicU64,
    first_frame_ms: AtomicU64,
    last_frame_ms: AtomicU64,
    decode_busy_ms: AtomicU64,
    read_busy_ms: AtomicU64,
    launch_ms: AtomicU64,
    connected_ms: AtomicU64,
    handshake_ms: AtomicU64,
    running_ms: AtomicU64,
}

impl MetricCounters {
    fn snapshot(&self) -> ScreencastMetrics {
        let load = |counter: &AtomicU64| counter.load(Ordering::Relaxed);
        ScreencastMetrics {
            access_units: load(&self.access_units),
            bytes_received: load(&self.bytes_received),
            frames_decoded: load(&self.frames_decoded),
            frames_delivered: load(&self.frames_delivered),
            frames_dropped: load(&self.frames_dropped),
            nudges: load(&self.nudges),
            resyncs: load(&self.resyncs),
            first_frame_ms: load(&self.first_frame_ms),
            last_frame_ms: load(&self.last_frame_ms),
            decode_busy_ms: load(&self.decode_busy_ms),
            read_busy_ms: load(&self.read_busy_ms),
            launch_ms: load(&self.launch_ms),
            connected_ms: load(&self.connected_ms),
            handshake_ms: load(&self.handshake_ms),
            running_ms: load(&self.running_ms),
            started_at_ms: load(&self.started_at_ms),
        }
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

/// Session state (spec §6.1).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum ScrcpySessionState {
    /// No session.
    #[default]
    Idle,
    /// push / tunnel / handshake in progress (not interruptible).
    Connecting,
    /// Video stream + control available.
    Running {
        device_name: String,
        width: u32,
        height: u32,
    },
    /// Session ended (device disconnected / server exited).
    Disconnected,
    /// Startup failed; the message is the user-visible failure reason.
    Failed(String),
}

impl ScrcpySessionState {
    /// Connecting or Running (spec §6.2 `isActive`).
    pub fn is_active(&self) -> bool {
        matches!(self, Self::Connecting | Self::Running { .. })
    }

    /// Whether `finish` may overwrite this state (Idle/Disconnected/Failed
    /// are terminal; only an explicit new `start` may leave them).
    fn is_locked(&self) -> bool {
        !self.is_active()
    }
}

/// Decoder backend factory (spec §9.3 `resolveDecoderBackend`); a failure
/// string becomes the session's `Failed` message.
pub type DecoderFactory = Arc<dyn Fn() -> Result<Box<dyn FrameDecoder>, String> + Send + Sync>;

/// The default backend: the system `ffmpeg` CLI decoder.
pub fn default_decoder_factory() -> DecoderFactory {
    Arc::new(|| {
        crate::decoder::FfmpegCliDecoder::new()
            .map(|decoder| Box::new(decoder) as Box<dyn FrameDecoder>)
            .map_err(|error| error.to_string())
    })
}

/// Per-session configuration.
pub struct SessionConfig {
    pub serial: String,
    pub quality: ScreencastQuality,
    /// Explicit adb executable; `None` uses the shared locator chain.
    pub adb_executable: Option<PathBuf>,
    pub decoder_factory: DecoderFactory,
}

impl SessionConfig {
    pub fn new(serial: impl Into<String>, quality: ScreencastQuality) -> Self {
        Self {
            serial: serial.into(),
            quality,
            adb_executable: None,
            decoder_factory: default_decoder_factory(),
        }
    }
}

/// Host side of the launch pipeline (spec §1.2 steps 1-5 plus §1.4/§6.4
/// teardown). The real implementation forwards to [`crate::launcher`]; tests
/// inject a recording fake.
pub trait LaunchEnvironment: Send + Sync + 'static {
    /// ServerSocket(0) on the loopback interface (§1.2 step 1).
    fn bind_listener(&self) -> std::io::Result<TcpListener>;
    /// push + reverse + device server spawn (§1.2 steps 2-5).
    fn launch(
        &self,
        serial: &str,
        launched: &mut LaunchedServer,
        quality: ScreencastQuality,
    ) -> Result<(), LaunchError>;
    /// §1.4 rollback after a failed launch step.
    fn rollback(&self, serial: &str, launched: &LaunchedServer);
    /// §6.4 cleanup after the session ended (cancel shell → 500 ms →
    /// reverse --remove → rm -f → temp jar).
    fn cleanup(&self, serial: &str, launched: &LaunchedServer);
}

/// Production environment bound to one adb executable.
pub struct AdbLaunchEnvironment {
    runner: AdbRunner,
}

impl LaunchEnvironment for AdbLaunchEnvironment {
    fn bind_listener(&self) -> std::io::Result<TcpListener> {
        TcpListener::bind("127.0.0.1:0")
    }

    fn launch(
        &self,
        serial: &str,
        launched: &mut LaunchedServer,
        quality: ScreencastQuality,
    ) -> Result<(), LaunchError> {
        crate::launcher::launch(&self.runner, serial, launched, quality)
    }

    fn rollback(&self, serial: &str, launched: &LaunchedServer) {
        crate::launcher::rollback(&self.runner, serial, launched);
    }

    fn cleanup(&self, serial: &str, launched: &LaunchedServer) {
        crate::launcher::cleanup(&self.runner, serial, launched);
    }
}

/// Frame stream handle returned by [`ScrcpySession::start`]; a dead receiver
/// (sender dropped) means no active run owns the channel.
pub type FrameReceiver = Receiver<BgraFrame>;

struct SessionInner {
    config: SessionConfig,
    environment: Arc<dyn LaunchEnvironment>,
    state: Mutex<ScrcpySessionState>,
    /// Buffered control writer over the control socket (`None` when not
    /// Running → sends are silently dropped, §6.3).
    writer: Mutex<Option<ControlWriter<TcpStream>>>,
    /// Shutdown handles so `finish` can unblock the readers from any thread.
    video_socket: Mutex<Option<TcpStream>>,
    control_socket: Mutex<Option<TcpStream>>,
    frames_sender: Mutex<Option<SyncSender<BgraFrame>>>,
    /// Pipeline counters (startup latency, frames, drops, decoder load).
    metrics: Arc<MetricCounters>,
    /// Active MP4 recording (§6.2 video loop tees packets into it); taken by
    /// `stop_recording` or finalized off-thread in `finish`.
    recorder: Mutex<Option<VideoRecorder>>,
    /// Registered only after a successful launch (Kotlin's `launched` field).
    launched: Mutex<Option<LaunchedServer>>,
    /// `startGuard` (§6.2): CAS'd false→true for the whole run, released when
    /// the session thread unwinds.
    start_guard: AtomicBool,
    stop_requested: AtomicBool,
}

/// Shared session handle (`Clone` aliases the same state machine).
#[derive(Clone)]
pub struct ScrcpySession {
    inner: Arc<SessionInner>,
}

impl ScrcpySession {
    /// Production constructor: locates adb through the shared locator chain.
    pub fn new(config: SessionConfig) -> Result<Self, String> {
        let runner = AdbRunner::locate(config.adb_executable.as_deref())?;
        Ok(Self::with_environment(
            config,
            Arc::new(AdbLaunchEnvironment { runner }),
        ))
    }

    /// Test/assembly constructor with an injected launch environment.
    pub fn with_environment(
        config: SessionConfig,
        environment: Arc<dyn LaunchEnvironment>,
    ) -> Self {
        Self {
            inner: Arc::new(SessionInner {
                config,
                environment,
                state: Mutex::new(ScrcpySessionState::Idle),
                writer: Mutex::new(None),
                video_socket: Mutex::new(None),
                control_socket: Mutex::new(None),
                frames_sender: Mutex::new(None),
                metrics: Arc::new(MetricCounters::default()),
                launched: Mutex::new(None),
                recorder: Mutex::new(None),
                start_guard: AtomicBool::new(false),
                stop_requested: AtomicBool::new(false),
            }),
        }
    }

    pub fn state(&self) -> ScrcpySessionState {
        lock(&self.inner.state).clone()
    }

    /// Connecting or Running (§6.2 `isActive`).
    pub fn is_active(&self) -> bool {
        self.state().is_active()
    }

    /// Starts a session run (§6.2): the start guard makes this idempotent —
    /// a call while Connecting/Running is a no-op that returns a dead frame
    /// receiver. The returned receiver yields decoded BGRA frames (≤ 4
    /// buffered; slower consumers cause drops, keeping only recent frames).
    pub fn start(&self) -> FrameReceiver {
        // CAS the guard first: exactly one caller enters run_session.
        if self
            .inner
            .start_guard
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return dead_receiver();
        }
        if self.is_active() {
            self.inner.start_guard.store(false, Ordering::SeqCst);
            return dead_receiver();
        }
        let (sender, receiver) = sync_channel(FRAME_CHANNEL_CAPACITY);
        *lock(&self.inner.frames_sender) = Some(sender);
        self.set_state(ScrcpySessionState::Connecting);
        self.inner.stop_requested.store(false, Ordering::SeqCst);
        // Fresh run: reset the pipeline counters so `metrics()` describes this
        // session only (startup latency, frames, drops).
        self.inner
            .metrics
            .started_at_ms
            .store(now_ms(), Ordering::Relaxed);
        for counter in [
            &self.inner.metrics.access_units,
            &self.inner.metrics.bytes_received,
            &self.inner.metrics.frames_decoded,
            &self.inner.metrics.frames_delivered,
            &self.inner.metrics.frames_dropped,
            &self.inner.metrics.nudges,
            &self.inner.metrics.resyncs,
            &self.inner.metrics.first_frame_ms,
            &self.inner.metrics.last_frame_ms,
            &self.inner.metrics.decode_busy_ms,
            &self.inner.metrics.read_busy_ms,
            &self.inner.metrics.launch_ms,
            &self.inner.metrics.connected_ms,
            &self.inner.metrics.handshake_ms,
            &self.inner.metrics.running_ms,
        ] {
            counter.store(0, Ordering::Relaxed);
        }
        let inner = self.inner.clone();
        let spawned = std::thread::Builder::new()
            .name("aps-screencast-session".to_owned())
            .spawn(move || run_session(inner));
        if spawned.is_err() {
            *lock(&self.inner.frames_sender) = None;
            self.inner.start_guard.store(false, Ordering::SeqCst);
            self.set_state(ScrcpySessionState::Failed(
                "unable to spawn the session thread".to_owned(),
            ));
            return dead_receiver();
        }
        receiver
    }

    /// `stop() = finish(Idle)` (§6.4).
    pub fn stop(&self) {
        self.inner.stop_requested.store(true, Ordering::SeqCst);
        self.finish(ScrcpySessionState::Idle);
    }

    /// Snapshot of the mirror pipeline counters — the numbers that decide
    /// whether the mirror is live (first-frame latency, frames, drops,
    /// decoder load). Safe to call from any thread, including while running.
    pub fn metrics(&self) -> ScreencastMetrics {
        self.inner.metrics.snapshot()
    }

    // ---- control senders (§6.3: writer null → silent drop; write error →
    // Disconnected). Touch/scroll dimensions come from the Running state
    // (§8.2); anything sent while not Running is dropped. ----

    /// `session.sendTouch` (§8.2): `pointerId = POINTER_ID_MOUSE`,
    /// `actionButton = 0` (§A.4).
    pub fn send_touch(&self, action: u8, x: i32, y: i32, pressure: f32, buttons: i32) {
        let Some((width, height)) = self.running_size() else {
            return;
        };
        self.write_control(|writer| {
            writer.inject_touch(
                action,
                POINTER_ID_MOUSE,
                x,
                y,
                width as u16,
                height as u16,
                pressure,
                0,
                buttons,
            )
        });
    }

    /// `session.sendScroll` (§8.2): scroll values are already negated by the
    /// UI layer; buttons are always 0.
    pub fn send_scroll(&self, x: i32, y: i32, scroll_x: f32, scroll_y: f32) {
        let Some((width, height)) = self.running_size() else {
            return;
        };
        self.write_control(|writer| {
            writer.inject_scroll(x, y, width as u16, height as u16, scroll_x, scroll_y, 0)
        });
    }

    pub fn send_keycode(&self, action: u8, keycode: i32) {
        self.write_control(|writer| writer.inject_keycode(action, keycode, 0, 0));
    }

    /// Down + up pair (§10 test ③).
    pub fn tap_keycode(&self, keycode: i32) {
        self.write_control(|writer| writer.tap_keycode(keycode));
    }

    pub fn back(&self) {
        self.tap_keycode(KEYCODE_BACK);
    }

    pub fn home(&self) {
        self.tap_keycode(KEYCODE_HOME);
    }

    pub fn recents(&self) {
        self.tap_keycode(KEYCODE_APP_SWITCH);
    }

    /// `pasteToDevice` (§A.11): setClipboard with `paste = true`, sequence 0.
    pub fn paste_text(&self, text: &str) {
        self.write_control(|writer| writer.set_clipboard(0, true, text));
    }

    pub fn set_clipboard(&self, sequence: u64, paste: bool, text: &str) {
        self.write_control(|writer| writer.set_clipboard(sequence, paste, text));
    }

    pub fn get_clipboard(&self, copy_key: u8) {
        self.write_control(|writer| writer.get_clipboard(copy_key));
    }

    pub fn back_or_screen_on(&self, action: u8) {
        self.write_control(|writer| writer.back_or_screen_on(action));
    }

    pub fn expand_notification_panel(&self) {
        self.write_control(|writer| writer.expand_notification_panel());
    }

    pub fn collapse_panels(&self) {
        self.write_control(|writer| writer.collapse_panels());
    }

    pub fn rotate_device(&self) {
        self.write_control(|writer| writer.rotate_device());
    }

    /// RESET_VIDEO: the server re-sends its config packet (§A.10).
    pub fn reset_video(&self) {
        self.write_control(|writer| writer.reset_video());
    }

    /// `toggleScreenPower` target: `on` lights the display without sleeping.
    pub fn set_display_power(&self, on: bool) {
        self.write_control(|writer| writer.set_display_power(on));
    }

    // ---- recording (host-side MP4 remux tee) ----

    /// Whether a recording is currently attached to the video loop.
    pub fn is_recording(&self) -> bool {
        lock(&self.inner.recorder).is_some()
    }

    /// Starts recording the raw video stream into `path` (usually `.mp4`).
    /// Requires a Running session; sends `RESET_VIDEO` so a mid-session start
    /// always begins on the server's re-sent codec config + an IDR.
    pub fn start_recording(&self, path: &Path) -> Result<(), String> {
        if !matches!(self.state(), ScrcpySessionState::Running { .. }) {
            return Err("the screencast session is not running".to_owned());
        }
        let mut guard = lock(&self.inner.recorder);
        if guard.is_some() {
            return Err("a recording is already active".to_owned());
        }
        let executable = crate::decoder::discover_executable().ok_or_else(|| {
            "ffmpeg executable not found; set FFMPEG_EXECUTABLE or install ffmpeg on PATH"
                .to_owned()
        })?;
        // Seeded framerate: the stream is VFR, so active periods play
        // real-time and idle stretches compress (see `record`).
        let recorder = VideoRecorder::spawn(
            &executable,
            path.to_path_buf(),
            self.inner.config.quality.max_fps(),
        )?;
        *guard = Some(recorder);
        drop(guard);
        self.write_control(|writer| writer.reset_video());
        Ok(())
    }

    /// Stops the active recording: closes the muxer's stdin and waits (bounded)
    /// for the MP4 trailer. `None` when nothing is recording (already finalized
    /// by a session end).
    pub fn stop_recording(&self) -> Option<Result<PathBuf, String>> {
        let recorder = lock(&self.inner.recorder).take()?;
        Some(recorder.finish())
    }

    // ---- internals ----

    fn running_size(&self) -> Option<(u32, u32)> {
        match self.state() {
            ScrcpySessionState::Running { width, height, .. } => Some((width, height)),
            _ => None,
        }
    }

    fn set_state(&self, state: ScrcpySessionState) {
        *lock(&self.inner.state) = state;
    }

    /// §6.3: writer absent (not Running) → silent drop; write failure →
    /// `finish(Disconnected)`.
    fn write_control(
        &self,
        message: impl FnOnce(&mut ControlWriter<TcpStream>) -> ControlResult<()>,
    ) {
        let mut guard = lock(&self.inner.writer);
        let Some(writer) = guard.as_mut() else {
            return;
        };
        if let Err(error) = message(writer) {
            drop(guard);
            eprintln!("[screencast] control write failed: {error}");
            self.finish(ScrcpySessionState::Disconnected);
        }
    }

    fn finish(&self, final_state: ScrcpySessionState) -> bool {
        self.inner.finish(final_state)
    }
}

impl SessionInner {
    fn set_state(&self, state: ScrcpySessionState) {
        *lock(&self.state) = state;
    }

    /// Best-effort `RESET_VIDEO` on the control socket: the server re-encodes
    /// the current screen as a fresh IDR. Used to release the last access unit
    /// the decoder pipeline is holding (see the module docs) and as the
    /// resync point after a catch-up.
    fn request_reset_video(&self) {
        if let Some(writer) = lock(&self.writer).as_mut() {
            let _ = writer.reset_video();
        }
    }

    /// §6.4 finish: close writer → video socket → control socket (the listener
    /// is released when the session thread unwinds — std cannot shutdown a
    /// `TcpListener`), disconnect the frame channel, then run the adb cleanup
    /// on an independent thread so it never blocks or dies with the session
    /// thread. Returns whether a terminal transition happened.
    fn finish(&self, final_state: ScrcpySessionState) -> bool {
        if let Some(writer) = lock(&self.writer).take() {
            let stream = writer.into_inner();
            let _ = stream.shutdown(Shutdown::Both);
        }
        if let Some(video) = lock(&self.video_socket).take() {
            let _ = video.shutdown(Shutdown::Both);
        }
        if let Some(control) = lock(&self.control_socket).take() {
            let _ = control.shutdown(Shutdown::Both);
        }
        let transitioned = {
            let mut state = lock(&self.state);
            if state.is_locked() {
                false
            } else {
                if let ScrcpySessionState::Failed(message) = &final_state {
                    eprintln!("[screencast] session failed: {message}");
                }
                *state = final_state;
                true
            }
        };
        if transitioned {
            *lock(&self.frames_sender) = None;
            // Finalize a live recording off-thread: the MP4 trailer write must
            // never block `stop()` or the session thread (the file stays
            // valid even when the session ended unexpectedly).
            if let Some(recorder) = lock(&self.recorder).take() {
                let _ = std::thread::Builder::new()
                    .name("aps-screencast-recorder-finish".to_owned())
                    .spawn(move || match recorder.finish() {
                        Ok(path) => {
                            eprintln!("[screencast] recording finalized on session end: {path:?}")
                        }
                        Err(error) => eprintln!("[screencast] recording finalize failed: {error}"),
                    });
            }
            let launched = lock(&self.launched).take();
            if let Some(launched) = launched {
                let environment = self.environment.clone();
                let serial = self.config.serial.clone();
                let _ = std::thread::Builder::new()
                    .name("aps-screencast-cleanup".to_owned())
                    .spawn(move || environment.cleanup(&serial, &launched));
            }
        }
        transitioned
    }
}

/// Releases the start guard when the session thread unwinds (§6.2 `finally`).
struct StartGuardRelease(Arc<SessionInner>);

impl Drop for StartGuardRelease {
    fn drop(&mut self) {
        self.0.start_guard.store(false, Ordering::SeqCst);
    }
}

fn lock<T>(guard: &Mutex<T>) -> MutexGuard<'_, T> {
    guard
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Formats a launch/handshake failure with the device-server output tail: a
/// crashed server explains itself there (missing jar, incompatible Android,
/// codec errors — all invisible without this tail).
fn failed_with_server_log(launched: &LaunchedServer, message: String) -> String {
    let tail = crate::launcher::server_log_tail_text(&launched.server_log_tail);
    if tail.is_empty() {
        message
    } else {
        format!("{message}\n[scrcpy server] {tail}")
    }
}

fn dead_receiver() -> FrameReceiver {
    let (sender, receiver) = sync_channel(0);
    drop(sender);
    receiver
}

/// The session body (§6.2 `runSession`): the §1.2 startup sequence, then the
/// video decode loop; the control-read thread runs concurrently.
fn run_session(inner: Arc<SessionInner>) {
    let _release = StartGuardRelease(inner.clone());
    let serial = inner.config.serial.clone();
    // Phase timestamps for `metrics()`: the device-side server boot dominates a
    // cold start, so it is worth being able to see where the time went.
    let mark_phase = |slot: &AtomicU64| {
        let elapsed = now_ms().saturating_sub(inner.metrics.started_at_ms.load(Ordering::Relaxed));
        slot.store(elapsed, Ordering::Relaxed);
    };

    // Decode backend first (§9.3): a missing backend fails before any device
    // side effect.
    let mut decoder = match (inner.config.decoder_factory)() {
        Ok(decoder) => decoder,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(format!(
                "decode backend unavailable: {error}"
            )));
            return;
        }
    };

    // §1.2 step 1: ServerSocket(0), the 15 s soTimeout becomes an accept
    // deadline below.
    let listener = match inner.environment.bind_listener() {
        Ok(listener) => listener,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(format!(
                "unable to bind the local reverse-tunnel listener: {error}"
            )));
            return;
        }
    };
    let local_port = listener
        .local_addr()
        .map(|address| address.port())
        .unwrap_or(0);
    let mut launched = LaunchedServer::generate(local_port);

    // §1.2 steps 2-5 (push / reverse / server spawn); §1.4 rollback on error.
    if let Err(error) = inner
        .environment
        .launch(&serial, &mut launched, inner.config.quality)
    {
        inner.environment.rollback(&serial, &launched);
        inner.finish(ScrcpySessionState::Failed(error.to_string()));
        return;
    }
    mark_phase(&inner.metrics.launch_ms);
    *lock(&inner.launched) = Some(launched.clone());

    // §1.2 step 6: accept twice with the 15 s deadline — video first, control
    // second (§1.2 connection-order note; audio=false adds no third socket).
    let deadline = Instant::now() + Duration::from_millis(ACCEPT_TIMEOUT_MS);
    let video = match accept_with_timeout(&listener, deadline) {
        Ok(stream) => stream,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(failed_with_server_log(
                &launched,
                error.to_string(),
            )));
            return;
        }
    };
    configure_video_socket(&video);
    let control = match accept_with_timeout(&listener, deadline) {
        Ok(stream) => stream,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(failed_with_server_log(
                &launched,
                error.to_string(),
            )));
            return;
        }
    };

    mark_phase(&inner.metrics.connected_ms);
    // §1.2 step 8: device name (64 B) + video metadata (12 B) on the video
    // socket; codec must be h264.
    let video_handle = video.try_clone().ok();
    let mut reader = StreamReader::new(video);
    let (device_name, metadata) = match reader.read_device_meta() {
        Ok(meta) => meta,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(failed_with_server_log(
                &launched,
                format!("scrcpy handshake failed: {error}"),
            )));
            return;
        }
    };
    if metadata.codec_id != VIDEO_CODEC_H264 {
        inner.finish(ScrcpySessionState::Failed(format!(
            "Unsupported video codec from server: {}",
            metadata.codec_name()
        )));
        return;
    }

    mark_phase(&inner.metrics.handshake_ms);
    // Shutdown handles for `finish` from foreign threads.
    if let Some(handle) = video_handle {
        *lock(&inner.video_socket) = Some(handle);
    }
    if let Ok(handle) = control.try_clone() {
        *lock(&inner.control_socket) = Some(handle);
    }
    // §6.3: the control writer rides its own socket clone — created here so
    // every host→device message (touch, keys, clipboard, RESET_VIDEO) reaches
    // the server. Without it every send is a silent drop (writer null).
    if let Ok(writer_socket) = control.try_clone() {
        *lock(&inner.writer) = Some(ControlWriter::new(writer_socket));
    }
    // §6.2 control-read coroutine: drain device messages; IOExceptions are
    // swallowed — real disconnects surface in the video loop.
    std::thread::Builder::new()
        .name("aps-screencast-device-messages".to_owned())
        .spawn(move || {
            let mut reader = DeviceMessageReader::new(control);
            drain_device_messages(&mut reader);
        })
        .ok();

    // §1.2 step 9: Running + video decode main loop (skipped entirely when
    // `stop` already tore the Connecting session down — the frame sender is
    // gone).
    //
    // One thread reads the tunnel and decodes inline. The decoder never blocks
    // for a frame that is still waiting on a later access unit (see `decoder`),
    // so the read side stays ahead of the encoder; a decode that is genuinely
    // too slow is handled by [`Self::catch_up`]-style logic below (skip to the
    // next keyframe instead of accumulating delay).
    if !inner.stop_requested.load(Ordering::SeqCst) {
        eprintln!(
            "[screencast] session running: {device_name} ({}x{})",
            metadata.width, metadata.height
        );
        mark_phase(&inner.metrics.running_ms);
        inner.set_state(ScrcpySessionState::Running {
            device_name: device_name.clone(),
            width: metadata.width,
            height: metadata.height,
        });
        // Ask for a fresh keyframe right away. The server's first encoded
        // access unit would otherwise sit in the decoder until ~1 KiB of
        // follow-up data arrives — instant for an animating screen, never for a
        // static one — so the mirror would paint late or not at all.
        inner.request_reset_video();
        // NOTE: the sender clone must be bound in its own statement —
        // `if let Some(sender) = lock(...).clone()` would keep the MutexGuard
        // temporary alive for the whole loop body, self-deadlocking every
        // `finish()` inside the loop (they re-lock `frames_sender`).
        let sender = lock(&inner.frames_sender).clone();
        if let Some(sender) = sender {
            let metrics = inner.metrics.clone();
            run_video_loop(
                &inner,
                &mut reader,
                decoder.as_mut(),
                &metadata,
                &sender,
                &metrics,
                &serial,
            );
        }
    }
    // §6.4 step 2: decoder reset on loop exit.
    decoder.reset();
}

/// The video loop body: read → tee to the recorder → decode → deliver, plus
/// the three latency guards (burst nudge, startup assist, saturation catch-up).
fn run_video_loop(
    inner: &Arc<SessionInner>,
    reader: &mut StreamReader<TcpStream>,
    decoder: &mut dyn FrameDecoder,
    metadata: &crate::video_stream::VideoMetadata,
    sender: &SyncSender<BgraFrame>,
    metrics: &MetricCounters,
    serial: &str,
) {
    const DECODE_ERROR_LOG_INTERVAL: Duration = Duration::from_millis(1_000);
    let mut last_error: Option<Instant> = None;
    // Burst detection: the first access unit after a quiet stretch is the one
    // the parser holds (it waits for that unit's successor), so it gets a nudge
    // that makes the server re-emit the screen and release it.
    let mut last_au_at: Option<Instant> = None;
    let mut last_nudge_at: Option<Instant> = None;
    let mut startup_nudges = 0u32;
    // Catch-up: while set, access units are discarded until the next keyframe,
    // so the decoder restarts on a clean reference instead of replaying a stale
    // backlog.
    let mut catching_up = false;
    let mut cached_config: Option<Vec<u8>> = None;
    let mut last_decoder_dropped = 0u64;
    // Backlog sampling: access units in versus frames out per window. The
    // ffmpeg CLI decodes in its own process, so this — not our own busy time —
    // is what says whether the mirror is falling behind.
    let mut window_start = Instant::now();
    let mut window_aus = 0i64;
    let mut window_frames = 0i64;

    loop {
        if inner.stop_requested.load(Ordering::SeqCst) {
            inner.finish(ScrcpySessionState::Idle);
            return;
        }
        let read_started = Instant::now();
        let packet = match reader.read_packet() {
            // §4: EOF is a clean session end.
            Ok(None) => {
                inner.finish(ScrcpySessionState::Disconnected);
                return;
            }
            Ok(Some(packet)) => packet,
            Err(error) => {
                // §6.3: runtime failure → Disconnected with the session-ended
                // log; a socket shutdown caused by our own `stop()` is not an
                // error and lands in Idle.
                if inner.stop_requested.load(Ordering::SeqCst) {
                    inner.finish(ScrcpySessionState::Idle);
                } else {
                    eprintln!("[screencast] session ended: {error}");
                    inner.finish(ScrcpySessionState::Disconnected);
                }
                return;
            }
        };
        metrics
            .read_busy_ms
            .fetch_add(read_started.elapsed().as_millis() as u64, Ordering::Relaxed);
        metrics
            .bytes_received
            .fetch_add(packet.payload.len() as u64, Ordering::Relaxed);

        // Recorder tee: every packet (config + access units) feeds the MP4
        // muxer while a recording is active. A dead muxer disables the
        // recording; the UI clears its indicator via `is_recording`.
        {
            let mut guard = lock(&inner.recorder);
            if let Some(recorder) = guard.as_mut()
                && let Err(error) = recorder.write(&packet.payload)
            {
                eprintln!("[screencast] recorder failed: {error}");
                guard.take();
            }
        }

        if packet.is_config() {
            // §4: config packets go to configure and never count as frames.
            // The payload is cached so a catch-up restart can re-seed the
            // decoder without waiting for the server to resend it.
            cached_config = Some(packet.payload.clone());
            if !catching_up {
                decoder.configure(&packet.payload);
            }
            continue;
        }
        metrics.access_units.fetch_add(1, Ordering::Relaxed);
        window_aus += 1;

        // ---- catch-up: drop until the next keyframe, then restart clean ----
        if catching_up {
            if !packet.is_key_frame() {
                metrics.frames_dropped.fetch_add(1, Ordering::Relaxed);
                continue;
            }
            catching_up = false;
            metrics.resyncs.fetch_add(1, Ordering::Relaxed);
            decoder.reset();
            decoder.stats();
            last_decoder_dropped = 0;
            if let Some(config) = cached_config.as_deref() {
                decoder.configure(config);
            }
        }

        // ---- burst nudge: release the access unit the parser is holding ----
        let burst = last_au_at.is_none_or(|at| at.elapsed() >= BURST_QUIET);
        last_au_at = Some(Instant::now());
        if burst && last_nudge_at.is_none_or(|at| at.elapsed() >= NUDGE_MIN_GAP) {
            last_nudge_at = Some(Instant::now());
            metrics.nudges.fetch_add(1, Ordering::Relaxed);
            inner.request_reset_video();
        }
        // Startup assist: only covers the case where the device never sends an
        // initial keyframe on its own (a static screen still does, so this
        // normally never fires).
        if metrics.first_frame_ms.load(Ordering::Relaxed) == 0
            && startup_nudges < STARTUP_ASSIST_LIMIT
            && now_ms().saturating_sub(metrics.started_at_ms.load(Ordering::Relaxed))
                >= STARTUP_ASSIST_AFTER.as_millis() as u64
        {
            // At most one fallback nudge per burst, so a genuinely idle device
            // is not hammered with IDR requests.
            if burst {
                startup_nudges += 1;
                metrics.nudges.fetch_add(1, Ordering::Relaxed);
                inner.request_reset_video();
                if startup_nudges == STARTUP_ASSIST_LIMIT {
                    // Nothing is arriving at all: an abandoned scrcpy server
                    // from a previously killed session keeps the device encoder
                    // allocated, and the new server then emits no frames. Say
                    // so instead of leaving a silent black canvas.
                    eprintln!(
                        "[screencast] no video data after {startup_nudges} startup keyframe \
                         requests. If the canvas stays black, a stale scrcpy server may still \
                         hold the device encoder: adb -s {serial} shell pkill -f \
                         com.genymobile.scrcpy.Server"
                    );
                }
            }
        }

        // ---- decode -------------------------------------------------------
        let decode_started = Instant::now();
        match decoder.decode(&packet.payload, metadata.width, metadata.height) {
            Ok(Some(frame)) => {
                if metrics.first_frame_ms.load(Ordering::Relaxed) == 0 {
                    let first_ms =
                        now_ms().saturating_sub(metrics.started_at_ms.load(Ordering::Relaxed));
                    metrics.first_frame_ms.store(first_ms, Ordering::Relaxed);
                    eprintln!(
                        "[screencast] first frame after {first_ms} ms ({}x{})",
                        frame.width, frame.height
                    );
                }
                metrics.frames_decoded.fetch_add(1, Ordering::Relaxed);
                window_frames += 1;
                metrics.last_frame_ms.store(now_ms(), Ordering::Relaxed);
                match sender.try_send(frame) {
                    Ok(()) => {
                        metrics.frames_delivered.fetch_add(1, Ordering::Relaxed);
                    }
                    // Display behind: the newest frame wins, so dropping is the
                    // correct behavior (Kotlin suspends; the effect on latency
                    // is the same).
                    Err(TrySendError::Full(_)) => {
                        metrics.frames_dropped.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(TrySendError::Disconnected(_)) => {}
                }
            }
            // No frame released yet: the next access unit releases it.
            Ok(None) => {}
            Err(error) => {
                // Decode errors are not fatal: log them (throttled) and keep
                // the session; the next keyframe (or catch-up) re-syncs.
                let now = Instant::now();
                if last_error.is_none_or(|at| at.elapsed() >= DECODE_ERROR_LOG_INTERVAL) {
                    last_error = Some(now);
                    eprintln!("[screencast] decode error (recovering): {error}");
                }
            }
        }
        metrics.decode_busy_ms.fetch_add(
            decode_started.elapsed().as_millis() as u64,
            Ordering::Relaxed,
        );
        // Decoder-side discards (older frames displaced by a newer one).
        let decoder_dropped = decoder.stats().frames_dropped;
        if decoder_dropped > last_decoder_dropped {
            metrics
                .frames_dropped
                .fetch_add(decoder_dropped - last_decoder_dropped, Ordering::Relaxed);
            last_decoder_dropped = decoder_dropped;
        }

        // ---- backlog: skip to the next keyframe instead of lagging --------
        if window_start.elapsed() >= BACKLOG_WINDOW {
            let backlog = window_aus - window_frames;
            let limit = BACKLOG_LIMIT_MIN.max(window_aus / 4);
            window_start = Instant::now();
            window_aus = 0;
            window_frames = 0;
            if backlog >= limit && !catching_up {
                eprintln!(
                    "[screencast] decoder is {backlog} access units behind; skipping to the next keyframe"
                );
                catching_up = true;
                // An IDR is the only resync point the server offers.
                inner.request_reset_video();
            }
        }
    }
}

/// Accepts one connection before the shared 15 s deadline (§1.2 soTimeout).
fn accept_with_timeout(
    listener: &TcpListener,
    deadline: Instant,
) -> Result<TcpStream, LaunchError> {
    listener
        .set_nonblocking(true)
        .map_err(LaunchError::Protocol)?;
    let result = loop {
        match listener.accept() {
            Ok((stream, _peer)) => break Ok(stream),
            Err(error) if error.kind() == ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    break Err(LaunchError::AcceptTimeout);
                }
                std::thread::sleep(ACCEPT_POLL);
            }
            Err(error) => break Err(LaunchError::Protocol(error)),
        }
    };
    listener.set_nonblocking(false).ok();
    result.inspect(|stream| {
        // Accepted sockets inherit the listener's non-blocking flag.
        stream.set_nonblocking(false).ok();
    })
}

/// §1.2 step 7: TCP_NODELAY + 2 MiB receive buffer on the video socket.
fn configure_video_socket(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
    set_receive_buffer(stream, SOCKET_BUFFER_SIZE);
}

/// SO_RCVBUF is not exposed by std; call libc's setsockopt directly (libc is
/// already linked by std). Constants: Linux/Android `SOL_SOCKET=1, SO_RCVBUF=8`,
/// macOS `0xFFFF / 0x1002`. Silently ignored elsewhere (best-effort sizing,
/// the kernel may round the value).
#[cfg(unix)]
fn set_receive_buffer(stream: &TcpStream, bytes: usize) {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    const SOL_SOCKET: i32 = 1;
    #[cfg(any(target_os = "linux", target_os = "android"))]
    const SO_RCVBUF: i32 = 8;
    #[cfg(target_os = "macos")]
    const SOL_SOCKET: i32 = 0xFFFF;
    #[cfg(target_os = "macos")]
    const SO_RCVBUF: i32 = 0x1002;
    #[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
    {
        let _ = (stream, bytes);
    }
    #[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
    {
        use std::os::unix::io::AsRawFd;
        unsafe extern "C" {
            fn setsockopt(
                fd: i32,
                level: i32,
                option: i32,
                value: *const core::ffi::c_void,
                length: u32,
            ) -> i32;
        }
        let value = bytes as i32;
        unsafe {
            let _ = setsockopt(
                stream.as_raw_fd(),
                SOL_SOCKET,
                SO_RCVBUF,
                core::ptr::from_ref(&value).cast(),
                core::mem::size_of::<i32>() as u32,
            );
        }
    }
}

#[cfg(not(unix))]
fn set_receive_buffer(_stream: &TcpStream, _bytes: usize) {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::video_stream::{CONFIG_FLAG, DEVICE_NAME_BYTES, KEY_FRAME_FLAG, VIDEO_CODEC_H265};

    /// Stub decoder: every access unit yields a frame (deterministic, no
    /// ffmpeg dependency in the session tests).
    struct StubDecoder;

    impl FrameDecoder for StubDecoder {
        fn configure(&mut self, _payload: &[u8]) {}
        fn decode(
            &mut self,
            _payload: &[u8],
            width: u32,
            height: u32,
        ) -> std::io::Result<Option<BgraFrame>> {
            Ok(Some(BgraFrame {
                bgra: vec![0xAA; (width * height * 4) as usize],
                width,
                height,
            }))
        }
        fn reset(&mut self) {}
    }

    fn stub_decoder_factory() -> DecoderFactory {
        Arc::new(|| Ok(Box::new(StubDecoder) as Box<dyn FrameDecoder>))
    }

    /// What the fake launcher/device does during launch.
    #[derive(Clone)]
    enum FakePlan {
        /// Records push + reverse, then emulates the §1.4 rollback and fails
        /// at the shell step ("registered reverse, then IOException").
        FailAtShell,
        /// Records push + reverse, optionally sleeps first ("launcher
        /// suspended"), then emulates the device connecting video → control.
        Serve {
            hang_ms: u64,
            holder_ms: u64,
            codec_id: i32,
        },
        /// Streams a REAL H.264 Annex-B payload through the §4 wire format at
        /// a live-encoder pacing (integration with the real decoder).
        Stream {
            stream: Arc<Vec<u8>>,
            reset_stream: Arc<Vec<u8>>,
            pacing_ms: u64,
            holder_ms: u64,
        },
    }

    /// Trait-injected fake launcher + fake device, spec §10 style: adb
    /// commands are recorded, the device side speaks the §4 wire format.
    struct FakeEnvironment {
        records: Arc<Mutex<Vec<String>>>,
        plan: FakePlan,
    }

    impl FakeEnvironment {
        fn new(plan: FakePlan) -> Arc<Self> {
            Arc::new(Self {
                records: Arc::new(Mutex::new(Vec::new())),
                plan,
            })
        }

        fn records(&self) -> Vec<String> {
            lock(&self.records).clone()
        }

        fn record(&self, line: impl Into<String>) {
            lock(&self.records).push(line.into());
        }
    }

    impl LaunchEnvironment for FakeEnvironment {
        fn bind_listener(&self) -> std::io::Result<TcpListener> {
            TcpListener::bind("127.0.0.1:0")
        }

        fn launch(
            &self,
            serial: &str,
            launched: &mut LaunchedServer,
            _quality: ScreencastQuality,
        ) -> Result<(), LaunchError> {
            self.record(format!("adb -s {serial} push {}", launched.remote_jar_path));
            match &self.plan {
                FakePlan::FailAtShell => {
                    // The launch pipeline fails after registering reverse; the
                    // session responds with `rollback` (§1.4), which the fake
                    // records below.
                    Err(LaunchError::Shell("server failed to start".to_owned()))
                }
                FakePlan::Serve {
                    hang_ms,
                    holder_ms,
                    codec_id,
                } => {
                    if *hang_ms > 0 {
                        std::thread::sleep(Duration::from_millis(*hang_ms));
                    }
                    self.record(format!(
                        "adb -s {serial} reverse localabstract:{} tcp:{}",
                        launched.socket_name, launched.local_port
                    ));
                    serve_fake_device(launched.local_port, *codec_id, *holder_ms);
                    Ok(())
                }
                FakePlan::Stream {
                    stream,
                    reset_stream,
                    pacing_ms,
                    holder_ms,
                } => {
                    self.record(format!(
                        "adb -s {serial} reverse localabstract:{} tcp:{}",
                        launched.socket_name, launched.local_port
                    ));
                    serve_fake_stream_device(
                        launched.local_port,
                        640,
                        480,
                        stream,
                        reset_stream,
                        *pacing_ms,
                        *holder_ms,
                    );
                    Ok(())
                }
            }
        }

        fn rollback(&self, serial: &str, launched: &LaunchedServer) {
            self.record(format!(
                "adb -s {serial} reverse --remove localabstract:{}",
                launched.socket_name
            ));
            self.record(format!(
                "adb -s {serial} shell rm -f {}",
                launched.remote_jar_path
            ));
        }

        fn cleanup(&self, serial: &str, launched: &LaunchedServer) {
            self.record(format!(
                "cleanup adb -s {serial} reverse --remove localabstract:{}",
                launched.socket_name
            ));
            self.record(format!(
                "cleanup adb -s {serial} shell rm -f {}",
                launched.remote_jar_path
            ));
        }
    }

    /// Emulates the device: connects video → control on the reverse port and
    /// writes the §4 metadata plus a config packet and two key frames; a
    /// holder thread keeps the sockets open for `holder_ms` then drops them.
    fn serve_fake_device(port: u16, codec_id: i32, holder_ms: u64) {
        let video = TcpStream::connect(("127.0.0.1", port)).expect("fake device video connect");
        std::thread::sleep(Duration::from_millis(5));
        let control = TcpStream::connect(("127.0.0.1", port)).expect("fake device control connect");
        let mut video = video;
        let mut meta = vec![0u8; DEVICE_NAME_BYTES];
        meta[..7].copy_from_slice(b"Pixel 8");
        meta.extend_from_slice(&codec_id.to_be_bytes());
        meta.extend_from_slice(&320u32.to_be_bytes());
        meta.extend_from_slice(&240u32.to_be_bytes());
        use std::io::Write;
        video.write_all(&meta).expect("fake meta");
        let packet = |flags: u64, payload: &[u8]| {
            let mut bytes = flags.to_be_bytes().to_vec();
            bytes.extend_from_slice(&(payload.len() as i32).to_be_bytes());
            bytes.extend_from_slice(payload);
            bytes
        };
        video
            .write_all(&packet(CONFIG_FLAG, &[0x67, 0x64, 0x00]))
            .expect("fake config");
        video
            .write_all(&packet(KEY_FRAME_FLAG | 1, &[0x65, 0x88, 0x85]))
            .expect("fake key frame");
        video
            .write_all(&packet(KEY_FRAME_FLAG | 2, &[0x41, 0x9A, 0x2B]))
            .expect("fake second frame");
        // Holder keeps the sockets alive so the session stays Running, then
        // EOFs the video loop.
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(holder_ms));
            drop(video);
            drop(control);
        });
    }

    /// Emulates the device streaming a REAL H.264 Annex-B stream (spec §4
    /// wire format): codec config packet first, then each access unit paced
    /// like a live encoder. Exercises the full session path with the real
    /// ffmpeg decoder factory.
    fn serve_fake_stream_device(
        port: u16,
        width: u32,
        height: u32,
        stream: &[u8],
        reset_stream: &[u8],
        pacing_ms: u64,
        holder_ms: u64,
    ) {
        use std::io::Write;
        let video = TcpStream::connect(("127.0.0.1", port)).expect("fake stream video connect");
        std::thread::sleep(Duration::from_millis(5));
        let control = TcpStream::connect(("127.0.0.1", port)).expect("fake stream control connect");
        let mut video = video;
        let mut meta = vec![0u8; DEVICE_NAME_BYTES];
        meta[..7].copy_from_slice(b"Pixel 8");
        meta.extend_from_slice(&VIDEO_CODEC_H264.to_be_bytes());
        meta.extend_from_slice(&width.to_be_bytes());
        meta.extend_from_slice(&height.to_be_bytes());
        video.write_all(&meta).expect("fake stream meta");
        let send = |video: &mut TcpStream, flags: u64, payload: &[u8]| {
            let mut bytes = flags.to_be_bytes().to_vec();
            bytes.extend_from_slice(&(payload.len() as i32).to_be_bytes());
            bytes.extend_from_slice(payload);
            video.write_all(&bytes).expect("fake stream packet");
        };
        let (config, units) = split_stream(stream);
        send(&mut video, CONFIG_FLAG, &config);
        for (index, unit) in units.iter().enumerate() {
            send(&mut video, KEY_FRAME_FLAG | (index as u64 + 1), unit);
            std::thread::sleep(Duration::from_millis(pacing_ms));
        }
        // RESET_VIDEO responder (real server behavior: re-encode the current
        // screen — a FRESH keyframe with new POC/frame_num; an identical-byte
        // copy is treated as a continuation of the held AU and never
        // releases it, so the responder streams successive DIFFERENT units).
        let (reset_config, reset_units) = split_stream(reset_stream);
        if let (Ok(reset_video), Ok(reset_control)) = (video.try_clone(), control.try_clone()) {
            std::thread::spawn(move || {
                use std::io::Read;
                let mut control = reset_control;
                let mut video = reset_video;
                let mut buffer = [0u8; 64];
                loop {
                    match control.read(&mut buffer) {
                        Ok(0) | Err(_) => break,
                        Ok(count) => {
                            for &byte in &buffer[..count] {
                                if byte == crate::control::RESET_VIDEO {
                                    send(&mut video, CONFIG_FLAG, &reset_config);
                                    for (index, unit) in reset_units.iter().enumerate() {
                                        send(&mut video, KEY_FRAME_FLAG | (index as u64 + 1), unit);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(holder_ms));
            drop(video);
            drop(control);
        });
    }

    /// Splits an Annex-B stream into (parameter sets + SEI/AUD) and access
    /// units; slice NALs (1/5) end the previous unit. Mirrors the decoder
    /// tests' splitter.
    fn split_stream(stream: &[u8]) -> (Vec<u8>, Vec<Vec<u8>>) {
        let nals = annex_b_nals(stream);
        let mut config = Vec::new();
        let mut units: Vec<Vec<u8>> = Vec::new();
        let mut current: Option<Vec<u8>> = None;
        for (nal_type, range) in nals {
            let bytes = &stream[range];
            match nal_type {
                6..=9 => match current.as_mut() {
                    Some(unit) => unit.extend_from_slice(bytes),
                    None => config.extend_from_slice(bytes),
                },
                1 | 5 => {
                    if let Some(unit) = current.take() {
                        units.push(unit);
                    }
                    current = Some(bytes.to_vec());
                }
                _ => {
                    let unit = current.get_or_insert_with(Vec::new);
                    unit.extend_from_slice(bytes);
                }
            }
        }
        if let Some(unit) = current {
            units.push(unit);
        }
        (config, units)
    }

    /// Scans Annex-B start codes and yields `(nal_type, byte range)` including
    /// the start code and any leading zeros.
    fn annex_b_nals(stream: &[u8]) -> Vec<(u8, std::ops::Range<usize>)> {
        let mut starts = Vec::new();
        let mut index = 0;
        while index + 2 < stream.len() {
            if stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1 {
                starts.push(index);
                index += 3;
            } else {
                index += 1;
            }
        }
        let mut nals = Vec::with_capacity(starts.len());
        for (position, &start) in starts.iter().enumerate() {
            let payload_start = start + 3;
            let mut code_start = start;
            while code_start > 0 && stream[code_start - 1] == 0 {
                code_start -= 1;
            }
            let end = starts
                .get(position + 1)
                .map(|&next| {
                    let mut boundary = next;
                    while boundary > payload_start && stream[boundary - 1] == 0 {
                        boundary -= 1;
                    }
                    boundary
                })
                .unwrap_or(stream.len());
            if payload_start < end {
                nals.push((stream[payload_start] & 0x1F, code_start..end));
            }
        }
        nals
    }

    /// Generates a REAL H.264 Annex-B stream with the local ffmpeg CLI
    /// (640×480, 30 fps, 1.5 s → ~45 frames; `source` picks the pattern —
    /// testsrc vs testsrc2 produce different bytes, needed to emulate a
    /// fresh reset-video keyframe).
    fn generate_stream(executable: &std::path::Path, source: &str) -> Option<Vec<u8>> {
        use std::process::{Command, Stdio};
        let output = Command::new(executable)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                &format!("{source}=duration=1.5:size=640x480:rate=30"),
                "-c:v",
                "libx264",
                // Match the real device encoders: 4:2:0 Baseline.
                "-pix_fmt",
                "yuv420p",
                "-profile:v",
                "baseline",
                "-f",
                "h264",
                "-",
            ])
            .stdin(Stdio::null())
            .output()
            .ok()?;
        if output.status.success() && !output.stdout.is_empty() {
            Some(output.stdout)
        } else {
            eprintln!(
                "testsrc generation failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
            None
        }
    }

    fn session_with(plan: FakePlan) -> (ScrcpySession, Arc<FakeEnvironment>) {
        let environment = FakeEnvironment::new(plan);
        let config = SessionConfig {
            serial: "FAKE001".to_owned(),
            quality: ScreencastQuality::Hd,
            adb_executable: None,
            decoder_factory: stub_decoder_factory(),
        };
        (
            ScrcpySession::with_environment(config, environment.clone()),
            environment,
        )
    }

    fn wait_for_state(
        session: &ScrcpySession,
        timeout: Duration,
        predicate: impl Fn(&ScrcpySessionState) -> bool,
    ) -> ScrcpySessionState {
        let deadline = Instant::now() + timeout;
        loop {
            let state = session.state();
            if predicate(&state) || Instant::now() >= deadline {
                return state;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    /// The §6.4 cleanup runs on a detached thread; poll for the expected
    /// record instead of reading immediately after the state transition.
    fn wait_for_record(environment: &FakeEnvironment, needle: &str, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        loop {
            if environment
                .records()
                .iter()
                .any(|record| record.contains(needle))
            {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    /// Spec §10 ①: start is idempotent — repeated starts during a slow launch
    /// trigger exactly one launch.
    #[test]
    fn start_is_idempotent_while_the_launcher_is_busy() {
        let (session, environment) = session_with(FakePlan::Serve {
            hang_ms: 250,
            holder_ms: 2_000,
            codec_id: VIDEO_CODEC_H264,
        });
        let _frames = session.start();
        std::thread::sleep(Duration::from_millis(50));
        let _ = session.start();
        let _ = session.start();
        let state = wait_for_state(&session, Duration::from_secs(5), |state| state.is_active());
        assert!(
            state.is_active(),
            "session should be Connecting/Running, got {state:?}"
        );
        let launches = environment
            .records()
            .into_iter()
            .filter(|record| record.contains(" push "))
            .count();
        assert_eq!(launches, 1, "exactly one launch despite repeated starts");
        session.stop();
        wait_for_state(&session, Duration::from_secs(2), |state| {
            *state == ScrcpySessionState::Idle
        });
    }

    /// Running delivers decoded frames through the channel; socket EOF ends
    /// the session as Disconnected and the §6.4 cleanup records appear.
    #[test]
    fn running_delivers_frames_then_disconnects_with_cleanup() {
        let (session, environment) = session_with(FakePlan::Serve {
            hang_ms: 0,
            holder_ms: 300,
            codec_id: VIDEO_CODEC_H264,
        });
        let frames = session.start();
        let state = wait_for_state(&session, Duration::from_secs(5), |state| {
            matches!(state, ScrcpySessionState::Running { .. })
        });
        let ScrcpySessionState::Running {
            device_name,
            width,
            height,
        } = state
        else {
            panic!("expected Running, got {state:?}");
        };
        assert_eq!(device_name, "Pixel 8");
        assert_eq!((width, height), (320, 240));
        let frame = frames
            .recv_timeout(Duration::from_secs(5))
            .expect("decoded frame");
        assert_eq!(frame.width, 320);
        assert_eq!(frame.height, 240);
        assert_eq!(frame.bgra.len(), 320 * 240 * 4);
        let state = wait_for_state(&session, Duration::from_secs(5), |state| {
            *state == ScrcpySessionState::Disconnected
        });
        assert_eq!(state, ScrcpySessionState::Disconnected);
        let records = environment.records();
        assert!(
            wait_for_record(
                &environment,
                "cleanup adb -s FAKE001 reverse --remove",
                Duration::from_secs(2)
            ),
            "cleanup must remove the reverse mapping: {records:?}"
        );
        assert!(
            wait_for_record(
                &environment,
                "cleanup adb -s FAKE001 shell rm -f",
                Duration::from_secs(2)
            ),
            "cleanup must remove the remote jar: {records:?}"
        );
    }

    /// Spec §10 ②: sends in the Idle state are silently dropped, the state
    /// stays Idle.
    #[test]
    fn idle_sends_are_silently_dropped() {
        let (session, environment) = session_with(FakePlan::FailAtShell);
        session.send_touch(
            crate::control::ACTION_DOWN,
            10,
            20,
            1.0,
            crate::control::BUTTON_PRIMARY,
        );
        session.send_scroll(0, 0, -1.0, -1.0);
        session.back();
        session.home();
        session.recents();
        session.paste_text("hello");
        session.rotate_device();
        session.set_display_power(true);
        session.reset_video();
        session.get_clipboard(crate::control::COPY_KEY_COPY);
        assert_eq!(session.state(), ScrcpySessionState::Idle);
        assert!(!session.is_active());
        assert!(
            environment.records().is_empty(),
            "Idle sends must not launch: {:?}",
            environment.records()
        );
    }

    /// Spec §10 ③: a launch failure after the reverse registration → Failed
    /// with the launcher's message, inactive, and the §1.4 rollback records
    /// present (and no second, session-level cleanup).
    #[test]
    fn launch_failure_sets_failed_and_rolls_back() {
        let (session, environment) = session_with(FakePlan::FailAtShell);
        let _frames = session.start();
        let state = wait_for_state(&session, Duration::from_secs(5), |state| {
            matches!(state, ScrcpySessionState::Failed(_))
        });
        let ScrcpySessionState::Failed(message) = state else {
            panic!("expected Failed, got {state:?}");
        };
        assert!(message.contains("server failed to start"), "got: {message}");
        assert!(!session.is_active());
        let records = environment.records();
        assert!(
            records
                .iter()
                .any(|record| record.contains("reverse --remove")),
            "rollback must remove the reverse mapping: {records:?}"
        );
        assert!(
            records.iter().any(|record| record.contains("shell rm -f")),
            "rollback must remove the remote jar: {records:?}"
        );
        assert!(
            !records.iter().any(|record| record.contains("cleanup ")),
            "a failed launch must not run the session-level cleanup: {records:?}"
        );
    }

    /// §1.2 step 8: a non-h264 codec id fails the session with the spec
    /// message.
    #[test]
    fn wrong_video_codec_fails_the_session() {
        let (session, environment) = session_with(FakePlan::Serve {
            hang_ms: 0,
            holder_ms: 2_000,
            codec_id: VIDEO_CODEC_H265,
        });
        let _frames = session.start();
        let state = wait_for_state(&session, Duration::from_secs(5), |state| {
            matches!(state, ScrcpySessionState::Failed(_))
        });
        let ScrcpySessionState::Failed(message) = state else {
            panic!("expected Failed, got {state:?}");
        };
        assert!(
            message.contains("Unsupported video codec from server: h265"),
            "got: {message}"
        );
        assert!(
            wait_for_record(
                &environment,
                "cleanup adb -s FAKE001 reverse --remove",
                Duration::from_secs(2)
            ),
            "cleanup still runs after a handshake failure (launcher was registered)"
        );
    }

    /// §6.4: `stop()` on a Running session lands in Idle with cleanup.
    #[test]
    fn stop_moves_running_to_idle() {
        let (session, environment) = session_with(FakePlan::Serve {
            hang_ms: 0,
            holder_ms: 5_000,
            codec_id: VIDEO_CODEC_H264,
        });
        let _frames = session.start();
        wait_for_state(&session, Duration::from_secs(5), |state| {
            matches!(state, ScrcpySessionState::Running { .. })
        });
        session.stop();
        let state = wait_for_state(&session, Duration::from_secs(2), |state| {
            *state == ScrcpySessionState::Idle
        });
        assert_eq!(state, ScrcpySessionState::Idle);
        assert!(
            wait_for_record(
                &environment,
                "cleanup adb -s FAKE001 reverse --remove",
                Duration::from_secs(2)
            ),
            "stop must run the §6.4 cleanup"
        );
    }

    /// `stop()` while Idle is a no-op.
    #[test]
    fn stop_while_idle_is_a_no_op() {
        let (session, environment) = session_with(FakePlan::FailAtShell);
        session.stop();
        assert_eq!(session.state(), ScrcpySessionState::Idle);
        assert!(environment.records().is_empty());
    }

    /// Integration: REAL H.264 streams through the full session path with
    /// the real ffmpeg decoder must deliver decoded frames — including the
    /// static-screen case where the demuxer holds the last AU until the
    /// watchdog's RESET_VIDEO flush (a fresh keyframe with new POC releases
    /// it).
    #[test]
    fn real_decoder_delivers_frames() {
        let Some(executable) = crate::decoder::discover_executable() else {
            eprintln!("SKIP: ffmpeg not found on PATH/FFMPEG_EXECUTABLE");
            return;
        };
        let Some(stream) = generate_stream(&executable, "testsrc") else {
            eprintln!("SKIP: ffmpeg could not encode a testsrc stream (libx264 missing?)");
            return;
        };
        let Some(reset_stream) = generate_stream(&executable, "testsrc2") else {
            eprintln!("SKIP: ffmpeg could not encode a testsrc2 stream (libx264 missing?)");
            return;
        };
        assert_ne!(
            stream, reset_stream,
            "reset keyframe must differ from the initial stream"
        );

        let environment = FakeEnvironment::new(FakePlan::Stream {
            stream: Arc::new(stream),
            reset_stream: Arc::new(reset_stream),
            pacing_ms: 30,
            holder_ms: 4_000,
        });
        let config = SessionConfig {
            serial: "FAKE001".to_owned(),
            quality: ScreencastQuality::Hd,
            adb_executable: None,
            decoder_factory: default_decoder_factory(),
        };
        let session = ScrcpySession::with_environment(config, environment.clone());
        let frames = session.start();
        let state = wait_for_state(&session, Duration::from_secs(5), |state| {
            state != &ScrcpySessionState::Connecting
        });
        assert!(
            matches!(state, ScrcpySessionState::Running { .. }),
            "expected Running, got {state:?}"
        );
        let frame = frames
            .recv_timeout(Duration::from_secs(8))
            .expect("real decoder must deliver at least one frame while the stream is live");
        assert_eq!((frame.width, frame.height), (640, 480));
        assert_eq!(frame.bgra.len(), 640 * 480 * 4);
        session.stop();
    }
}
