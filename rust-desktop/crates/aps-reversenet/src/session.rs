//! Session orchestration: adb operations, vendored APK extraction and the
//! SessionController state machine.
//!
//! Ported from `session/{ReverseNetAdbOperations,SessionController,
//! ReverseNetSession,ReverseNetPaths}.kt` (spec §5, §6). Startup order:
//! install-if-needed → adb reverse → start relay → start device client; stop
//! runs the reverse order (device client, tunnel removal, relay stop). Any TCP
//! client connecting to the relay is the VPN-authorization signal (client id
//! handshake start), with a 15 s Starting→AwaitingVpnAuthorization timeout.
//!
//! Threading model: adb calls are blocking, so `start()` runs them on the
//! caller's thread (the Kotlin controller ran them on Dispatchers.IO); relay
//! tunnel callbacks arrive on the relay thread and are forwarded through a
//! channel to a per-session actor thread that owns the state transitions —
//! this replaces the Kotlin coroutine jobs without holding locks across joins.
//! Every thread (relay loop, session actor, leak auditor, private-DNS probe)
//! is joinable and joined on stop (spec §6.3: guaranteed release).
//!
//! Wiring fixes over the dormant Kotlin fork (spec §0): the isolation mode
//! reaches `DnsInterceptor(mode) → Router → UDPConnection`, the leak auditor
//! starts when the session enters Sharing, and Private DNS is checked once at
//! session start and once at stop.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use crate::conn::ConnectionId;
use crate::dns::{DnsFilterMode, DnsInterceptor};
use crate::events::{ReverseNetEvent, ReverseNetEventBus};
use crate::leak::{AuditorRunning, DeviceProbe, LeakAuditor, PrivateDnsDetector};
use crate::relay::{MioRelayEngine, Relay, DEFAULT_PORT};
use crate::tunnel::RelayListener;

/// DefaultReverseNetAdbOperations constants (spec §5 table, Kotlin companion).
pub const CLIENT_PACKAGE: &str = "com.genymobile.gnirehtet";
pub const LOCAL_ABSTRACT: &str = "localabstract:gnirehtet";
/// Host-side spec used by `adb reverse` (device-side name stays plain).
pub const LOCAL_ABSTRACT_HOST_SPEC: &str = "localabstract:gnirehtet";
pub const RELAY_PORT: u16 = DEFAULT_PORT;
pub const START_ACTION: &str = "com.genymobile.gnirehtet.START";
pub const STOP_ACTION: &str = "com.genymobile.gnirehtet.STOP";
/// ADB_TIMEOUT = 10 s; INSTALL_TIMEOUT = 120 s (ReverseNetAdbOperations.kt).
pub const ADB_TIMEOUT: Duration = Duration::from_secs(10);
pub const INSTALL_TIMEOUT: Duration = Duration::from_secs(120);
/// gnirehtet 2.5.1 vendored APK size (ReverseNetPaths.RESOURCE_SIZE, spec §9).
pub const APK_RESOURCE_SIZE: usize = 23_904;
/// ReverseNetSessionConfig.DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS.
pub const DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS: u64 = 15_000;

fn take200(text: &str) -> String {
    text.chars().take(200).collect()
}

/// Outcome of one adb invocation.
#[derive(Debug, Clone, Default)]
pub struct AdbOutput {
    pub stdout: String,
    pub stderr: String,
    /// Local adb exit status. `adb shell` does not reliably propagate remote
    /// exit codes, so command-level checks pair this with stdout content.
    pub success: bool,
    /// True when the deadline fired and the child was killed.
    pub timed_out: bool,
}

/// Lightweight blocking adb runner bound to one executable (~80 lines, self
/// contained on purpose: unlike the Winscope transport this crate only needs
/// locate + bounded command execution, no streaming or cancellation).
pub struct AdbRunner {
    executable: PathBuf,
}

impl AdbRunner {
    /// Resolves adb through the shared locator (configured SDK, environment, PATH).
    pub fn locate(explicit: Option<&Path>) -> Result<Self, String> {
        let location = aps_adb::AdbLocator::new()
            .locate(explicit)
            .map_err(|error| error.to_string())?;
        Ok(Self { executable: location.executable })
    }

    pub fn executable(&self) -> &Path {
        &self.executable
    }

    /// Runs a bounded adb command, capturing both streams; kills the child when
    /// the deadline fires (mirrors DefaultAdbCommandExecutor timeouts).
    pub fn run(&self, arguments: &[&str], timeout: Duration) -> AdbOutput {
        let spawn = Command::new(&self.executable)
            .args(arguments)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .stdin(Stdio::null())
            .spawn();
        let mut child = match spawn {
            Ok(child) => child,
            Err(error) => {
                return AdbOutput { stderr: format!("Unable to spawn adb: {error}"), ..Default::default() };
            }
        };
        // Drain both pipes on helper threads so a chatty adb never deadlocks.
        let stdout_handle = child.stdout.take();
        let stderr_handle = child.stderr.take();
        let stdout_sink = std::thread::spawn(move || drain_to_string(stdout_handle));
        let stderr_sink = std::thread::spawn(move || drain_to_string(stderr_handle));
        let started = Instant::now();
        let mut timed_out = false;
        let mut final_status: Option<std::process::ExitStatus> = None;
        let mut wait_error: Option<std::io::Error> = None;
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    final_status = Some(status);
                    break;
                }
                Ok(None) => {
                    if started.elapsed() >= timeout {
                        let _ = child.kill();
                        let _ = child.wait();
                        timed_out = true;
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(15));
                }
                Err(error) => {
                    wait_error = Some(error);
                    break;
                }
            }
        }
        let stdout = stdout_sink.join().unwrap_or_default();
        let stderr = stderr_sink.join().unwrap_or_default();
        if let Some(error) = wait_error {
            return AdbOutput {
                stdout,
                stderr: format!("{stderr}\nadb wait failed: {error}"),
                success: false,
                timed_out,
            };
        }
        AdbOutput {
            stdout,
            stderr,
            success: final_status.map(|status| status.success()).unwrap_or(false),
            timed_out,
        }
    }
}

fn drain_to_string<R: Read>(handle: Option<R>) -> String {
    let Some(mut handle) = handle else { return String::new() };
    let mut sink = Vec::new();
    let _ = handle.read_to_end(&mut sink);
    String::from_utf8_lossy(&sink).into_owned()
}

/// adb operations required by the session orchestration, isolated for
/// testability (ReverseNetAdbOperations.kt). APK installation goes through a
/// raw adb command because AdbClient does not expose install.
pub trait ReverseNetAdbOperations: Send + Sync {
    /// True when the gnirehtet package is installed on the device.
    fn is_client_installed(&self, serial: &str) -> bool;
    fn install_client(&self, serial: &str, apk_path: &Path) -> Result<(), String>;
    fn add_reverse_tunnel(&self, serial: &str) -> Result<(), String>;
    /// Any failure is swallowed: the tunnel may already be gone (§5 #4).
    fn remove_reverse_tunnel(&self, serial: &str);
    /// Returns current `adb reverse --list` entries for the device.
    fn list_reverse_tunnels(&self, serial: &str) -> Vec<String>;
    /// Output is not validated: VPN authorization happens via the device dialog.
    fn start_client_on_device(&self, serial: &str);
    /// Device may already be gone; cleanup continues (§5 #7).
    fn stop_client_on_device(&self, serial: &str);
}

/// Default implementation over the raw adb runner (spec §5 table, verbatim).
pub struct DefaultReverseNetAdbOperations {
    adb: AdbRunner,
}

impl DefaultReverseNetAdbOperations {
    pub fn locate() -> Result<Self, String> {
        AdbRunner::locate(None).map(|adb| Self { adb })
    }

    pub fn with_runner(adb: AdbRunner) -> Self {
        Self { adb }
    }
}

impl ReverseNetAdbOperations for DefaultReverseNetAdbOperations {
    fn is_client_installed(&self, serial: &str) -> bool {
        let output = self.adb.run(
            &["-s", serial, "shell", "pm", "path", CLIENT_PACKAGE],
            ADB_TIMEOUT,
        );
        !output.stdout.trim().is_empty()
    }

    fn install_client(&self, serial: &str, apk_path: &Path) -> Result<(), String> {
        // `-r` reinstall keeping data, `-g` grant all runtime permissions once.
        let apk = apk_path.to_string_lossy().into_owned();
        let output = self.adb.run(
            &["-s", serial, "install", "-r", "-g", apk.as_str()],
            INSTALL_TIMEOUT,
        );
        if output.stdout.contains("Success") {
            Ok(())
        } else {
            Err(format!("gnirehtet.apk installation failed: {}", take200(&output.stdout)))
        }
    }

    fn add_reverse_tunnel(&self, serial: &str) -> Result<(), String> {
        // adb reverse (device connects to host) is not exposed by AdbClient,
        // which only supports forward; run it as a raw adb command.
        let port_spec = format!("tcp:{RELAY_PORT}");
        let output = self.adb.run(
            &["-s", serial, "reverse", LOCAL_ABSTRACT_HOST_SPEC, port_spec.as_str()],
            ADB_TIMEOUT,
        );
        if output.success {
            Ok(())
        } else {
            Err(format!("adb reverse failed: {}", take200(&output.stdout)))
        }
    }

    fn remove_reverse_tunnel(&self, serial: &str) {
        let _ = self.adb.run(
            &["-s", serial, "reverse", "--remove", LOCAL_ABSTRACT_HOST_SPEC],
            ADB_TIMEOUT,
        );
    }

    fn list_reverse_tunnels(&self, serial: &str) -> Vec<String> {
        let output = self.adb.run(&["-s", serial, "reverse", "--list"], ADB_TIMEOUT);
        if output.timed_out || output.stdout.is_empty() {
            return Vec::new();
        }
        output.stdout.lines().filter(|line| !line.trim().is_empty()).map(str::to_owned).collect()
    }

    fn start_client_on_device(&self, serial: &str) {
        let activity = format!("{CLIENT_PACKAGE}/.GnirehtetActivity");
        let _ = self.adb.run(
            &["-s", serial, "shell", "am", "start", "-a", START_ACTION, "-n", activity.as_str()],
            ADB_TIMEOUT,
        );
    }

    fn stop_client_on_device(&self, serial: &str) {
        let activity = format!("{CLIENT_PACKAGE}/.GnirehtetActivity");
        let _ = self.adb.run(
            &["-s", serial, "shell", "am", "start", "-a", STOP_ACTION, "-n", activity.as_str()],
            ADB_TIMEOUT,
        );
    }
}

/// Production DeviceProbe over the raw adb runner (spec §4.1: trimmed stdout,
/// `None` on failure — spawn failure or timeout; remote exit codes are ignored
/// because probes like ping exit non-zero on 100% loss by design).
pub struct AdbDeviceProbe {
    adb: AdbRunner,
}

impl AdbDeviceProbe {
    pub fn locate() -> Result<Self, String> {
        AdbRunner::locate(None).map(|adb| Self { adb })
    }
}

impl DeviceProbe for AdbDeviceProbe {
    fn shell(&self, serial: &str, command: &[&str]) -> Option<String> {
        let mut arguments: Vec<&str> = vec!["-s", serial, "shell"];
        arguments.extend_from_slice(command);
        let output = self.adb.run(&arguments, ADB_TIMEOUT);
        if output.timed_out {
            return None;
        }
        Some(output.stdout.trim().to_owned())
    }
}

/// Locates the vendored gnirehtet.apk for the reverse-net module
/// (ReverseNetPaths.vendoredApk, installed-package mode): extracts the embedded
/// resource to `<data_root>/reverse-net/gnirehtet.apk`, validating the 23904-byte
/// 2.5.1 size before writing; an already-extracted copy of the right size short
/// -circuits. The Kotlin dev-mode third_party probe was dropped — the asset is
/// always embedded in this crate, so there is a single source of truth.
pub fn vendored_apk(data_root: &Path) -> Option<PathBuf> {
    let directory = data_root.join("reverse-net");
    let target = directory.join("gnirehtet.apk");
    if let Ok(metadata) = std::fs::metadata(&target) {
        if metadata.is_file() && metadata.len() == APK_RESOURCE_SIZE as u64 {
            return Some(target);
        }
    }
    if crate::EMBEDDED_APK.len() != APK_RESOURCE_SIZE {
        return None;
    }
    std::fs::create_dir_all(&directory).ok()?;
    std::fs::write(&target, crate::EMBEDDED_APK).ok()?;
    Some(target)
}

/// Default data root: `~/.android-performance-studio` (ReverseNetPaths.cacheDir).
pub fn default_data_root() -> Option<PathBuf> {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)?;
    if !home.is_absolute() {
        return None;
    }
    Some(home.join(".android-performance-studio"))
}

/// Lifecycle state of a reverse-tethering session (ReverseNetSessionState).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionState {
    Idle,
    Starting,
    /// Waiting for the user to confirm the VPN dialog on the device.
    AwaitingVpnAuthorization,
    /// Tunnel established; device traffic is flowing through the relay.
    Sharing,
    Stopping,
    Error(String),
}

/// Configuration for one reverse-tethering session (ReverseNetSessionConfig).
/// The Kotlin port declared `vpnAuthorizationTimeoutMs` but never read it; this
/// port honors the field so tests can shorten the wait.
#[derive(Debug, Clone)]
pub struct SessionConfig {
    pub isolation_mode: DnsFilterMode,
    pub vpn_authorization_timeout_ms: u64,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self { isolation_mode: DnsFilterMode::Relaxed, vpn_authorization_timeout_ms: DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS }
    }
}

/// Relay engine factory (SessionController.relayFactory): the controller passes
/// the relay port; the listener and DNS interceptor are handed over at `start`.
pub type RelayFactory = Box<dyn Fn(u16) -> Box<dyn Relay> + Send + Sync>;

/// Commands forwarded from the relay listener / controller into the session
/// actor thread (replaces the Kotlin coroutine jobs + StateFlow updates).
#[derive(Debug)]
enum Ctrl {
    TunnelConnected,
    TunnelDisconnected,
    SessionStarted,
    Stop,
}

struct ControllerShared {
    state: Mutex<SessionState>,
    bus: Arc<ReverseNetEventBus>,
    active_serial: Mutex<Option<String>>,
}

/// Orchestrates the reverse-tethering session for a single device (spec §6).
pub struct SessionController {
    shared: Arc<ControllerShared>,
    adb: Arc<dyn ReverseNetAdbOperations>,
    vendored_apk: PathBuf,
    relay_port: u16,
    vpn_authorization_timeout_ms: u64,
    relay_factory: RelayFactory,
    probe: Option<Arc<dyn DeviceProbe>>,
    relay: Mutex<Option<Box<dyn Relay>>>,
    tunnel_tx: Mutex<Option<mpsc::Sender<Ctrl>>>,
    actor: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl SessionController {
    pub fn new(
        adb: Arc<dyn ReverseNetAdbOperations>,
        vendored_apk: PathBuf,
        bus: Arc<ReverseNetEventBus>,
    ) -> Self {
        Self {
            shared: Arc::new(ControllerShared {
                state: Mutex::new(SessionState::Idle),
                bus,
                active_serial: Mutex::new(None),
            }),
            adb,
            vendored_apk,
            relay_port: RELAY_PORT,
            vpn_authorization_timeout_ms: DEFAULT_VPN_AUTHORIZATION_TIMEOUT_MS,
            relay_factory: Box::new(|port| Box::new(MioRelayEngine::new(port))),
            probe: None,
            relay: Mutex::new(None),
            tunnel_tx: Mutex::new(None),
            actor: Mutex::new(None),
        }
    }

    /// Production assembly: locate adb, wire the default adb operations, the
    /// device probe and the vendored APK under the default data root.
    pub fn with_defaults(bus: Arc<ReverseNetEventBus>) -> Result<Self, String> {
        let adb: Arc<dyn ReverseNetAdbOperations> =
            Arc::new(DefaultReverseNetAdbOperations::locate()?);
        let probe: Option<Arc<dyn DeviceProbe>> =
            AdbDeviceProbe::locate().ok().map(|p| Arc::new(p) as Arc<dyn DeviceProbe>);
        let data_root = default_data_root()
            .ok_or_else(|| "home directory not found for the vendored gnirehtet.apk".to_owned())?;
        let apk = vendored_apk(&data_root)
            .ok_or_else(|| "Vendored gnirehtet.apk could not be extracted.".to_owned())?;
        Ok(Self::new(adb, apk, bus).with_probe(probe))
    }

    pub fn with_relay_port(mut self, port: u16) -> Self {
        self.relay_port = port;
        self
    }

    pub fn with_relay_factory(mut self, factory: RelayFactory) -> Self {
        self.relay_factory = factory;
        self
    }

    pub fn with_vpn_authorization_timeout(mut self, timeout_ms: u64) -> Self {
        self.vpn_authorization_timeout_ms = timeout_ms;
        self
    }

    pub fn with_probe(mut self, probe: Option<Arc<dyn DeviceProbe>>) -> Self {
        self.probe = probe;
        self
    }

    /// StateFlow value equivalent (UI polls this snapshot).
    pub fn state(&self) -> SessionState {
        self.shared.state.lock().unwrap().clone()
    }

    pub fn is_session_active(&self) -> bool {
        self.shared.active_serial.lock().unwrap().is_some()
    }

    pub fn dropped_events(&self) -> u64 {
        self.shared.bus.dropped_events()
    }

    /// Snapshot query for the UI: newest `limit` events, oldest first (spec §6:
    /// `eventsSnapshot(limit)`; the Kotlin UI kept at most 500).
    pub fn events_snapshot(&self, limit: usize) -> Vec<ReverseNetEvent> {
        self.shared.bus.events_snapshot(limit)
    }

    /// Attempt to detect leftover state from a crashed session and clean it up
    /// (SessionController.recoverLeftovers; called on APS startup).
    pub fn recover_leftovers(&self, serial: &str) -> bool {
        let tunnels = self.adb.list_reverse_tunnels(serial);
        if !tunnels.iter().any(|tunnel| tunnel.contains("localabstract:gnirehtet")) {
            return false;
        }
        self.adb.stop_client_on_device(serial);
        self.adb.remove_reverse_tunnel(serial);
        true
    }

    /// Full startup sequence (SessionController.start, spec §6.2). Blocking adb
    /// work runs on the caller's thread; a failure anywhere runs
    /// [`Self::stop_internal`] with `reportError = true` and the message lands
    /// in [`SessionState::Error`].
    pub fn start(&self, serial: &str, isolation_mode: DnsFilterMode) -> Result<(), String> {
        {
            let mut state = self.shared.state.lock().unwrap();
            if !matches!(*state, SessionState::Idle | SessionState::Error(_)) {
                return Err("A session is already active".to_owned());
            }
            *state = SessionState::Starting;
        }
        *self.shared.active_serial.lock().unwrap() = Some(serial.to_owned());
        let (tx, rx) = mpsc::channel();
        *self.tunnel_tx.lock().unwrap() = Some(tx.clone());
        *self.actor.lock().unwrap() = Some(spawn_session_actor(
            self.shared.clone(),
            rx,
            serial.to_owned(),
            self.probe.clone(),
            self.vpn_authorization_timeout_ms,
        ));
        match self.start_sequence(serial, isolation_mode, &tx) {
            Ok(()) => Ok(()),
            Err(error) => {
                self.stop_internal(true, Some(error.clone()));
                Err(error)
            }
        }
    }

    fn start_sequence(
        &self,
        serial: &str,
        isolation_mode: DnsFilterMode,
        tx: &mpsc::Sender<Ctrl>,
    ) -> Result<(), String> {
        // 2. install the vendored client when missing (§5 #1/#2)
        if !self.adb.is_client_installed(serial) {
            self.adb.install_client(serial, &self.vendored_apk)?;
        }
        // 3. adb reverse localabstract:gnirehtet tcp:31416 (§5 #3)
        self.adb.add_reverse_tunnel(serial)?;
        // 4. start the relay with the tap bridge + wired DNS interceptor
        let listener: Arc<dyn RelayListener> = Arc::new(WrapListener {
            tx: Mutex::new(tx.clone()),
            delegate: Arc::new(crate::events::TapEventBridge::new(self.shared.bus.clone())),
        });
        let dns = Arc::new(DnsInterceptor::new(self.shared.bus.clone(), isolation_mode));
        let mut relay = (self.relay_factory)(self.relay_port);
        relay.start(listener, Some(dns))?;
        *self.relay.lock().unwrap() = Some(relay);
        // 5. start the device client (VPN dialog appears on the device, §5 #6)
        self.adb.start_client_on_device(serial);
        // 6. arm the 15 s VPN authorization timeout (actor-side deadline)
        let _ = tx.send(Ctrl::SessionStarted);
        Ok(())
    }

    /// SessionController.stop → stopInternal(reportError = false).
    pub fn stop(&self) {
        self.stop_internal(false, None);
    }

    /// Non-suspend release for page disposal: best-effort full stop; no-op with
    /// no active session (SessionController.close).
    pub fn close(&self) {
        let idle = self.shared.active_serial.lock().unwrap().is_none()
            && self.relay.lock().unwrap().is_none();
        if idle {
            return;
        }
        self.stop_internal(false, None);
    }

    /// Relay-side tunnel signal (WrapListener.onClientConnected equivalent);
    /// exposed for UI/test bridges that trigger the authorization transition.
    pub fn on_tunnel_connected(&self) {
        if let Some(tx) = self.tunnel_tx.lock().unwrap().as_ref() {
            let _ = tx.send(Ctrl::TunnelConnected);
        }
    }

    /// Relay-side disconnect signal (WrapListener.onClientDisconnected).
    pub fn on_tunnel_disconnected(&self) {
        if let Some(tx) = self.tunnel_tx.lock().unwrap().as_ref() {
            let _ = tx.send(Ctrl::TunnelDisconnected);
        }
    }

    /// Shared stop sequence (SessionController.stopInternal, spec §6.3): device
    /// client first, then adb reverse removal, then the relay engine.
    fn stop_internal(&self, report_error: bool, error: Option<String>) {
        *self.shared.state.lock().unwrap() = SessionState::Stopping;
        let serial = self.shared.active_serial.lock().unwrap().take();
        if let Some(serial) = &serial {
            self.adb.stop_client_on_device(serial);
            self.adb.remove_reverse_tunnel(serial);
        }
        if let Some(mut relay) = self.relay.lock().unwrap().take() {
            relay.stop(); // joins the relay thread
        }
        let actor = {
            let tx_slot = self.tunnel_tx.lock().unwrap().take();
            if let Some(tx) = tx_slot {
                let _ = tx.send(Ctrl::Stop);
            }
            self.actor.lock().unwrap().take()
        };
        if let Some(actor) = actor {
            let _ = actor.join(); // auditor stop + final private-DNS check happen here
        }
        let mut state = self.shared.state.lock().unwrap();
        *state = match (report_error, error) {
            (true, Some(error)) => SessionState::Error(error),
            _ => SessionState::Idle,
        };
    }
}

/// Forwards relay listener events to both the tap bridge and session state
/// tracking (SessionController.WrapListener). Channel sends never block.
struct WrapListener {
    tx: Mutex<mpsc::Sender<Ctrl>>,
    delegate: Arc<dyn RelayListener>,
}

impl RelayListener for WrapListener {
    fn on_client_connected(&self, client_id: i32) {
        if let Ok(tx) = self.tx.lock() {
            let _ = tx.send(Ctrl::TunnelConnected);
        }
        self.delegate.on_client_connected(client_id);
    }

    fn on_client_disconnected(&self, client_id: i32) {
        if let Ok(tx) = self.tx.lock() {
            let _ = tx.send(Ctrl::TunnelDisconnected);
        }
        self.delegate.on_client_disconnected(client_id);
    }

    fn on_connection_opened(&self, connection: &dyn crate::conn::Connection, id: &ConnectionId) {
        self.delegate.on_connection_opened(connection, id);
    }

    fn on_connection_closed(&self, connection: &dyn crate::conn::Connection, id: &ConnectionId) {
        self.delegate.on_connection_closed(connection, id);
    }

    fn on_packet_dropped(&self, packet: &crate::packet::IPv4Packet) {
        self.delegate.on_packet_dropped(packet);
    }

    fn on_connection_bytes(&self, connection: &dyn crate::conn::Connection, id: &ConnectionId) {
        self.delegate.on_connection_bytes(connection, id);
    }
}

/// The per-session actor: owns the state machine timing (15 s authorization
/// deadline), the leak auditor lifecycle and the two Private DNS checks
/// (session start / session stop, spec §4.3 wiring).
fn spawn_session_actor(
    shared: Arc<ControllerShared>,
    rx: mpsc::Receiver<Ctrl>,
    serial: String,
    probe: Option<Arc<dyn DeviceProbe>>,
    vpn_authorization_timeout_ms: u64,
) -> std::thread::JoinHandle<()> {
    std::thread::Builder::new()
        .name("aps-reverse-net-session".to_owned())
        .spawn(move || {
            let auth_timeout = Duration::from_millis(vpn_authorization_timeout_ms);
            let mut deadline: Option<Instant> = None;
            let mut auditor: Option<AuditorRunning> = None;
            let mut start_probe: Option<std::thread::JoinHandle<()>> = None;
            let mut session_started = false;
            loop {
                let now = Instant::now();
                let timeout = match deadline {
                    Some(deadline) if deadline > now => deadline - now,
                    Some(_) => Duration::ZERO,
                    None => Duration::from_secs(3600),
                };
                match rx.recv_timeout(timeout) {
                    Ok(Ctrl::SessionStarted) => {
                        session_started = true;
                        // Private DNS check at session start (§4.3 wiring); a
                        // dedicated joinable thread keeps the actor responsive.
                        if let Some(probe) = &probe {
                            let detector = PrivateDnsDetector::new(shared.bus.clone(), probe.clone());
                            let serial = serial.clone();
                            start_probe =
                                Some(std::thread::spawn(move || { detector.check_and_alert(&serial); }));
                        }
                        deadline = Some(Instant::now() + auth_timeout);
                    }
                    Ok(Ctrl::TunnelConnected) => {
                        deadline = None; // cancel the authorization timeout
                        let mut state = shared.state.lock().unwrap();
                        if matches!(*state, SessionState::Starting | SessionState::AwaitingVpnAuthorization) {
                            *state = SessionState::Sharing;
                            drop(state);
                            // Auditor starts with the session entering Sharing
                            // (§4.2/§4.3 wiring) and runs until stop.
                            if auditor.is_none() {
                                if let Some(probe) = &probe {
                                    auditor = Some(
                                        LeakAuditor::new(shared.bus.clone(), probe.clone())
                                            .spawn_thread(&serial),
                                    );
                                }
                            }
                        }
                    }
                    Ok(Ctrl::TunnelDisconnected) => {
                        let mut state = shared.state.lock().unwrap();
                        if *state == SessionState::Sharing {
                            *state = SessionState::AwaitingVpnAuthorization;
                            drop(state);
                            // Re-arm; the fired timeout only moves Starting→
                            // Awaiting, so a re-fired deadline is a no-op here.
                            deadline = Some(Instant::now() + auth_timeout);
                        }
                    }
                    Ok(Ctrl::Stop) => break,
                    Err(mpsc::RecvTimeoutError::Timeout) => {
                        let mut state = shared.state.lock().unwrap();
                        if *state == SessionState::Starting {
                            *state = SessionState::AwaitingVpnAuthorization;
                        }
                        drop(state);
                        deadline = None; // one-shot like the Kotlin coroutine job
                    }
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
            // Teardown: stop the auditor, run the end-of-session Private DNS
            // check, then release the start probe thread.
            if let Some(auditor) = auditor.take() {
                auditor.stop_and_join();
            }
            if session_started {
                if let Some(probe) = &probe {
                    PrivateDnsDetector::new(shared.bus.clone(), probe.clone()).check_and_alert(&serial);
                }
            }
            if let Some(start_probe) = start_probe.take() {
                let _ = start_probe.join();
            }
        })
        .expect("spawn aps-reverse-net-session")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    const SERIAL: &str = "FAKE_SERIAL";

    struct FakeAdb {
        fail_reverse: bool,
        fail_install: bool,
        installed: bool,
        reverse_calls: Mutex<Vec<String>>,
        stop_client_calls: Mutex<Vec<String>>,
    }

    impl FakeAdb {
        fn new() -> Arc<Self> {
            Self::with_flags(false, false)
        }

        fn with_flags(fail_reverse: bool, fail_install: bool) -> Arc<Self> {
            Arc::new(Self {
                fail_reverse,
                fail_install,
                installed: false,
                reverse_calls: Mutex::new(Vec::new()),
                stop_client_calls: Mutex::new(Vec::new()),
            })
        }
    }

    impl ReverseNetAdbOperations for FakeAdb {
        fn is_client_installed(&self, _serial: &str) -> bool {
            self.installed
        }
        fn install_client(&self, _serial: &str, _apk: &Path) -> Result<(), String> {
            if self.fail_install {
                Err("install failed".to_owned())
            } else {
                Ok(())
            }
        }
        fn add_reverse_tunnel(&self, serial: &str) -> Result<(), String> {
            if self.fail_reverse {
                return Err("reverse failed: device offline".to_owned());
            }
            self.reverse_calls.lock().unwrap().push(serial.to_owned());
            Ok(())
        }
        fn remove_reverse_tunnel(&self, serial: &str) {
            self.reverse_calls.lock().unwrap().retain(|s| s != serial);
        }
        fn list_reverse_tunnels(&self, _serial: &str) -> Vec<String> {
            self.reverse_calls
                .lock()
                .unwrap()
                .iter()
                .map(|_| "localabstract:gnirehtet tcp:31416".to_owned())
                .collect()
        }
        fn start_client_on_device(&self, _serial: &str) {}
        fn stop_client_on_device(&self, serial: &str) {
            self.stop_client_calls.lock().unwrap().push(serial.to_owned());
        }
    }

    /// Relay engine stand-in that never binds a socket; sessions drive it
    /// through the factory (FakeRelayEngine).
    struct FakeRelay {
        running: bool,
    }

    impl Relay for FakeRelay {
        fn start(&mut self, _listener: Arc<dyn RelayListener>, _dns: Option<Arc<DnsInterceptor>>) -> Result<(), String> {
            self.running = true;
            Ok(())
        }
        fn stop(&mut self) {
            self.running = false;
        }
        fn is_running(&self) -> bool {
            self.running
        }
    }

    fn fake_factory() -> RelayFactory {
        Box::new(|_port| Box::new(FakeRelay { running: false }) as Box<dyn Relay>)
    }

    fn wait_for(controller: &SessionController, predicate: impl Fn(&SessionState) -> bool, seconds: u64) -> SessionState {
        let deadline = Instant::now() + Duration::from_secs(seconds);
        loop {
            let state = controller.state();
            if predicate(&state) || Instant::now() >= deadline {
                return state;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    fn make_controller(adb: Arc<FakeAdb>) -> SessionController {
        SessionController::new(adb, PathBuf::from("build/tmp/fake-gnirehtet.apk"), Arc::new(ReverseNetEventBus::new()))
            .with_relay_port(0)
            .with_relay_factory(fake_factory())
    }

    /// §8.5.1: happy path transitions to awaiting authorization then sharing on
    /// tunnel connect; stop returns to Idle and tears adb state down.
    #[test]
    fn happy_path_transitions_to_awaiting_then_sharing_on_tunnel_connect() {
        let adb = FakeAdb::new();
        let controller = make_controller(adb.clone());
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap();
        assert_eq!(controller.state(), SessionState::Starting);
        controller.on_tunnel_connected();
        let state = wait_for(&controller, |s| *s == SessionState::Sharing, 5);
        assert_eq!(state, SessionState::Sharing);
        controller.stop();
        assert_eq!(controller.state(), SessionState::Idle);
        assert!(adb.stop_client_calls.lock().unwrap().iter().any(|s| s == SERIAL));
        assert!(adb.reverse_calls.lock().unwrap().is_empty());
    }

    /// §8.5.2: reverse failure maps to the Error state with the message intact.
    #[test]
    fn reverse_failure_maps_to_error_state() {
        let controller = make_controller(FakeAdb::with_flags(true, false));
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap_err();
        assert_eq!(
            controller.state(),
            SessionState::Error("reverse failed: device offline".to_owned()),
            "error message passes through verbatim"
        );
        assert!(!controller.is_session_active());
    }

    /// §8.5.3: installation failure maps to the Error state.
    #[test]
    fn installation_failure_maps_to_error_state() {
        let controller = make_controller(FakeAdb::with_flags(false, true));
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap_err();
        assert!(matches!(controller.state(), SessionState::Error(_)));
    }

    /// §8.5.4: with no tunnel connection the (shortened) authorization timeout
    /// transitions Starting → AwaitingVpnAuthorization; stop returns to Idle.
    #[test]
    fn authorization_timeout_transitions_to_awaiting_vpn_authorization() {
        let adb = FakeAdb::new();
        let controller = make_controller(adb.clone()).with_vpn_authorization_timeout(300);
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap();
        let state = wait_for(&controller, |s| *s == SessionState::AwaitingVpnAuthorization, 5);
        assert_eq!(state, SessionState::AwaitingVpnAuthorization);
        controller.stop();
        assert_eq!(controller.state(), SessionState::Idle);
    }

    /// §8.5.5: leftover tunnel detection recovers state; the second call finds
    /// nothing.
    #[test]
    fn leftover_tunnel_detection_recovers_state() {
        let adb = FakeAdb::new();
        adb.add_reverse_tunnel(SERIAL).unwrap();
        let controller = make_controller(adb.clone());
        assert!(controller.recover_leftovers(SERIAL));
        assert!(adb.reverse_calls.lock().unwrap().is_empty());
        assert!(!controller.recover_leftovers(SERIAL));
    }

    /// Double start is rejected while a session is active (check(activeSerial == null)).
    #[test]
    fn double_start_is_rejected() {
        let adb = FakeAdb::new();
        let controller = make_controller(adb.clone());
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap();
        assert_eq!(
            controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap_err(),
            "A session is already active"
        );
        controller.stop();
        // After a full stop the controller accepts a fresh session.
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap();
        controller.stop();
    }

    /// §8.6: vendored apk extraction validates the embedded 23904-byte resource.
    #[test]
    fn vendored_apk_extracts_to_data_root() {
        assert_eq!(crate::EMBEDDED_APK.len(), APK_RESOURCE_SIZE, "vendored gnirehtet.apk 2.5.1 is 23904 bytes");
        let data_root = std::env::temp_dir().join(format!("aps-reversenet-test-{}", std::process::id()));
        let apk = vendored_apk(&data_root).expect("extraction succeeds");
        assert_eq!(apk, data_root.join("reverse-net").join("gnirehtet.apk"));
        assert_eq!(std::fs::metadata(&apk).unwrap().len() as usize, APK_RESOURCE_SIZE);
        // Idempotent: the cached copy of the right size short-circuits.
        assert_eq!(vendored_apk(&data_root), Some(apk.clone()));
        let _ = std::fs::remove_dir_all(&data_root);
    }

    /// A relay factory failure maps to the Error state and tears the tunnel down.
    #[test]
    fn relay_start_failure_maps_to_error_state() {
        struct FailingRelay;
        impl Relay for FailingRelay {
            fn start(&mut self, _: Arc<dyn RelayListener>, _: Option<Arc<DnsInterceptor>>) -> Result<(), String> {
                Err("relay bind failed: address taken".to_owned())
            }
            fn stop(&mut self) {}
            fn is_running(&self) -> bool {
                false
            }
        }
        let adb = FakeAdb::new();
        let controller = SessionController::new(
            adb,
            PathBuf::from("build/tmp/fake-gnirehtet.apk"),
            Arc::new(ReverseNetEventBus::new()),
        )
        .with_relay_factory(Box::new(|_port| Box::new(FailingRelay) as Box<dyn Relay>));
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap_err();
        assert_eq!(
            controller.state(),
            SessionState::Error("relay bind failed: address taken".to_owned())
        );
    }

    /// Compile-time companion: token counters start where the allocator says.
    #[test]
    fn relay_factory_is_only_built_once_per_session() {
        static BUILDS: AtomicUsize = AtomicUsize::new(0);
        let adb = FakeAdb::new();
        let controller = SessionController::new(
            adb,
            PathBuf::from("build/tmp/fake-gnirehtet.apk"),
            Arc::new(ReverseNetEventBus::new()),
        )
        .with_relay_factory(Box::new(|_port| {
            BUILDS.fetch_add(1, Ordering::Relaxed);
            Box::new(FakeRelay { running: false }) as Box<dyn Relay>
        }));
        controller.start(SERIAL, DnsFilterMode::Relaxed).unwrap();
        controller.stop();
        assert_eq!(BUILDS.load(Ordering::Relaxed), 1);
    }
}
