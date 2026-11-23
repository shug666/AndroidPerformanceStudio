//! The blocking adb transport used by the capture engine. Unlike the read-only
//! `aps-adb` adapter, the Winscope protocol must run root shells, long-lived trace
//! shells with cancellation and streamed fetches; those primitives live here and
//! are exercised only against an explicitly located adb executable.
//!
//! Cancellation mirrors the reference implementation: `/end` (or the keep-alive
//! timeout) kills the *local* adb client; the device-side trap in the started
//! shell script then performs the actual stop and writes `TRACE_OK`.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Output cap for captured streams. Trace shells print only banners; probes are
/// small. The cap keeps a misbehaving device from exhausting memory (the Kotlin
/// port hit the JVM array cap with unbounded `exec-out` capture).
const MAX_CAPTURED_BYTES: usize = 512 * 1024;

/// Outcome of one adb invocation.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AdbOutput {
    pub stdout: String,
    pub stderr: String,
    /// Local adb exit status. `adb shell` does not propagate remote exit codes,
    /// so command-level checks pair this with the stderr text like the Kotlin
    /// port did.
    pub success: bool,
}

impl AdbOutput {
    /// The reference implementation merges stderr into stdout for version probes
    /// (`subprocess.STDOUT`); callers consult both streams equivalently.
    pub fn merged(&self) -> String {
        let mut merged = self.stdout.clone();
        merged.push_str(&self.stderr);
        merged
    }
}

/// Options for one shell invocation.
#[derive(Clone)]
pub struct ShellOptions {
    /// Hard deadline: the child is killed when it fires (trace shells run with a
    /// 24h ceiling like the Kotlin port; probes use a few seconds).
    pub timeout: Duration,
    /// Cooperative cancellation: when flipped, the local adb client is killed.
    pub cancel: Option<Arc<AtomicBool>>,
    /// Poll interval while waiting for exit or cancellation.
    pub poll: Duration,
}

impl Default for ShellOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(10),
            cancel: None,
            poll: Duration::from_millis(50),
        }
    }
}

impl ShellOptions {
    pub fn probe() -> Self {
        Self {
            timeout: Duration::from_secs(8),
            ..Self::default()
        }
    }

    pub fn trace_shell(cancel: Arc<AtomicBool>) -> Self {
        Self {
            timeout: Duration::from_secs(24 * 3600),
            cancel: Some(cancel),
            poll: Duration::from_millis(100),
        }
    }
}

/// Result of a cancelled or timed-out shell: the local client died, which is the
/// reference implementation's intended stop mechanism.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShellAbort {
    pub timed_out: bool,
}

/// Outcome of one adb invocation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ShellOutcome {
    Completed(AdbOutput),
    Aborted(ShellAbort),
}

impl ShellOutcome {
    /// Completed output, or an empty output carrying the abort reason on stderr
    /// (mirrors the Kotlin port, whose cancelled shells surface the abort as the
    /// error text).
    pub fn output_or_abort(&self) -> (AdbOutput, bool) {
        match self {
            Self::Completed(output) => (output.clone(), false),
            Self::Aborted(abort) => (
                AdbOutput {
                    stdout: String::new(),
                    stderr: if abort.timed_out {
                        "adb command timed out".to_owned()
                    } else {
                        "adb shell cancelled".to_owned()
                    },
                    success: false,
                },
                !abort.timed_out,
            ),
        }
    }
}

/// Transport bound to one adb executable.
#[derive(Debug, Clone)]
pub struct AdbTransport {
    executable: PathBuf,
    environment: Vec<(std::ffi::OsString, std::ffi::OsString)>,
}

impl AdbTransport {
    /// Resolves adb through the shared locator (configured SDK first, environment,
    /// then PATH).
    pub fn locate(explicit: Option<&Path>) -> Result<Self, String> {
        let location = aps_adb::AdbLocator::new()
            .locate(explicit)
            .map_err(|error| error.to_string())?;
        Ok(Self::from_location(location))
    }

    pub fn from_location(location: aps_adb::AdbLocation) -> Self {
        Self {
            executable: location.executable,
            environment: std::env::vars_os().collect(),
        }
    }

    pub fn executable(&self) -> &Path {
        &self.executable
    }

    fn command(&self, arguments: &[&str]) -> Command {
        let mut command = Command::new(&self.executable);
        command.args(arguments);
        for (key, value) in &self.environment {
            command.env(key, value);
        }
        command
    }

    /// Runs a bounded adb command, capturing both streams.
    pub fn run(&self, arguments: &[&str], options: &ShellOptions) -> ShellOutcome {
        let spawn = self
            .command(arguments)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .stdin(Stdio::null())
            .spawn();
        let mut child = match spawn {
            Ok(child) => child,
            Err(error) => {
                return ShellOutcome::Completed(AdbOutput {
                    stdout: String::new(),
                    stderr: format!("Unable to spawn adb: {error}"),
                    success: false,
                });
            }
        };
        run_child(&mut child, options)
    }

    /// Streams `exec-out su root cat <remote path>` into a local file, matching
    /// the reference fetch path (`docs/winscope-proxy-protocol.md` §2.6) while
    /// keeping memory bounded by the file system instead of an in-process buffer.
    pub fn fetch_to_file(
        &self,
        serial: &str,
        remote_path: &str,
        destination: &Path,
    ) -> Result<u64, String> {
        let output = std::fs::File::create(destination)
            .map_err(|error| format!("Unable to create fetch target: {error}"))?;
        let spawn = self
            .command(&["-s", serial, "exec-out", "su", "root", "cat", remote_path])
            .stdout(Stdio::from(output))
            .stderr(Stdio::piped())
            .stdin(Stdio::null())
            .spawn();
        let mut child = spawn.map_err(|error| format!("Unable to spawn adb fetch: {error}"))?;
        // Stdout is attached to the destination file; drain stderr on a helper
        // thread so a chatty adb never blocks on a full pipe.
        let stderr_handle = child.stderr.take();
        let stderr_drain = std::thread::spawn(move || drain_to_string(stderr_handle));
        let status = child
            .wait()
            .map_err(|error| format!("Unable to wait for adb fetch: {error}"))?;
        let stderr = stderr_drain.join().unwrap_or_default();
        if !status.success() {
            return Err(format!("adb fetch exited with {status}: {stderr}"));
        }
        std::fs::metadata(destination)
            .map(|metadata| metadata.len())
            .map_err(|error| format!("Fetched file disappeared: {error}"))
    }
}

/// Shared wait loop: polls exit/cancellation/timeout, kills the child on cancel
/// or deadline, and collects both streams.
fn run_child(child: &mut std::process::Child, options: &ShellOptions) -> ShellOutcome {
    // Take the pipes before waiting so the streams never fill their OS buffers
    // and deadlock the child.
    let stdout_handle = child.stdout.take();
    let stderr_handle = child.stderr.take();
    let stdout_sink = std::thread::spawn(move || drain_to_string(stdout_handle));
    let stderr_sink = std::thread::spawn(move || drain_to_string(stderr_handle));

    let started = Instant::now();
    let mut killed = false;
    let mut timed_out = false;
    loop {
        if !killed {
            if let Some(cancel) = &options.cancel {
                if cancel.load(Ordering::SeqCst) {
                    let _ = child.kill();
                    killed = true;
                }
            }
        }
        if !killed && started.elapsed() >= options.timeout {
            let _ = child.kill();
            killed = true;
            timed_out = true;
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                let stdout = stdout_sink.join().unwrap_or_default();
                let stderr = stderr_sink.join().unwrap_or_default();
                if timed_out {
                    return ShellOutcome::Completed(AdbOutput {
                        stdout,
                        stderr: format!("{stderr}\nadb command timed out"),
                        success: false,
                    });
                }
                if killed {
                    return ShellOutcome::Aborted(ShellAbort { timed_out: false });
                }
                return ShellOutcome::Completed(AdbOutput {
                    stdout,
                    stderr,
                    success: status.success(),
                });
            }
            Ok(None) => {
                if killed {
                    // Let the process die and the pipes close before re-checking.
                    std::thread::sleep(Duration::from_millis(10));
                } else {
                    std::thread::sleep(options.poll);
                }
            }
            Err(error) => {
                let stdout = stdout_sink.join().unwrap_or_default();
                let _stderr = stderr_sink.join().unwrap_or_default();
                return ShellOutcome::Completed(AdbOutput {
                    stdout,
                    stderr: format!("adb wait failed: {error}"),
                    success: false,
                });
            }
        }
    }
}

fn drain_to_string<R: Read>(handle: Option<R>) -> String {
    let Some(mut handle) = handle else {
        return String::new();
    };
    let mut sink = Vec::new();
    let _ = handle.read_to_end(&mut sink);
    String::from_utf8_lossy(&truncate_utf8(&sink, MAX_CAPTURED_BYTES)).into_owned()
}

fn truncate_utf8(bytes: &[u8], limit: usize) -> &[u8] {
    if bytes.len() <= limit {
        return bytes;
    }
    let mut end = limit;
    while end > 0 && std::str::from_utf8(&bytes[..end]).is_err() {
        end -= 1;
    }
    &bytes[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncated_output_stays_valid_utf8() {
        let bytes = vec![b'a'; 4096];
        assert_eq!(truncate_utf8(&bytes, 16).len(), 16);
        // A split multi-byte sequence is trimmed back to a valid boundary.
        let mut split = vec![b'x'; 14];
        split.push(0xC3);
        split.push(0xA9);
        let truncated = truncate_utf8(&split, 15);
        assert!(std::str::from_utf8(truncated).is_ok());
    }

    #[test]
    fn merged_output_appends_stderr() {
        let output = AdbOutput {
            stdout: "1.4\n".to_owned(),
            stderr: String::new(),
            success: true,
        };
        assert_eq!(output.merged(), "1.4\n");
        let output = AdbOutput {
            stdout: String::new(),
            stderr: "v1.4".to_owned(),
            success: true,
        };
        assert_eq!(output.merged(), "v1.4");
    }

    #[test]
    fn abort_outcomes_carry_the_reason_as_stderr() {
        let (output, cancelled) = ShellOutcome::Aborted(ShellAbort { timed_out: false }).output_or_abort();
        assert!(cancelled);
        assert_eq!(output.stderr, "adb shell cancelled");
        let (output, cancelled) = ShellOutcome::Aborted(ShellAbort { timed_out: true }).output_or_abort();
        assert!(!cancelled);
        assert_eq!(output.stderr, "adb command timed out");
    }

    #[test]
    fn trace_shell_options_wait_a_day_and_probe_options_do_not() {
        assert_eq!(ShellOptions::trace_shell(Arc::new(AtomicBool::new(false))).timeout, Duration::from_secs(24 * 3600));
        assert_eq!(ShellOptions::probe().timeout, Duration::from_secs(8));
    }
}
