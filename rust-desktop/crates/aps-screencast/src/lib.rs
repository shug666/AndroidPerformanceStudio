//! Rust port of the APS device screencast module, speaking the embedded
//! scrcpy-server v3.1 protocol.
//!
//! Contract source: the port spec `/tmp/aps-port-spec-screencast.md`
//! (Kotlin `desktop-viewer/device-screencast`). Wire fidelity notes:
//! - control messages (§2) and device messages (§3) are big-endian;
//! - the video stream (§4) is `64 B device name + 12 B metadata` followed by
//!   `8 B ptsAndFlags + 4 B size + payload` frame packets;
//! - the decoder deliberately spawns the system `ffmpeg` CLI instead of the
//!   Kotlin in-process libavcodec chain (spec §5 route (b), task-mandated
//!   deviation; see `decoder`).
//!
//! Module map: `quality` (§7 presets), `control` (§2 writer), `device_message`
//! (§3 reader), `video_stream` (§4 reader), `decoder` (§5 backend), `launcher`
//! (§1 push/reverse/argv/cleanup + adb runner), `session` (§6 state machine),
//! `record` (host-side MP4 recording tee).
//!
//! Bundled assets: `assets/scrcpy-server` is the upstream scrcpy-server v3.1
//! jar (included via `include_bytes!` in `launcher`); `assets/scrcpy-LICENSE`
//! carries its Apache-2.0 license text.

pub mod control;
pub mod decoder;
pub mod device_message;
pub mod launcher;
pub mod quality;
pub mod record;
pub mod session;
pub mod video_stream;

pub use decoder::{BgraFrame, FrameDecoder};
pub use quality::ScreencastQuality;
pub use session::{ScrcpySession, ScrcpySessionState, SessionConfig};

/// Spawns `command`, retrying briefly while the OS reports the executable as
/// busy (`ETXTBSY`, "Text file busy").
///
/// `execve` refuses a file that still has a write reference, and that
/// reference can outlive the write call by a few milliseconds — a freshly
/// written stub script in a test, or a host `adb`/`ffmpeg` that an updater has
/// just replaced. Retrying is the documented remedy and costs nothing when the
/// executable is stable.
pub(crate) fn spawn_executable(
    command: &mut std::process::Command,
) -> std::io::Result<std::process::Child> {
    const ATTEMPTS: u32 = 20;
    const RETRY_DELAY: std::time::Duration = std::time::Duration::from_millis(5);
    let mut attempts = 0;
    loop {
        match command.spawn() {
            Ok(child) => return Ok(child),
            Err(error) if is_executable_busy(&error) && attempts < ATTEMPTS => {
                attempts += 1;
                std::thread::sleep(RETRY_DELAY);
            }
            Err(error) => return Err(error),
        }
    }
}

fn is_executable_busy(error: &std::io::Error) -> bool {
    if error.kind() == std::io::ErrorKind::ExecutableFileBusy {
        return true;
    }
    #[cfg(unix)]
    if error.raw_os_error() == Some(26) {
        return true;
    }
    false
}

/// Reads exactly `buffer.len()` bytes, reporting clean EOF (`Ok(false)`) when
/// the stream ends with nothing or a partial prefix read (§4: a clean stream
/// end is a session end signal, not an error).
pub(crate) fn read_exact_or_eof<R: std::io::Read>(
    reader: &mut R,
    buffer: &mut [u8],
) -> std::io::Result<bool> {
    let mut filled = 0;
    while filled < buffer.len() {
        match reader.read(&mut buffer[filled..]) {
            Ok(0) => return Ok(false),
            Ok(count) => filled += count,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(true)
}
