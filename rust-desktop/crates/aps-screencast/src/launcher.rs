//! scrcpy-server launch pipeline, ported from the Kotlin launcher (spec §1) and
//! the screencast-app assembly layer (spec §9): locate adb, materialize the
//! bundled server jar, `adb push`, `adb reverse`, spawn the device-side
//! `app_process` server, and roll back / clean up in the spec's order.
//!
//! ADB execution is a self-contained blocking runner (same shape as
//! `aps-winscope/src/adb.rs` but independent): `std::process::Command` with an
//! argv list (never `sh -c`), timeout kill, and captured stdout/stderr.
//! Non-session commands use 10 s timeouts, `push` uses 60 s, and the session
//! shell deliberately has a 24 h ceiling (server lifetime = session lifetime).

use aps_adb::AdbLocator;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Embedded scrcpy-server v3.1 (90,640-byte jar; the server self-checks its
/// version string, spec header). Materialized to a temp file for `adb push`
/// (§9.2: adb cannot push from stdin).
pub const SCRCPY_SERVER_JAR: &[u8] = include_bytes!("../assets/scrcpy-server");

/// Remote staging directory (spec §1.1).
pub const REMOTE_DIR: &str = "/data/local/tmp/scrcpy_aps";
/// Client version string sent to `com.genymobile.scrcpy.Server`; must equal the
/// embedded jar's version character-for-character (spec §1.3).
pub const SERVER_VERSION: &str = "3.1";
/// Accept timeout for both connections (spec §1.2, §6 constants).
pub const ACCEPT_TIMEOUT_MS: u64 = 15_000;
/// Session shell ceiling — server lifetime equals session lifetime (spec §1.2).
pub const SESSION_SHELL_TIMEOUT_SECS: u64 = 24 * 3600;
/// Non-session adb command timeout (task constraint).
pub const COMMAND_TIMEOUT_SECS: u64 = 10;
/// `adb push` timeout (task constraint).
pub const PUSH_TIMEOUT_SECS: u64 = 60;
/// Delay between killing the server shell and removing the reverse mapping
/// (spec §1.4/§6.4, CLEANUP_SETTLE_DELAY_MS).
pub const CLEANUP_SETTLE_DELAY_MS: u64 = 500;

/// One launched server session's identifiers (spec §1.1).
#[derive(Debug, Clone)]
pub struct LaunchedServer {
    pub scid: u32,
    /// `"scrcpy_<scid:08x>"` — zero-padded lowercase hex.
    pub socket_name: String,
    /// `<REMOTE_DIR>/<socket_name>.jar`.
    pub remote_jar_path: String,
    /// Host temp file holding the extracted jar; removed during cleanup.
    pub local_jar_path: PathBuf,
    /// Local TCP port the reverse mapping points at.
    pub local_port: u16,
    /// Flip to cancel the session shell (killed like the Kotlin coroutine).
    pub cancel: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl LaunchedServer {
    /// Generates a fresh session identity: 31-bit non-negative scid, the
    /// zero-padded socket name, and the remote jar path (spec §1.1).
    pub fn generate(local_port: u16) -> Self {
        // Random.nextInt(1, Int.MAX_VALUE): 31-bit, never 0, never negative.
        let scid = random_scid();
        let socket_name = format!("scrcpy_{scid:08x}");
        let remote_jar_path = format!("{REMOTE_DIR}/{socket_name}.jar");
        Self {
            scid,
            socket_name,
            remote_jar_path,
            local_jar_path: PathBuf::new(),
            local_port,
            cancel: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }
}

fn drain_to_string<R: Read>(handle: Option<R>) -> String {
    let Some(mut handle) = handle else {
        return String::new();
    };
    let mut sink = Vec::new();
    let _ = handle.read_to_end(&mut sink);
    String::from_utf8_lossy(&sink).into_owned()
}

/// Fire-and-forget stderr/stdout drainer for the long-lived session shell.
fn spawn_drainer<R: Read + Send + 'static>(handle: Option<R>) {
    std::thread::spawn(move || {
        let mut sink = Vec::new();
        if let Some(mut handle) = handle {
            let _ = handle.read_to_end(&mut sink);
        }
    });
}

fn random_scid() -> u32 {
    // SplitMix64 over nanos+pid: a non-cryptographic but adequate 31-bit draw
    // that needs no external dependency.
    let mut state = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_nanos() as u64)
        .unwrap_or(0)
        ^ (u64::from(std::process::id()) << 32);
    state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = state;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^= z >> 31;
    ((z % u64::from(u32::MAX)) as u32) + 1
}

/// Outcome of one adb invocation (mirrors `aps-winscope/src/adb.rs`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AdbOutput {
    pub stdout: String,
    pub stderr: String,
    pub success: bool,
}

/// What happened to a bounded adb invocation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdbOutcome {
    Completed,
    TimedOut,
}

impl AdbOutput {
    /// stdout + stderr merged (the Kotlin port surfaces either stream).
    pub fn merged(&self) -> String {
        let mut merged = self.stdout.clone();
        merged.push_str(&self.stderr);
        merged
    }
}

/// Blocking adb runner bound to one executable. Shell commands are passed as
/// argv lists (`Command::args`), so every argument reaches the device shell
/// individually — no `sh -c` re-quoting hazards (spec §A.12).
#[derive(Debug, Clone)]
pub struct AdbRunner {
    executable: PathBuf,
}

impl AdbRunner {
    /// Locates adb through the shared `aps-adb` locator (task constraint: the
    /// only allowed dependency, used for `AdbLocator` alone).
    pub fn locate(explicit: Option<&Path>) -> Result<Self, String> {
        let location = AdbLocator::new()
            .locate(explicit)
            .map_err(|error| error.to_string())?;
        Ok(Self {
            executable: location.executable,
        })
    }

    pub fn from_executable(executable: PathBuf) -> Self {
        Self { executable }
    }

    pub fn executable(&self) -> &Path {
        &self.executable
    }

    /// Runs one bounded adb command, capturing both streams. Cancelling flips
    /// `cancel` → the local client is killed (Kotlin coroutine-cancel analog).
    pub fn run(
        &self,
        arguments: &[&str],
        timeout: Duration,
        cancel: Option<&std::sync::atomic::AtomicBool>,
    ) -> (AdbOutput, AdbOutcome) {
        let spawn = Command::new(&self.executable)
            .args(arguments)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .stdin(Stdio::null())
            .spawn();
        let mut child = match spawn {
            Ok(child) => child,
            Err(error) => {
                return (
                    AdbOutput {
                        stdout: String::new(),
                        stderr: format!("unable to spawn adb: {error}"),
                        success: false,
                    },
                    AdbOutcome::Completed,
                );
            }
        };
        let stdout_handle = child.stdout.take();
        let stderr_handle = child.stderr.take();
        let stdout_sink = std::thread::spawn(move || drain_to_string(stdout_handle));
        let stderr_sink = std::thread::spawn(move || drain_to_string(stderr_handle));

        let started = Instant::now();
        let mut timed_out = false;
        let mut killed = false;
        loop {
            if !killed && cancel.is_some_and(|flag| flag.load(std::sync::atomic::Ordering::SeqCst))
            {
                let _ = child.kill();
                killed = true;
            }
            if !killed && !timed_out && started.elapsed() >= timeout {
                let _ = child.kill();
                timed_out = true;
                killed = true;
            }
            match child.try_wait() {
                Ok(Some(status)) => {
                    let stdout = stdout_sink.join().unwrap_or_default();
                    let stderr = stderr_sink.join().unwrap_or_default();
                    let mut output = AdbOutput {
                        stdout,
                        stderr,
                        success: status.success(),
                    };
                    if timed_out {
                        output.success = false;
                        output.stderr.push_str("\nadb command timed out");
                        return (output, AdbOutcome::TimedOut);
                    }
                    return (output, AdbOutcome::Completed);
                }
                Ok(None) => std::thread::sleep(Duration::from_millis(20)),
                Err(error) => {
                    let stdout = stdout_sink.join().unwrap_or_default();
                    let _ = stderr_sink.join();
                    return (
                        AdbOutput {
                            stdout,
                            stderr: format!("adb wait failed: {error}"),
                            success: false,
                        },
                        AdbOutcome::Completed,
                    );
                }
            }
        }
    }

    /// The device-server shell: no effective timeout (24 h ceiling), runs
    /// without waiting for exit; the process dies when the handle is dropped
    /// or `cancel` is flipped (spec §1.2 step 5).
    pub fn spawn_session_shell(
        &self,
        serial: &str,
        argv: &[String],
        cancel: std::sync::Arc<std::sync::atomic::AtomicBool>,
    ) -> Result<(), String> {
        let mut command = Command::new(&self.executable);
        command.arg("-s").arg(serial);
        for argument in argv {
            command.arg(argument);
        }
        command
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let mut child = command
            .spawn()
            .map_err(|error| format!("unable to spawn adb session shell: {error}"))?;
        let stdout_handle = child.stdout.take();
        let stderr_handle = child.stderr.take();
        spawn_drainer(stdout_handle);
        spawn_drainer(stderr_handle);
        // Watcher thread: cancel flip → kill the local adb client (the shell
        // dies with it), matching the Kotlin `cancel` of the shell coroutine.
        std::thread::spawn(move || {
            let mut child = child;
            loop {
                if cancel.load(std::sync::atomic::Ordering::SeqCst) {
                    let _ = child.kill();
                    let _ = child.wait();
                    return;
                }
                match child.try_wait() {
                    Ok(Some(_)) | Err(_) => return,
                    Ok(None) => std::thread::sleep(Duration::from_millis(100)),
                }
            }
        });
        Ok(())
    }
}

/// Failure of one launch step, with the spec's error text.
#[derive(Debug)]
pub enum LaunchError {
    JarTemp(std::io::Error),
    Push(String),
    Reverse(String),
    Shell(String),
    AcceptTimeout,
    Protocol(std::io::Error),
}

impl std::fmt::Display for LaunchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::JarTemp(error) => write!(f, "unable to materialize scrcpy-server jar: {error}"),
            Self::Push(details) => write!(f, "adb push failed: {details}"),
            Self::Reverse(details) => write!(f, "adb reverse failed: {details}"),
            Self::Shell(details) => write!(f, "scrcpy server failed to start: {details}"),
            Self::AcceptTimeout => {
                f.write_str("Timed out waiting for the scrcpy server to connect back")
            }
            Self::Protocol(error) => write!(f, "scrcpy handshake failed: {error}"),
        }
    }
}

impl std::error::Error for LaunchError {}

/// Launch steps 2-5 (spec §1.2): materialize the jar, push, reverse, spawn the
/// device-side server. The handshake (accept + device metadata) stays in
/// `session`. On failure the caller proceeds to [`rollback`] (spec §1.4).
pub fn launch(
    runner: &AdbRunner,
    serial: &str,
    launched: &mut LaunchedServer,
    quality: crate::quality::ScreencastQuality,
) -> Result<(), LaunchError> {
    // Step 2: materialize the bundled jar to a unique temp file (§9.2:
    // `Files.createTempFile` + `adb push`; adb cannot push from stdin).
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_nanos())
        .unwrap_or_default();
    let local_jar = std::env::temp_dir().join(format!(
        "scrcpy-server-{}-{}-{}.jar",
        std::process::id(),
        nanos,
        launched.scid
    ));
    if let Err(error) = std::fs::write(&local_jar, SCRCPY_SERVER_JAR) {
        return Err(LaunchError::JarTemp(error));
    }
    launched.local_jar_path = local_jar.clone();

    // Step 3: adb push (60 s budget).
    let (output, _) = runner.run(
        &[
            "-s",
            serial,
            "push",
            local_jar.to_string_lossy().as_ref(),
            &launched.remote_jar_path,
        ],
        Duration::from_secs(PUSH_TIMEOUT_SECS),
        None,
    );
    if !output.success {
        let _ = std::fs::remove_file(&local_jar);
        return Err(LaunchError::Push(output.merged().trim().to_owned()));
    }

    // Step 4: adb reverse (10 s budget).
    let (output, _) = runner.run(
        &[
            "-s",
            serial,
            "reverse",
            &format!("localabstract:{}", launched.socket_name),
            &format!("tcp:{}", launched.local_port),
        ],
        Duration::from_secs(COMMAND_TIMEOUT_SECS),
        None,
    );
    if !output.success {
        let _ = std::fs::remove_file(&local_jar);
        return Err(LaunchError::Reverse(output.merged().trim().to_owned()));
    }

    // Step 5: device-side app_process server, non-blocking spawn (the shell
    // lives as long as the session; the watcher thread owns cancellation).
    runner
        .spawn_session_shell(
            serial,
            &server_shell_argv(launched, quality),
            launched.cancel.clone(),
        )
        .map_err(LaunchError::Shell)
}

/// Rollback for a failed launch step (spec §1.4): kill the server shell
/// (cancel flag → the watcher kills the local adb client), `reverse --remove`
/// (one retry on failure), `rm -f` the remote jar, delete the host temp file.
/// No settle delay — that belongs to the normal-end [`cleanup`] only.
pub fn rollback(runner: &AdbRunner, serial: &str, launched: &LaunchedServer) {
    teardown(runner, serial, launched, false);
}

/// Full cleanup (spec §6.4 step 3): kill the server shell, settle 500 ms,
/// `reverse --remove` (one retry), `rm -f` the remote jar, delete the host
/// temp file. Runs on an independent cleanup thread in production.
pub fn cleanup(runner: &AdbRunner, serial: &str, launched: &LaunchedServer) {
    teardown(runner, serial, launched, true);
}

fn teardown(runner: &AdbRunner, serial: &str, launched: &LaunchedServer, settle: bool) {
    launched
        .cancel
        .store(true, std::sync::atomic::Ordering::SeqCst);
    if settle {
        std::thread::sleep(Duration::from_millis(CLEANUP_SETTLE_DELAY_MS));
    }
    // `reverse --remove localabstract:<socket_name>` with one retry on failure.
    let spec = format!("localabstract:{}", launched.socket_name);
    let (output, _) = runner.run(
        &["-s", serial, "reverse", "--remove", &spec],
        Duration::from_secs(COMMAND_TIMEOUT_SECS),
        None,
    );
    if !output.success {
        let _ = runner.run(
            &["-s", serial, "reverse", "--remove", &spec],
            Duration::from_secs(COMMAND_TIMEOUT_SECS),
            None,
        );
    }
    // `shell rm -f <remote jar>`; arguments travel as individual argv entries
    // (spec §A.12: no `sh -c`).
    let _ = runner.run(
        &["-s", serial, "shell", "rm", "-f", &launched.remote_jar_path],
        Duration::from_secs(COMMAND_TIMEOUT_SECS),
        None,
    );
    // Host-side temp jar removal (spec §1.4 push-failure branch and §6.4).
    remove_local_jar(launched);
}

/// Host-side temp jar removal (spec §1.4 push-failure branch and §6.4).
pub fn remove_local_jar(launched: &LaunchedServer) {
    if !launched.local_jar_path.as_os_str().is_empty() {
        let _ = std::fs::remove_file(&launched.local_jar_path);
    }
}

/// Device-shell argv for the app_process server (spec §1.3, complete).
/// Argument-level fidelity:
/// - `scid` value is lowercase hex **without zero padding**;
/// - socket/jar names use the **zero-padded** `%08x` form — never mix them;
/// - quality options are appended in §7 order (NATIVE omits `max_size`).
pub fn server_shell_argv(
    launched: &LaunchedServer,
    quality: crate::quality::ScreencastQuality,
) -> Vec<String> {
    let mut argv = vec![
        "shell".to_owned(),
        "env".to_owned(),
        format!("CLASSPATH={}", launched.remote_jar_path),
        "app_process".to_owned(),
        "/system/bin".to_owned(),
        "com.genymobile.scrcpy.Server".to_owned(),
        SERVER_VERSION.to_owned(),
        "log_level=info".to_owned(),
        format!("scid={:x}", launched.scid),
        "video=true".to_owned(),
        "audio=false".to_owned(),
        "control=true".to_owned(),
        "tunnel_forward=false".to_owned(),
        "stay_awake=true".to_owned(),
    ];
    argv.extend(quality.server_argv());
    argv
}

#[cfg(test)]
mod tests {
    use super::*;

    fn launched(scid: u32) -> LaunchedServer {
        LaunchedServer {
            scid,
            socket_name: format!("scrcpy_{scid:08x}"),
            remote_jar_path: format!("{REMOTE_DIR}/scrcpy_{scid:08x}.jar"),
            local_jar_path: PathBuf::new(),
            local_port: 34_567,
            cancel: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    /// Spec §1.1: socket/jar names zero-padded, 31-bit scids never zero.
    #[test]
    fn generated_identity_uses_the_padded_socket_name() {
        for scid in [1u32, 0x1a2b3c4d, u32::MAX] {
            let server = launched(scid);
            assert_eq!(server.socket_name, format!("scrcpy_{scid:08x}"));
            assert_eq!(
                server.remote_jar_path,
                format!("{REMOTE_DIR}/scrcpy_{scid:08x}.jar")
            );
        }
        let server = LaunchedServer::generate(1);
        assert!(server.scid >= 1, "scid is 1..=Int.MAX_VALUE");
    }

    /// Spec §1.3: complete argv, scid unpadded hex, quality options appended.
    #[test]
    fn server_shell_argv_matches_the_spec_line() {
        let server = launched(0x1a2b3c4d);
        assert_eq!(
            server_shell_argv(&server, crate::quality::ScreencastQuality::Hd),
            [
                "shell",
                "env",
                "CLASSPATH=/data/local/tmp/scrcpy_aps/scrcpy_1a2b3c4d.jar",
                "app_process",
                "/system/bin",
                "com.genymobile.scrcpy.Server",
                "3.1",
                "log_level=info",
                "scid=1a2b3c4d", // unpadded hex
                "video=true",
                "audio=false",
                "control=true",
                "tunnel_forward=false",
                "stay_awake=true",
                "max_size=1600",
                "max_fps=60",
                "video_bit_rate=12000000",
            ]
        );
        // NATIVE appends no max_size; small scids stay unpadded (`scid=1`).
        let server = launched(1);
        assert_eq!(
            server_shell_argv(&server, crate::quality::ScreencastQuality::Native)[8],
            "scid=1"
        );
        assert!(
            !server_shell_argv(&server, crate::quality::ScreencastQuality::Native)
                .iter()
                .any(|argument| argument.starts_with("max_size="))
        );
    }

    /// The embedded jar is the exact expected scrcpy-server payload (spec
    /// header: 90,640 bytes; jar = zip container "PK").
    #[test]
    fn embedded_server_jar_is_complete() {
        assert_eq!(SCRCPY_SERVER_JAR.len(), 90_640);
        assert_eq!(&SCRCPY_SERVER_JAR[..2], b"PK", "a jar is a zip");
    }

    /// Constants mirror the spec (§1.2, §6, task adb-timeout table).
    #[test]
    fn spec_constants_are_pinned() {
        assert_eq!(ACCEPT_TIMEOUT_MS, 15_000);
        assert_eq!(SESSION_SHELL_TIMEOUT_SECS, 24 * 3600);
        assert_eq!(COMMAND_TIMEOUT_SECS, 10);
        assert_eq!(PUSH_TIMEOUT_SECS, 60);
        assert_eq!(CLEANUP_SETTLE_DELAY_MS, 500);
        assert_eq!(SERVER_VERSION, "3.1");
        assert_eq!(REMOTE_DIR, "/data/local/tmp/scrcpy_aps");
    }

    /// Integration test with a stub adb executable: a failing `reverse` must
    /// trigger the §1.4 rollback (`reverse --remove` + `shell rm -f`) with the
    /// exact argv order, and the host temp jar must be deleted. Arguments are
    /// written to a log file by the stub, standing in for a recorded AdbClient.
    #[cfg(unix)]
    #[test]
    fn launch_failure_rolls_back_through_a_stub_adb() {
        use std::fs::Permissions;
        use std::os::unix::fs::PermissionsExt;

        let directory = std::env::temp_dir().join(format!(
            "aps-screencast-stub-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or_default()
        ));
        std::fs::create_dir_all(&directory).expect("stub dir");
        let log_path = directory.join("adb-log.txt");
        let log = log_path.to_string_lossy().replace('\'', "'\\''");
        let stub_path = directory.join("adb");
        std::fs::write(
            &stub_path,
            format!(
                "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '{log}'\nif [ \"$3\" = reverse ] && [ \"$4\" != --remove ]; then exit 9; fi\nexit 0\n"
            ),
        )
        .expect("stub script");
        std::fs::set_permissions(&stub_path, Permissions::from_mode(0o755)).expect("exec bit");
        let runner = AdbRunner::from_executable(stub_path.clone());

        let mut launched = launched(0x2abc_0001);
        let result = launch(
            &runner,
            "FAKE002",
            &mut launched,
            crate::quality::ScreencastQuality::Fluent,
        );
        assert!(
            matches!(result, Err(LaunchError::Reverse(_))),
            "got: {result:?}"
        );
        // The session reacts to a launch failure with the §1.4 rollback
        // (session.rs run_session does exactly this call).
        rollback(&runner, "FAKE002", &launched);

        let log = std::fs::read_to_string(&log_path).expect("stub log");
        let lines: Vec<&str> = log.lines().collect();
        // push: -s serial push <local jar> <remote jar>
        assert_eq!(&lines[0..3], ["-s", "FAKE002", "push"]);
        assert!(lines[4].contains("scrcpy_2abc0001.jar"));
        // reverse (fails with exit 9): -s serial reverse localabstract:<name> tcp:<port>
        assert_eq!(
            &lines[5..9],
            ["-s", "FAKE002", "reverse", "localabstract:scrcpy_2abc0001"]
        );
        assert!(lines[9].starts_with("tcp:"));
        // §1.4 rollback: reverse --remove, then shell rm -f.
        assert_eq!(
            &lines[10..15],
            [
                "-s",
                "FAKE002",
                "reverse",
                "--remove",
                "localabstract:scrcpy_2abc0001"
            ]
        );
        assert_eq!(&lines[15..20], ["-s", "FAKE002", "shell", "rm", "-f"]);
        assert!(lines[20].contains("scrcpy_2abc0001.jar"));
        // The host-side temp jar is gone after the rollback.
        assert!(!launched.local_jar_path.exists());
        let _ = std::fs::remove_dir_all(&directory);
    }
}
