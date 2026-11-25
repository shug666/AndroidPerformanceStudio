//! Host-side recording: the video loop tees every raw H.264 Annex-B packet
//! (codec config + access units) into a dedicated system `ffmpeg` CLI process
//! that remuxes the stream (`-c:v copy`) straight into an MP4 file.
//!
//! Two timing facts shape the design:
//! - The scrcpy stream is VFR (frames only on damage). The raw `h264`
//!   demuxer has no per-packet timestamps to copy, so the recorder seeds the
//!   input `framerate` with the session's `max_fps`: active interaction stays
//!   real-time while fully idle stretches compress.
//! - A recording started mid-session would otherwise begin on an arbitrary
//!   access unit without SPS/PPS; the session therefore sends `RESET_VIDEO`
//!   first, which makes the server re-send the codec config and resume on an
//!   IDR — the recorder always starts on a decodable boundary.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::time::{Duration, Instant};

/// Bounded wait for ffmpeg to write the MP4 trailer after stdin EOF.
const FINALIZE_TIMEOUT_SECS: u64 = 5;
/// Poll interval while waiting for the finalize exit.
const FINALIZE_POLL: Duration = Duration::from_millis(20);
/// stderr tail kept for diagnostics.
const STDERR_TAIL_BYTES: usize = 4096;

/// argv of the recorder process (mirrors the decoder's task-mandated shape:
/// `-nostdin` keeps ffmpeg from grabbing the terminal).
pub fn recorder_argv(max_fps: u32, output: &Path) -> Vec<String> {
    vec![
        "-nostdin".to_owned(),
        "-loglevel".to_owned(),
        "error".to_owned(),
        "-f".to_owned(),
        "h264".to_owned(),
        "-framerate".to_owned(),
        max_fps.to_string(),
        "-i".to_owned(),
        "pipe:0".to_owned(),
        "-c:v".to_owned(),
        "copy".to_owned(),
        output.to_string_lossy().into_owned(),
    ]
}

/// One active recording: the ffmpeg muxer child plus its stdin pipe.
pub struct VideoRecorder {
    child: Child,
    /// `Some` while the input pipe is open; closing it makes ffmpeg write the
    /// MP4 trailer and exit ([`Self::finish`]).
    stdin: Option<ChildStdin>,
    path: PathBuf,
    /// Human-readable failure tail from stderr (written by the drain thread).
    stderr_tail: std::sync::Arc<std::sync::Mutex<String>>,
}

impl VideoRecorder {
    /// Spawns the muxer process. Failure carries a spawn-time message; later
    /// write failures surface through [`Self::write`].
    pub fn spawn(executable: &Path, path: PathBuf, max_fps: u32) -> Result<Self, String> {
        let mut command = Command::new(executable);
        command
            .args(recorder_argv(max_fps, &path))
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::piped());
        let mut child = command
            .spawn()
            .map_err(|error| format!("unable to spawn ffmpeg recorder: {error}"))?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| "ffmpeg recorder stdin unavailable".to_owned())?;
        let mut stderr = child
            .stderr
            .take()
            .ok_or_else(|| "ffmpeg recorder stderr unavailable".to_owned())?;
        let stderr_tail = std::sync::Arc::new(std::sync::Mutex::new(String::new()));
        // stderr must be drained or ffmpeg blocks on a full pipe (same
        // constraint as the decoder); keep a small tail for diagnostics.
        {
            let stderr_tail = stderr_tail.clone();
            let _ = std::thread::Builder::new()
                .name("aps-screencast-recorder-stderr".to_owned())
                .spawn(move || {
                    let mut sink = Vec::new();
                    let _ = stderr.read_to_end(&mut sink);
                    let start = sink.len().saturating_sub(STDERR_TAIL_BYTES);
                    *stderr_tail
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner()) =
                        String::from_utf8_lossy(&sink[start..]).into_owned();
                });
        }
        Ok(Self {
            child,
            stdin: Some(stdin),
            path,
            stderr_tail,
        })
    }

    /// Destination file chosen when the recording started.
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Writes one raw packet payload (config packet or access unit).
    pub fn write(&mut self, bytes: &[u8]) -> std::io::Result<()> {
        let Some(stdin) = self.stdin.as_mut() else {
            return Err(std::io::Error::other("recorder input already closed"));
        };
        stdin.write_all(bytes).and_then(|_| stdin.flush())
    }

    /// Closes the input pipe (EOF → MP4 trailer) and waits for the muxer to
    /// exit, bounded. Returns the finalized file, or a failure message.
    pub fn finish(mut self) -> Result<PathBuf, String> {
        self.stdin = None;
        let deadline = Instant::now() + Duration::from_secs(FINALIZE_TIMEOUT_SECS);
        loop {
            match self.child.try_wait() {
                Ok(Some(status)) => {
                    return if status.success() {
                        Ok(self.path.clone())
                    } else {
                        Err(self.failure("ffmpeg recorder failed"))
                    };
                }
                Ok(None) => {}
                Err(error) => return Err(format!("ffmpeg recorder wait failed: {error}")),
            }
            if Instant::now() >= deadline {
                let _ = self.child.kill();
                let _ = self.child.wait();
                return Err("ffmpeg recorder finalization timed out".to_owned());
            }
            std::thread::sleep(FINALIZE_POLL);
        }
    }

    fn failure(&self, prefix: &str) -> String {
        let tail = self
            .stderr_tail
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .trim()
            .to_owned();
        if tail.is_empty() {
            prefix.to_owned()
        } else {
            format!("{prefix}: {tail}")
        }
    }
}

impl Drop for VideoRecorder {
    /// Safety net for paths that never reach [`Self::finish`] (a panicked
    /// session thread): kill the muxer. On an already-exited child both calls
    /// are harmless no-ops.
    fn drop(&mut self) {
        self.stdin = None;
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unique_temp_dir(label: &str) -> PathBuf {
        let directory = std::env::temp_dir().join(format!(
            "aps-screencast-record-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or_default()
        ));
        std::fs::create_dir_all(&directory).expect("temp dir");
        directory
    }

    fn write_executable(directory: &Path, name: &str, body: &str) -> PathBuf {
        let path = directory.join(name);
        std::fs::write(&path, body).expect("stub file");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755))
                .expect("exec bit");
        }
        path
    }

    /// The recorder argv is pinned: raw h264 input from stdin, framerate
    /// seeded from the session's max_fps, stream copy to the output path.
    #[test]
    fn recorder_argv_matches_the_layout() {
        let argv = recorder_argv(60, Path::new("/tmp/out.mp4"));
        assert_eq!(
            argv,
            [
                "-nostdin",
                "-loglevel",
                "error",
                "-f",
                "h264",
                "-framerate",
                "60",
                "-i",
                "pipe:0",
                "-c:v",
                "copy",
                "/tmp/out.mp4"
            ]
        );
        // Fluent mirrors at 10 fps; the seeded rate follows the session.
        assert_eq!(recorder_argv(10, Path::new("x.mp4"))[6], "10");
    }

    /// End-to-end against a stub ffmpeg: the recorder pipes written bytes to
    /// stdin, `finish` closes it and reports the output path on success.
    #[cfg(unix)]
    #[test]
    fn stub_recorder_pipes_input_and_finishes_cleanly() {
        let directory = unique_temp_dir("stub");
        let log_path = directory.join("ffmpeg-log.txt");
        let log = log_path.to_string_lossy().replace('\'', "'\\''");
        let output = directory.join("out.mp4");
        let stub = write_executable(
            &directory,
            "ffmpeg",
            &format!(
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '{log}'\ncat > '{out}'\nexit 0\n",
                out = output.to_string_lossy().replace('\'', "'\\''"),
            ),
        );

        let mut recorder =
            VideoRecorder::spawn(&stub, output.clone(), 60).expect("stub spawn");
        recorder.write(&[0, 0, 0, 1, 0x67]).expect("config write");
        recorder.write(&[0, 0, 0, 1, 0x41]).expect("unit write");
        assert_eq!(recorder.path(), output.as_path());
        assert_eq!(recorder.finish().expect("finish"), output);

        let log = std::fs::read_to_string(&log_path).expect("stub log");
        assert_eq!(
            log.lines().collect::<Vec<_>>(),
            recorder_argv(60, &output)
        );
        assert_eq!(std::fs::read(&output).expect("output"), [0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x41]);
        let _ = std::fs::remove_dir_all(&directory);
    }

    /// A failing muxer (non-zero exit) surfaces through `finish` with the
    /// stderr tail instead of a success path.
    #[cfg(unix)]
    #[test]
    fn finish_reports_a_failing_muxer() {
        let directory = unique_temp_dir("fail");
        let stub = write_executable(
            &directory,
            "ffmpeg",
            "#!/bin/sh\ncat > /dev/null\necho boom >&2\nexit 3\n",
        );
        let recorder = VideoRecorder::spawn(&stub, directory.join("x.mp4"), 60).expect("spawn");
        let error = recorder.finish().expect_err("exit 3 must fail");
        assert!(error.contains("boom"), "unexpected error: {error}");
        let _ = std::fs::remove_dir_all(&directory);
    }

    /// Real-ffmpeg end-to-end: remux a generated testsrc stream into MP4 and
    /// validate the file container (ftyp box). Skipped when ffmpeg/libx264 is
    /// unavailable, matching the decoder test contract.
    #[test]
    fn real_remux_produces_an_mp4_container() {
        let Some(executable) = crate::decoder::discover_executable() else {
            eprintln!("SKIP: ffmpeg not found on PATH/FFMPEG_EXECUTABLE");
            return;
        };
        let directory = unique_temp_dir("real");
        let output = directory.join("out.mp4");
        let mut recorder = VideoRecorder::spawn(&executable, output.clone(), 60)
            .expect("real recorder spawn");
        recorder.write(&test_stream(&executable)).expect("stream write");
        recorder.finish().expect("finish");

        let bytes = std::fs::read(&output).expect("mp4 file");
        assert!(&bytes[4..8] == b"ftyp", "not an MP4 container");
        assert!(bytes.len() > 1024, "suspiciously small mp4");
        let _ = std::fs::remove_dir_all(&directory);
    }

    /// Generates an H.264 Annex-B stream with the local ffmpeg CLI
    /// (`testsrc`, 64×64, 10 fps, 0.5 s).
    fn test_stream(executable: &Path) -> Vec<u8> {
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
            .expect("testsrc generation");
        assert!(output.status.success(), "testsrc encode failed");
        output.stdout
    }
}
