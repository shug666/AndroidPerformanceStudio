//! Frame decoding backend, ported from the Kotlin `ScreencastFrameDecoder`
//! chain (spec §5) with one task-mandated deviation: instead of the in-process
//! libavcodec/javacpp chain (or jcodec), this backend spawns the system
//! `ffmpeg` CLI:
//!
//! `ffmpeg -nostdin -loglevel error -probesize 32 -analyzeduration 0 -threads 1
//!  -f h264 -i pipe:0 -f rawvideo -pix_fmt bgra pipe:1`
//!
//! stdin receives H.264 Annex-B access units; stdout emits raw BGRA frames of
//! exactly `width*height*4` bytes. `-nostdin` keeps ffmpeg from grabbing the
//! terminal. A dedicated thread accumulates stdout into a `ByteBuffer`; each
//! `decode` call returns the newest frame that has already fully arrived and
//! discards older ones, so a stalled display can never build a backlog.
//!
//! ## Why those three options are load-bearing for a live mirror
//!
//! Measured on a real device stream (2136×1280 @ 24 AU/s, 97 KB per access
//! unit), feeding one access unit per `decode` call:
//!
//! | ffmpeg options | first frame out |
//! |---|---|
//! | defaults (`-analyzeduration 0` only) | 2.2 s behind the stream |
//! | `-probesize 32` | 0.7 s |
//! | `-threads 1` | 2.1 s |
//! | `-probesize 32 -threads 1` | **0.10 s** |
//!
//! - `-probesize 32` stops `avformat_find_stream_info` from buffering ~50
//!   packets before the first decoded frame is emitted. With the default probe
//!   budget the mirror is a two-second-delayed slideshow no matter how fast
//!   the decoder itself is.
//! - `-threads 1` disables frame-threaded decoding, which otherwise holds
//!   `nproc - 1` decoded frames before releasing the first one (a second
//!   source of delay that grows with core count).
//! - `-analyzeduration 0` keeps the raw H.264 demuxer from probing seconds of
//!   content first.
//!
//! `ffmpeg` still holds the last access unit until the *next* one arrives (the
//! H.264 parser ends a frame only when it sees the following frame's start
//! code), so a screen that never changes produces no new frame. The session
//! compensates by asking the server for a fresh keyframe when the stream goes
//! quiet with an undisplayed unit pending — see `session::run_session`.
//!
//! The decoder interface mirrors spec §5.2 (`configure`/`decode`/`reset`);
//! `reset` kills and restarts the process.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Environment override consulted before the PATH search (task constraint).
pub const FFMPEG_EXECUTABLE_ENV: &str = "FFMPEG_EXECUTABLE";

/// Default per-decode wait for a frame to arrive before returning `None`.
///
/// This is an *opportunistic* wait, not a pacing budget: the packet reader
/// must never be held back by a decoder that is simply still working. 4 ms
/// covers the hand-off between the socket thread writing an access unit and
/// the stdout pump thread publishing the frame that this unit released,
/// without costing the read loop more than a fraction of a frame interval.
const DEFAULT_FRAME_WAIT: Duration = Duration::from_millis(4);
/// Poll interval while waiting for a frame.
const POLL_INTERVAL: Duration = Duration::from_millis(1);
/// stdout read chunk.
const READ_CHUNK: usize = 64 * 1024;
/// Backpressure cap on buffered (undrained) output; beyond it the reader
/// thread pauses so ffmpeg's pipe backpressure propagates to the device.
const MAX_BUFFERED_BYTES: usize = 64 << 20;
/// stderr tail kept for diagnostics.
const STDERR_TAIL_BYTES: usize = 4096;

/// Exact argv used by the CLI backend. The first four options are latency
/// critical for a live mirror and measured — see the module docs for the
/// before/after numbers; do not drop them without re-measuring.
pub const FFMPEG_DECODER_ARGV: [&str; 18] = [
    "-nostdin",
    "-loglevel",
    "error",
    // Stream-info probing must not buffer the live stream (50 packets of
    // startup delay on the default budget).
    "-probesize",
    "32",
    "-analyzeduration",
    "0",
    // No frame-threaded decoding: it holds nproc-1 frames before the first
    // one is released. Slice threading is unavailable (MediaCodec emits a
    // single slice per frame), so one decode thread is the low-latency choice
    // and still decodes this stream ~72 fps (measured).
    "-threads",
    "1",
    "-f",
    "h264",
    "-i",
    "pipe:0",
    "-f",
    "rawvideo",
    "-pix_fmt",
    "bgra",
    "pipe:1",
];

/// Counters for the diagnostics surface: how many access units went in, how
/// many frames came out, and how many decoded frames were displaced by a newer
/// one before the display could take them.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecoderStats {
    /// Access units written to `ffmpeg` stdin.
    pub packets: u64,
    /// Frames handed to the caller.
    pub frames: u64,
    /// Decoded frames discarded because a newer frame was already available
    /// in the same drain pass (the display only needs the newest frame).
    pub frames_dropped: u64,
}

/// One decoded frame: tightly packed BGRA (Skia N32 / AV_PIX_FMT_BGRA layout),
/// independently allocated per frame; consumers must not hold frames across
/// the decode loop (spec §5.2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BgraFrame {
    pub bgra: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

/// Decoder interface mirroring spec §5.2 (`ScreencastFrameDecoder`). The
/// session owns one instance and feeds codec config packets and access units
/// from the video loop.
pub trait FrameDecoder: Send {
    /// Feed a codec config packet (Annex-B SPS/PPS). Produces no frame.
    fn configure(&mut self, payload: &[u8]);

    /// Feed one access unit and return the newest frame that is available,
    /// discarding older complete frames (a live mirror shows the newest frame
    /// only). Waits at most a few milliseconds for the frame this unit
    /// released, never for a frame that arrives with a later unit: returning
    /// `Ok(None)` immediately is what keeps the packet reader ahead of the
    /// stream.
    fn decode(
        &mut self,
        payload: &[u8],
        width: u32,
        height: u32,
    ) -> std::io::Result<Option<BgraFrame>>;

    /// Drop all decoder state. The CLI backend kills and restarts the process.
    fn reset(&mut self);

    /// Diagnostics counters; the default is all-zero for backends that do not
    /// track them.
    fn stats(&self) -> DecoderStats {
        DecoderStats::default()
    }
}

/// The ffmpeg CLI backend is unavailable (executable not found). Surfaced at
/// session start so the UI can show it.
#[derive(Debug)]
pub struct DecoderUnavailable(pub String);

impl std::fmt::Display for DecoderUnavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ffmpeg decoder backend unavailable: {}", self.0)
    }
}

impl std::error::Error for DecoderUnavailable {}

/// Growing output accumulator with an offset head; the stdout reader thread
/// pushes, `decode` drains exactly one frame from the front.
#[derive(Default)]
struct ByteBuffer {
    data: Vec<u8>,
    head: usize,
}

/// Atomic backing for [`DecoderStats`]: the decode path holds a `&mut self`
/// borrow on the process handle, so the counters are shared instead of copied.
#[derive(Debug, Default)]
struct DecoderCounters {
    packets: std::sync::atomic::AtomicU64,
    frames: std::sync::atomic::AtomicU64,
    frames_dropped: std::sync::atomic::AtomicU64,
}

impl DecoderCounters {
    fn snapshot(&self) -> DecoderStats {
        use std::sync::atomic::Ordering;
        DecoderStats {
            packets: self.packets.load(Ordering::Relaxed),
            frames: self.frames.load(Ordering::Relaxed),
            frames_dropped: self.frames_dropped.load(Ordering::Relaxed),
        }
    }
}

impl ByteBuffer {
    fn push(&mut self, bytes: &[u8]) {
        self.data.extend_from_slice(bytes);
    }

    fn len(&self) -> usize {
        self.data.len() - self.head
    }

    /// Copies the next `count` bytes out and advances the head, compacting the
    /// buffer once the front half is consumed.
    fn drain(&mut self, count: usize, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.data[self.head..self.head + count]);
        self.head += count;
        if self.head == self.data.len() {
            self.data.clear();
            self.head = 0;
        } else if self.head > READ_CHUNK && self.head * 2 > self.data.len() {
            self.data.drain(..self.head);
            self.head = 0;
        }
    }

    /// Copies out the **newest** complete frame of `need` bytes and throws away
    /// the older complete frames ahead of it; returns how many frames were
    /// discarded.
    ///
    /// This is the "newest frame wins" display rule: when the decoder has been
    /// catching up (or the display stalled) the buffer can hold several
    /// frames, and displaying them in order would only add latency.
    fn drain_newest(&mut self, need: usize, out: &mut Vec<u8>) -> usize {
        let mut dropped = 0;
        while self.len() >= need * 2 {
            self.head += need;
            dropped += 1;
        }
        self.drain(need, out);
        dropped
    }
}

/// One spawned ffmpeg process and its pump threads.
struct DecodeProcess {
    child: Child,
    /// `Some` while the input pipe is open; closing it makes ffmpeg flush the
    /// frames still buffered in its demuxer and exit.
    stdin: Option<ChildStdin>,
    buffer: Arc<Mutex<ByteBuffer>>,
    /// Set by the stdout reader thread when the output stream ends.
    exited: Arc<AtomicBool>,
    /// Human-readable exit reason (stderr tail), for error messages.
    exit_note: Arc<Mutex<String>>,
}

/// ffmpeg CLI frame decoder (spec §5 route (b)).
pub struct FfmpegCliDecoder {
    executable: PathBuf,
    frame_wait: Duration,
    process: Option<DecodeProcess>,
    /// Spawn failure sticky error; `decode` surfaces it on every call.
    spawn_error: Option<String>,
    stats: Arc<DecoderCounters>,
}

impl FfmpegCliDecoder {
    /// Discovers ffmpeg (`FFMPEG_EXECUTABLE` → PATH) and constructs the
    /// decoder. Failure means the decode backend is unavailable.
    pub fn new() -> Result<Self, DecoderUnavailable> {
        let executable = discover_executable().ok_or_else(|| {
            DecoderUnavailable(
                "ffmpeg executable not found; set FFMPEG_EXECUTABLE or install ffmpeg on PATH"
                    .to_owned(),
            )
        })?;
        Ok(Self::with_frame_wait(executable, DEFAULT_FRAME_WAIT))
    }

    /// Explicit executable, for tests and assembly layers that already know
    /// the binary location.
    pub fn with_frame_wait(executable: impl Into<PathBuf>, frame_wait: Duration) -> Self {
        Self {
            executable: executable.into(),
            frame_wait,
            process: None,
            spawn_error: None,
            stats: Arc::new(DecoderCounters::default()),
        }
    }

    /// Lazy open, mirroring spec §5.3 (first configure/decode triggers it).
    fn ensure_process(&mut self) {
        if self.process.is_none() && self.spawn_error.is_none() {
            match spawn_decode_process(&self.executable) {
                Ok(process) => self.process = Some(process),
                Err(error) => self.spawn_error = Some(error),
            }
        }
    }

    fn active_process(&mut self) -> std::io::Result<&mut DecodeProcess> {
        if let Some(error) = &self.spawn_error {
            return Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                error.clone(),
            ));
        }
        let Some(process) = self.process.as_mut() else {
            return Err(std::io::Error::other("ffmpeg decoder process not running"));
        };
        Ok(process)
    }
}

impl FrameDecoder for FfmpegCliDecoder {
    /// Config packets go straight to stdin; no frame is read (spec §4: config
    /// packets never enter the frame count). Write errors are deferred — the
    /// next `decode` reports the dead process.
    fn configure(&mut self, payload: &[u8]) {
        self.ensure_process();
        let Ok(process) = self.active_process() else {
            return;
        };
        if let Some(stdin) = process.stdin.as_mut() {
            let _ = stdin.write_all(payload).and_then(|_| stdin.flush());
        }
    }

    fn decode(
        &mut self,
        payload: &[u8],
        width: u32,
        height: u32,
    ) -> std::io::Result<Option<BgraFrame>> {
        self.ensure_process();
        let need = u64::from(width) * u64::from(height) * 4;
        let need = usize::try_from(need)
            .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidInput, error))?;
        let frame_wait = self.frame_wait;
        let stats = self.stats.clone();
        let process = self.active_process()?;
        if !payload.is_empty() {
            stats.packets.fetch_add(1, Ordering::Relaxed);
            if let Some(stdin) = process.stdin.as_mut()
                && let Err(error) = stdin.write_all(payload).and_then(|_| stdin.flush())
            {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::BrokenPipe,
                    format!("ffmpeg decoder stdin write failed: {error}"),
                ));
            }
        }
        // Opportunistic wait only: the frame released by this access unit is
        // usually already in the pipe, but a frame that needs a *later* access
        // unit must not stall the reader (that is what turned the mirror into
        // a 1 fps slideshow).
        let deadline = Instant::now() + frame_wait;
        let mut out = Vec::with_capacity(need);
        loop {
            {
                let mut buffer = lock(&process.buffer);
                if buffer.len() >= need {
                    let dropped = buffer.drain_newest(need, &mut out);
                    stats.frames.fetch_add(1, Ordering::Relaxed);
                    stats
                        .frames_dropped
                        .fetch_add(dropped as u64, Ordering::Relaxed);
                    return Ok(Some(BgraFrame {
                        bgra: out,
                        width,
                        height,
                    }));
                }
            }
            if process.exited.load(Ordering::SeqCst) {
                let note = lock(&process.exit_note).clone();
                return Err(std::io::Error::new(
                    std::io::ErrorKind::BrokenPipe,
                    format!("ffmpeg decoder exited: {note}"),
                ));
            }
            if Instant::now() >= deadline {
                return Ok(None);
            }
            std::thread::sleep(POLL_INTERVAL);
        }
    }

    fn stats(&self) -> DecoderStats {
        self.stats.snapshot()
    }

    /// Kill the child and start a fresh process (task constraint: reset = kill
    /// + restart). Dropping stdin first lets ffmpeg observe EOF cleanly.
    fn reset(&mut self) {
        if let Some(process) = self.process.take() {
            teardown_process(process);
        }
        self.spawn_error = None;
        self.ensure_process();
    }
}

impl Drop for FfmpegCliDecoder {
    fn drop(&mut self) {
        if let Some(process) = self.process.take() {
            teardown_process(process);
        }
    }
}

fn lock<T>(guard: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    guard
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn teardown_process(process: DecodeProcess) {
    let DecodeProcess {
        mut child, stdin, ..
    } = process;
    drop(stdin);
    let _ = child.kill();
    let _ = child.wait();
}
fn spawn_decode_process(executable: &Path) -> Result<DecodeProcess, String> {
    let mut command = Command::new(executable);
    command
        .args(FFMPEG_DECODER_ARGV)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = crate::spawn_executable(&mut command)
        .map_err(|error| format!("unable to spawn ffmpeg decoder: {error}"))?;
    let stdin = child
        .stdin
        .take()
        .ok_or_else(|| "ffmpeg decoder stdin unavailable".to_owned())?;
    let mut stdout = child
        .stdout
        .take()
        .ok_or_else(|| "ffmpeg decoder stdout unavailable".to_owned())?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| "ffmpeg decoder stderr unavailable".to_owned())?;
    let buffer = Arc::new(Mutex::new(ByteBuffer::default()));
    let exited = Arc::new(AtomicBool::new(false));
    let exit_note = Arc::new(Mutex::new(String::new()));

    // stderr must be drained on its own thread or ffmpeg blocks on a full pipe
    // (task constraint); keep a small tail for diagnostics.
    {
        let exit_note = exit_note.clone();
        let _ = std::thread::Builder::new()
            .name("aps-screencast-ffmpeg-stderr".to_owned())
            .spawn(move || {
                let tail = drain_to_tail(stderr);
                *lock(&exit_note) = tail;
            });
    }
    // stdout accumulator thread: push into the shared ByteBuffer with a
    // backpressure cap so a stalled consumer cannot exhaust memory.
    {
        let buffer = buffer.clone();
        let exited = exited.clone();
        let exit_note = exit_note.clone();
        let _ = std::thread::Builder::new()
            .name("aps-screencast-ffmpeg-stdout".to_owned())
            .spawn(move || {
                let mut chunk = vec![0u8; READ_CHUNK];
                loop {
                    if lock(&buffer).len() >= MAX_BUFFERED_BYTES {
                        std::thread::sleep(POLL_INTERVAL);
                        continue;
                    }
                    match stdout.read(&mut chunk) {
                        Ok(0) | Err(_) => break,
                        Ok(count) => lock(&buffer).push(&chunk[..count]),
                    }
                }
                exited.store(true, Ordering::SeqCst);
                let tail = lock(&exit_note).clone();
                *lock(&exit_note) = if tail.is_empty() {
                    "stdout closed".to_owned()
                } else {
                    format!("stdout closed; stderr tail: {tail}")
                };
            });
    }
    Ok(DecodeProcess {
        child,
        stdin: Some(stdin),
        buffer,
        exited,
        exit_note,
    })
}

fn drain_to_tail<R: Read>(mut stream: R) -> String {
    let mut sink = Vec::new();
    let _ = stream.read_to_end(&mut sink);
    let start = sink.len().saturating_sub(STDERR_TAIL_BYTES);
    String::from_utf8_lossy(&sink[start..]).into_owned()
}

/// Executable discovery: `FFMPEG_EXECUTABLE` → PATH → unavailable (task
/// constraint). A session that gets `None` here fails at startup.
pub fn discover_executable() -> Option<PathBuf> {
    let env_override = std::env::var_os(FFMPEG_EXECUTABLE_ENV);
    let path = std::env::var_os("PATH").unwrap_or_default();
    discover_from(env_override.as_deref(), &path)
}

/// Pure version of [`discover_executable`] for deterministic tests.
pub fn discover_from(
    env_override: Option<&std::ffi::OsStr>,
    path_value: &std::ffi::OsStr,
) -> Option<PathBuf> {
    if let Some(value) = env_override.filter(|value| !value.is_empty()) {
        let candidate = PathBuf::from(value);
        let found = if candidate.is_absolute() || candidate.components().count() > 1 {
            usable_executable(&candidate)
        } else {
            search_path_for(candidate.as_os_str(), path_value)
        };
        if let Some(found) = found {
            return Some(found);
        }
    }
    search_path_for(std::ffi::OsStr::new("ffmpeg"), path_value)
}

fn search_path_for(name: &std::ffi::OsStr, path_value: &std::ffi::OsStr) -> Option<PathBuf> {
    for directory in std::env::split_paths(path_value) {
        let candidate = directory.join(with_executable_suffix(&name.to_os_string()));
        if let Some(found) = usable_executable(&candidate) {
            return Some(found);
        }
    }
    None
}

/// cfg(windows): append `.exe` when missing (task constraint).
fn with_executable_suffix(name: &std::ffi::OsString) -> std::ffi::OsString {
    #[cfg(target_os = "windows")]
    {
        let text = name.to_string_lossy();
        if !text.to_ascii_lowercase().ends_with(".exe") {
            return std::ffi::OsString::from(format!("{text}.exe"));
        }
    }
    name.clone()
}

/// Same usability rules as the shared adb locator: a regular file with the
/// executable bit set (unix) or an `.exe` suffix (windows).
fn usable_executable(path: &Path) -> Option<PathBuf> {
    let metadata = path.metadata().ok()?;
    if !metadata.is_file() {
        return None;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return None;
        }
    }
    #[cfg(target_os = "windows")]
    if !path
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"))
    {
        return None;
    }
    path.canonicalize().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A fresh scratch directory for one test.
    ///
    /// The sequence number matters: `SystemTime::now()` can be served from a
    /// coarse clock (whole kernel ticks), so two tests starting in the same
    /// tick used to compute the same path — one would write the stub script
    /// while the other executed it, which the kernel rejects with
    /// `ETXTBSY` ("Text file busy").
    fn unique_temp_dir(label: &str) -> PathBuf {
        static SEQUENCE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let sequence = SEQUENCE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let directory = std::env::temp_dir().join(format!(
            "aps-screencast-{label}-{}-{}-{sequence}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or_default()
        ));
        std::fs::create_dir_all(&directory).expect("temp dir");
        directory
    }

    fn write_executable(directory: &Path, name: &str) -> PathBuf {
        let path = directory.join(name);
        std::fs::write(&path, b"#!stub\n").expect("stub file");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755))
                .expect("exec bit");
        }
        path
    }

    /// The ByteBuffer drains exactly one frame per call and keeps the rest.
    #[test]
    fn byte_buffer_drains_exact_frame_boundaries() {
        let mut buffer = ByteBuffer::default();
        buffer.push(&[1, 2, 3, 4, 5, 6, 7, 8]);
        let mut out = Vec::new();
        buffer.drain(5, &mut out);
        assert_eq!(out, [1, 2, 3, 4, 5]);
        assert_eq!(buffer.len(), 3);
        let mut out = Vec::new();
        buffer.drain(3, &mut out);
        assert_eq!(out, [6, 7, 8]);
        assert_eq!(buffer.len(), 0);
        buffer.push(&[9, 10]);
        buffer.drain(2, &mut out);
        assert_eq!(&out[3..], [9, 10]);
    }

    /// Newest-frame-wins: with three complete frames buffered, `drain_newest`
    /// returns the last one and reports the two it discarded, so a display
    /// that fell behind never replays stale frames.
    #[test]
    fn byte_buffer_newest_frame_wins() {
        let mut buffer = ByteBuffer::default();
        buffer.push(&[1, 1, 2, 2, 3, 3]);
        let mut out = Vec::new();
        let dropped = buffer.drain_newest(2, &mut out);
        assert_eq!(out, [3, 3]);
        assert_eq!(dropped, 2);
        assert_eq!(buffer.len(), 0);

        // Exactly one complete frame: nothing dropped, nothing left over.
        buffer.push(&[7, 7]);
        let mut out = Vec::new();
        assert_eq!(buffer.drain_newest(2, &mut out), 0);
        assert_eq!(out, [7, 7]);

        // A partial trailing frame stays buffered for the next arrival.
        buffer.push(&[9, 9, 10]);
        let mut out = Vec::new();
        assert_eq!(buffer.drain_newest(2, &mut out), 0);
        assert_eq!(out, [9, 9]);
        assert_eq!(buffer.len(), 1);
    }

    /// PATH lookup finds executables with the exec bit and skips the rest.
    #[test]
    fn discover_from_scans_path_directories() {
        let directory = unique_temp_dir("path");
        let expected = write_executable(&directory, "ffmpeg");
        let found = discover_from(None, directory.as_os_str()).expect("found");
        assert_eq!(found, expected.canonicalize().expect("canonical"));
        // No usable candidate → None.
        assert_eq!(discover_from(None, &std::ffi::OsString::new()), None);
        let _ = std::fs::remove_dir_all(&directory);
    }

    /// `FFMPEG_EXECUTABLE` wins over PATH; an unusable override falls back to
    /// the PATH scan.
    #[test]
    fn discover_from_prefers_a_usable_env_override() {
        let env_dir = unique_temp_dir("env");
        let path_dir = unique_temp_dir("path2");
        let env_exec = write_executable(&env_dir, "ffmpeg-custom");
        let path_exec = write_executable(&path_dir, "ffmpeg");
        assert_eq!(
            discover_from(Some(env_exec.as_os_str()), path_dir.as_os_str()).expect("env wins"),
            env_exec.canonicalize().expect("canonical")
        );
        // Invalid override → PATH is still consulted.
        assert_eq!(
            discover_from(
                Some(std::ffi::OsStr::new("/nonexistent/ffmpeg")),
                path_dir.as_os_str(),
            )
            .expect("path fallback"),
            path_exec.canonicalize().expect("canonical")
        );
        let _ = std::fs::remove_dir_all(&env_dir);
        let _ = std::fs::remove_dir_all(&path_dir);
    }

    /// A killed decoder process surfaces as `Err` instead of fabricating a
    /// frame (task constraint: "进程退出 → 返回 Err").
    #[test]
    fn decode_reports_a_dead_process_as_an_error() {
        let Some(executable) = discover_executable() else {
            eprintln!("SKIP: ffmpeg not found; dead-process test skipped");
            return;
        };
        let mut decoder = FfmpegCliDecoder::with_frame_wait(executable, Duration::from_millis(200));
        decoder.ensure_process();
        // Same-module test: reach into the private process handle and kill it.
        let process = decoder.process.as_mut().expect("process spawned");
        let _ = process.child.kill();
        let _ = process.child.wait();
        let error = decoder
            .decode(&[], 8, 8)
            .expect_err("dead process must surface as Err");
        let message = error.to_string();
        assert!(
            message.contains("ffmpeg decoder exited") || message.contains("stdin write failed"),
            "unexpected error: {message}"
        );
    }

    /// End-to-end against the real ffmpeg CLI: encode a 64×64 testsrc clip,
    /// feed config + access units, then close the input pipe — ffmpeg's raw
    /// h264 demuxer keeps the newest access unit pending until EOF (a live
    /// scrcpy stream keeps the pipe continuously fed instead).
    ///
    /// Verifies BGRA frames of exactly 64*64*4 bytes and, because the drain
    /// loop applies the newest-frame-wins rule, that the surviving frame is
    /// the end of the clip rather than a stale early one. Skipped (with a SKIP
    /// line) when ffmpeg/libx264 is unavailable, per the porting task's
    /// §10-equivalent decoder test contract.
    #[test]
    fn ffmpeg_cli_backend_decodes_testsrc_to_bgra_frames() {
        let Some(executable) = discover_executable() else {
            eprintln!("SKIP: ffmpeg not found on PATH/FFMPEG_EXECUTABLE");
            return;
        };
        let Some(stream) = generate_test_stream(&executable) else {
            eprintln!("SKIP: ffmpeg could not encode a testsrc H.264 stream (libx264 missing?)");
            return;
        };
        let split = split_access_units(&stream);
        assert!(
            !split.access_units.is_empty(),
            "splitter found no access units"
        );
        // Decode the same bytes through ffmpeg in one shot: the last frame of
        // that reference run is what the live decoder must end on too.
        let reference_last = decode_whole_stream_last_frame(&executable, &stream, 64, 64);

        let mut decoder = FfmpegCliDecoder::with_frame_wait(executable, Duration::from_millis(40));
        if !split.config.is_empty() {
            decoder.configure(&split.config);
        }
        // Feed everything; ffmpeg holds the tail of the stream until EOF.
        for unit in &split.access_units {
            decoder
                .decode(unit, 64, 64)
                .expect("valid stream must decode");
        }
        // Close the input pipe → ffmpeg flushes its decoder pipeline and exits.
        decoder.process.as_mut().expect("process").stdin.take();

        let mut frames = Vec::new();
        let mut attempts = 0;
        let error = loop {
            attempts += 1;
            assert!(
                attempts <= 500,
                "decoder stalled with {} frames",
                frames.len()
            );
            match decoder.decode(&[], 64, 64) {
                Ok(Some(frame)) => frames.push(frame),
                Ok(None) => continue,
                Err(error) => break error,
            }
        };
        assert!(
            error.to_string().contains("ffmpeg decoder exited"),
            "drain loop must end with the process exit, got: {error}"
        );
        let last = frames.last().expect("the clip must decode to a frame");
        assert_eq!(last.width, 64);
        assert_eq!(last.height, 64);
        assert_eq!(last.bgra.len(), 64 * 64 * 4);
        if let Some(reference_last) = reference_last {
            assert_eq!(
                last.bgra, reference_last,
                "the newest-frame-wins drain must end on the clip's last frame"
            );
        }
        assert!(
            decoder.stats().frames >= 1 && decoder.stats().packets > 0,
            "stats must count the fed access units: {:?}",
            decoder.stats()
        );
    }

    /// Reference decode of a finite Annex-B stream in a single ffmpeg run:
    /// returns the final BGRA frame (the ground truth for the last frame).
    fn decode_whole_stream_last_frame(
        executable: &Path,
        stream: &[u8],
        width: u32,
        height: u32,
    ) -> Option<Vec<u8>> {
        let mut child = Command::new(executable)
            .args(FFMPEG_DECODER_ARGV)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let mut stdin = child.stdin.take()?;
        let bytes = stream.to_vec();
        let writer = std::thread::spawn(move || {
            use std::io::Write;
            let _ = stdin.write_all(&bytes);
        });
        let mut stdout = child.stdout.take()?;
        let mut sink = Vec::new();
        let _ = stdout.read_to_end(&mut sink);
        let _ = writer.join();
        let _ = child.wait();
        let frame_len = usize::try_from(u64::from(width) * u64::from(height) * 4).ok()?;
        if sink.len() < frame_len {
            return None;
        }
        Some(sink[sink.len() - frame_len..].to_vec())
    }

    /// Generates an H.264 Annex-B stream with the local ffmpeg CLI
    /// (`testsrc`, 64×64, 10 fps, 0.5 s → 5 frames).
    fn generate_test_stream(executable: &Path) -> Option<Vec<u8>> {
        let output = Command::new(executable)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=0.5:size=64x64:rate=10",
                "-c:v",
                "libx264",
                "-f",
                "h264",
                "-",
            ])
            .stdin(Stdio::null())
            .output()
            .ok()?;
        if !output.status.success() || output.stdout.is_empty() {
            eprintln!(
                "testsrc generation failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
            return None;
        }
        Some(output.stdout)
    }

    struct SplitStream {
        config: Vec<u8>,
        access_units: Vec<Vec<u8>>,
    }

    /// Splits an x264 Annex-B stream into (SPS/PPS/SEI/AUD) + access units.
    /// Slice NALs (types 1/5) end the previous access unit; parameter sets,
    /// SEI and access-unit delimiters ride with the codec config while no
    /// slice unit is open (before the first IDR they would otherwise become a
    /// slice-less phantom unit).
    fn split_access_units(stream: &[u8]) -> SplitStream {
        let mut config = Vec::new();
        let mut units = Vec::new();
        let mut current: Option<Vec<u8>> = None;
        for (nal_type, range) in annex_b_nals(stream) {
            let bytes = &stream[range];
            match nal_type {
                // SPS(7)/PPS(8)/SEI(6)/AUD(9): non-slice NALs.
                6..=9 => match current.as_mut() {
                    Some(unit) => unit.extend_from_slice(bytes),
                    None => config.extend_from_slice(bytes),
                },
                1 | 5 => {
                    if let Some(unit) = current.take() {
                        units.push(unit);
                    }
                    let mut unit = Vec::new();
                    unit.extend_from_slice(bytes);
                    current = Some(unit);
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
        SplitStream {
            config,
            access_units: units,
        }
    }

    /// Scans Annex-B start codes (00 00 01, with any leading zeros) and yields
    /// `(nal_type, byte range including the start code)`.
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
}
