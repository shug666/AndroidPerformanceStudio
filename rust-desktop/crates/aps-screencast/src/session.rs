//! Session state machine, ported from `ScrcpySession` (spec §6): the full §1.2
//! startup sequence, the §6.1 state enum (`Idle → Connecting → Running →
//! Disconnected`, `Failed` on startup errors), and the §6.2 thread model —
//! one session IO thread running the video decode loop plus a control-read
//! thread that drains device messages.
//!
//! Deviations forced by the blocking (non-async) Rust model, behavior-
//! equivalent: the coroutine scope becomes a dedicated thread per start; the
//! frame `SharedFlow(extraBufferCapacity=4)` becomes a bounded channel (4) —
//! when the consumer is slower than decode, arriving frames are dropped
//! (Kotlin suspends the emitter instead); `finish` sets the terminal state
//! before the adb cleanup runs (Kotlin ran cleanup first, keeping the old
//! state visible ~1 s longer).
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
use crate::video_stream::{StreamReader, VIDEO_CODEC_H264};
use std::io::ErrorKind;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, SyncSender, TrySendError, sync_channel};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// `SOCKET_BUFFER_SIZE = 2 MiB` (spec §6 constants; §1.2 step 7 receive buffer).
pub const SOCKET_BUFFER_SIZE: usize = 2 << 20;
/// Frame channel capacity (`extraBufferCapacity = 4`, spec §6.2).
pub const FRAME_CHANNEL_CAPACITY: usize = 4;
/// Accept-poll granularity while waiting for the two server connections.
const ACCEPT_POLL: Duration = Duration::from_millis(5);

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

/// The default backend: system `ffmpeg` CLI decoder (task-mandated route).
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
                launched: Mutex::new(None),
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
                *state = final_state;
                true
            }
        };
        if transitioned {
            *lock(&self.frames_sender) = None;
            if let Some(launched) = lock(&self.launched).take() {
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
    *lock(&inner.launched) = Some(launched.clone());

    // §1.2 step 6: accept twice with the 15 s deadline — video first, control
    // second (§1.2 connection-order note; audio=false adds no third socket).
    let deadline = Instant::now() + Duration::from_millis(ACCEPT_TIMEOUT_MS);
    let video = match accept_with_timeout(&listener, deadline) {
        Ok(stream) => stream,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(error.to_string()));
            return;
        }
    };
    configure_video_socket(&video);
    let control = match accept_with_timeout(&listener, deadline) {
        Ok(stream) => stream,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(error.to_string()));
            return;
        }
    };

    // §1.2 step 8: device name (64 B) + video metadata (12 B) on the video
    // socket; codec must be h264.
    let video_handle = video.try_clone().ok();
    let mut reader = StreamReader::new(video);
    let (device_name, metadata) = match reader.read_device_meta() {
        Ok(meta) => meta,
        Err(error) => {
            inner.finish(ScrcpySessionState::Failed(format!(
                "scrcpy handshake failed: {error}"
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

    // Shutdown handles for `finish` from foreign threads.
    if let Some(handle) = video_handle {
        *lock(&inner.video_socket) = Some(handle);
    }
    if let Ok(handle) = control.try_clone() {
        *lock(&inner.control_socket) = Some(handle);
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

    // §1.2 step 9: Running + decode main loop (skipped entirely when `stop`
    // already tore the Connecting session down — the frame sender is gone).
    if !inner.stop_requested.load(Ordering::SeqCst) {
        inner.set_state(ScrcpySessionState::Running {
            device_name: device_name.clone(),
            width: metadata.width,
            height: metadata.height,
        });
        let sender = lock(&inner.frames_sender).clone();
        if let Some(sender) = sender {
            loop {
                if inner.stop_requested.load(Ordering::SeqCst) {
                    inner.finish(ScrcpySessionState::Idle);
                    break;
                }
                match reader.read_packet() {
                    // §4: EOF is a clean session end.
                    Ok(None) => {
                        inner.finish(ScrcpySessionState::Disconnected);
                        break;
                    }
                    Ok(Some(packet)) => {
                        if packet.is_config() {
                            // §4: config packets go to configure and never count as frames.
                            decoder.configure(&packet.payload);
                        } else {
                            match decoder.decode(&packet.payload, metadata.width, metadata.height) {
                                Ok(Some(frame)) => {
                                    // Bounded channel: drop the frame when the consumer
                                    // is behind (Kotlin suspends; net effect is the same
                                    // newest-frame display behavior).
                                    if let Err(TrySendError::Disconnected(_)) =
                                        sender.try_send(frame)
                                    {
                                        // Consumer gone; keep draining the stream.
                                    }
                                }
                                Ok(None) => {} // no complete frame yet (§5 smooth cadence)
                                Err(error) => {
                                    eprintln!("[screencast] decoder failed: {error}");
                                    inner.finish(ScrcpySessionState::Disconnected);
                                    break;
                                }
                            }
                        }
                    }
                    Err(error) => {
                        // §6.3: runtime failure → Disconnected with the
                        // session-ended log; a socket shutdown caused by our
                        // own `stop()` is not an error and lands in Idle.
                        if inner.stop_requested.load(Ordering::SeqCst) {
                            inner.finish(ScrcpySessionState::Idle);
                        } else {
                            eprintln!("[screencast] session ended: {error}");
                            inner.finish(ScrcpySessionState::Disconnected);
                        }
                        break;
                    }
                }
            }
        }
    }
    // §6.4 step 2: decoder reset on loop exit.
    decoder.reset();
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
    #[derive(Clone, Copy)]
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
            match self.plan {
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
                    if hang_ms > 0 {
                        std::thread::sleep(Duration::from_millis(hang_ms));
                    }
                    self.record(format!(
                        "adb -s {serial} reverse localabstract:{} tcp:{}",
                        launched.socket_name, launched.local_port
                    ));
                    serve_fake_device(launched.local_port, codec_id, holder_ms);
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
}
