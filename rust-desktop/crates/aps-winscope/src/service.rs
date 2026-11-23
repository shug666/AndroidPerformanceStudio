//! Facade owning the bundled viewer + proxy service lifecycle: explicit
//! start/stop, session token generation, browser launch with `?token=`
//! injection, the keep-alive watchdog sweep and app-exit cleanup. Starting the
//! service is never a side effect of page navigation; token and both endpoints
//! die together on stop.

use crate::adb::AdbTransport;
use crate::catalog::DeviceCatalog;
use crate::engine::{ActiveCapture, CaptureEngine};
use crate::proxy::{PROXY_PORT, ProxyServer};
use crate::viewer::{VIEWER_PREFERRED_PORT, ViewerHost};
use rand::RngCore;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock, RwLock};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

/// High-level service snapshot surfaced to the Winscope page.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceSnapshot {
    pub running: bool,
    pub proxy_port: Option<u16>,
    pub viewer_port: Option<u16>,
    /// Capture state the browser currently drives, mirrored from the proxy
    /// endpoints.
    pub capture: Option<ActiveCapture>,
    pub error_message: Option<String>,
    /// Full viewer entry URL with the token injected; None while stopped.
    pub viewer_url: Option<String>,
    /// Session token shown for the viewer's paste-token retry dialog.
    pub session_token: Option<String>,
}

impl Default for ServiceSnapshot {
    fn default() -> Self {
        Self {
            running: false,
            proxy_port: None,
            viewer_port: None,
            capture: None,
            error_message: None,
            viewer_url: None,
            session_token: None,
        }
    }
}

/// Page-visible service lifecycle states.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ServiceState {
    #[default]
    Stopped,
    Starting,
    Running,
    Failed,
}

pub struct WinscopeService {
    engine: Arc<CaptureEngine>,
    catalog: Arc<DeviceCatalog>,
    proxy: Arc<ProxyServer>,
    viewer: Arc<ViewerHost>,
    state: Arc<RwLock<ServiceSnapshot>>,
    viewer_root: OnceLock<PathBuf>,
    closure_manifest: OnceLock<BTreeMap<String, String>>,
    watchdog_running: Arc<AtomicBool>,
    /// Browser opener injected for tests; defaults to the system launcher.
    browser_launcher: Box<dyn Fn(&str) + Send + Sync>,
}

impl WinscopeService {
    /// Builds the service. `viewer_resource_root` is the packaged closure
    /// directory (verified before hosting); `closure_manifest` maps
    /// closure-relative paths to expected SHA-256 and an empty manifest disables
    /// strict verification. `archive_root` enables dual-path artifact capture
    /// (design D5).
    pub fn new(
        transport: AdbTransport,
        viewer_resource_root: PathBuf,
        closure_manifest: BTreeMap<String, String>,
        archive_root: Option<PathBuf>,
    ) -> Arc<Self> {
        let engine = Arc::new(CaptureEngine::new(transport.clone(), archive_root));
        let catalog = Arc::new(DeviceCatalog::new(transport.clone()));
        let proxy = Arc::new(ProxyServer::new(
            engine.clone(),
            catalog.clone(),
            transport.clone(),
            PROXY_PORT,
        ));
        let viewer = Arc::new(ViewerHost::new(VIEWER_PREFERRED_PORT));
        let service = Arc::new(Self {
            engine,
            catalog,
            proxy,
            viewer,
            state: Arc::new(RwLock::new(ServiceSnapshot::default())),
            viewer_root: OnceLock::new(),
            closure_manifest: OnceLock::new(),
            watchdog_running: Arc::new(AtomicBool::new(false)),
            browser_launcher: Box::new(open_system_browser),
        });
        let _ = service.viewer_root.set(viewer_resource_root);
        let _ = service.closure_manifest.set(closure_manifest);
        crate::register_for_cleanup(&service);
        service
    }

    pub fn snapshot(&self) -> ServiceSnapshot {
        self.state
            .read()
            .map(|state| state.clone())
            .unwrap_or_default()
    }

    /// The browser-driven capture state, refreshed on demand so the page mirrors
    /// proxy activity without a listener channel.
    pub fn refresh_capture_state(&self) {
        if let Ok(mut state) = self.state.write() {
            state.capture = self.engine.active_capture();
        }
    }

    /// The viewer closure root configured at construction.
    pub fn viewer_root(&self) -> Option<PathBuf> {
        self.viewer_root.get().cloned()
    }

    /// Current session token while running; the Winscope page surfaces it so the
    /// viewer's paste-token retry dialog can be served manually after a
    /// stale-token 403.
    pub fn session_token(&self) -> Option<String> {
        self.snapshot().session_token
    }

    pub fn engine(&self) -> &CaptureEngine {
        &self.engine
    }

    pub fn catalog(&self) -> &DeviceCatalog {
        &self.catalog
    }

    /// Starts the proxy and viewer host. Fails with a user-actionable message
    /// when the proxy port is occupied or the closure verification fails.
    pub fn start(self: &Arc<Self>) -> Result<(), String> {
        if self.proxy.is_running() {
            return Ok(());
        }
        self.set_state(|state| *state = ServiceSnapshot::default());
        let new_token = new_token();
        let viewer_root = self
            .viewer_root
            .get()
            .cloned()
            .ok_or_else(|| "viewer resource closure not packaged".to_owned())?;
        let manifest = self.closure_manifest.get().cloned().unwrap_or_default();
        let started =
            self.viewer
                .start(&viewer_root, &manifest)
                .and_then(|()| self.proxy.start(&new_token));
        match started {
            Ok(()) => {
                let viewer_port = self.viewer.port();
                let snapshot = ServiceSnapshot {
                    running: true,
                    proxy_port: Some(PROXY_PORT),
                    viewer_port,
                    capture: None,
                    error_message: None,
                    viewer_url: viewer_port
                        .map(|port| format!("http://127.0.0.1:{port}/index.html?token={new_token}")),
                    session_token: Some(new_token),
                };
                self.set_state(|state| *state = snapshot);
                self.start_watchdog();
                Ok(())
            }
            Err(error) => {
                self.proxy.stop();
                self.viewer.stop();
                self.stop_watchdog();
                self.set_state(|state| {
                    state.running = false;
                    state.error_message = Some(error.clone());
                    state.session_token = None;
                });
                Err(error)
            }
        }
    }

    /// Stops both endpoints and invalidates the session token.
    pub fn stop(self: &Arc<Self>) {
        self.shutdown_cleanup();
        self.stop_watchdog();
        self.set_state(|state| *state = ServiceSnapshot::default());
        crate::unregister_from_cleanup(self);
    }

    /// App-exit safety net: when the application closes without an explicit
    /// stop, end any in-progress device capture (otherwise the device-side trace
    /// shells and the detached perfetto session orphan) and release both ports.
    /// Bounded so the quit path stays responsive.
    pub fn shutdown_cleanup(&self) {
        for serial in self.engine.active_capture_serials() {
            let _ = self.engine.end_capture(&serial);
        }
        self.proxy.stop();
        self.viewer.stop();
    }

    /// Opens the system browser at the viewer entry page with the session token
    /// injected.
    pub fn open_viewer(&self) -> Result<(), String> {
        let snapshot = self.snapshot();
        let token = snapshot
            .session_token
            .ok_or_else(|| "Winscope service is not running".to_owned())?;
        let port = snapshot
            .viewer_port
            .ok_or_else(|| "Winscope viewer host is not running".to_owned())?;
        (self.browser_launcher)(&format!("http://127.0.0.1:{port}/index.html?token={token}"));
        Ok(())
    }

    fn set_state(&self, update: impl FnOnce(&mut ServiceSnapshot)) {
        if let Ok(mut state) = self.state.write() {
            update(&mut state);
        }
    }

    /// The engine sweeps expired keep-alives; the watchdog invokes it once per
    /// second while the service runs so keep-alive expiry still ends captures
    /// when the viewer tab is closed.
    fn sweep(&self) {
        let _ = self.engine.sweep_expired();
    }

    fn start_watchdog(self: &Arc<Self>) {
        self.stop_watchdog();
        self.watchdog_running.store(true, Ordering::SeqCst);
        let flag = self.watchdog_running.clone();
        let weak = Arc::downgrade(self);
        let spawn = std::thread::Builder::new()
            .name("aps-winscope-watchdog".to_owned())
            .spawn(move || {
                while flag.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_secs(1));
                    let Some(service) = weak.upgrade() else {
                        break;
                    };
                    service.sweep();
                    service.refresh_capture_state();
                }
            });
        if spawn.is_err() {
            self.stop_watchdog();
        }
    }

    fn stop_watchdog(&self) {
        self.watchdog_running.store(false, Ordering::SeqCst);
    }
}

fn new_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    let mut hex = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        hex.push_str(&format!("{byte:02x}"));
    }
    hex
}

fn open_system_browser(url: &str) {
    let os = std::env::consts::OS;
    let command = match os {
        "windows" => vec!["cmd", "/c", "start", "", url],
        "macos" => vec!["open", url],
        _ => vec!["xdg-open", url],
    };
    let _ = std::process::Command::new(command[0])
        .args(&command[1..])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
}

/// The packaged viewer closure lookup, mirroring the Firefox Profiler dist
/// resolution: packaged builds read `resources/winscope-viewer` next to the
/// executable (plus the macOS Resources layouts); debug builds may pass an
/// explicit candidate from the caller.
pub fn resolve_viewer_root(explicit_candidates: &[PathBuf]) -> Option<PathBuf> {
    const INDEX_FILE: &str = "index.html";
    for candidate in explicit_candidates {
        if candidate.join(INDEX_FILE).is_file() {
            return Some(candidate.clone());
        }
    }
    let executable = std::env::current_exe().ok()?;
    let directory = executable.parent()?;
    [
        directory.join("resources").join("winscope-viewer"),
        directory.join("../Resources").join("winscope-viewer"),
        directory.join("Contents/Resources").join("winscope-viewer"),
    ]
    .into_iter()
    .find(|root| root.join(INDEX_FILE).is_file())
}

/// Loads the packaged checksum manifest (`winscope-viewer-manifest.json`,
/// generated by scripts/generate-winscope-closure-manifest.py) so the viewer
/// host verifies the closure before hosting; an absent manifest disables strict
/// verification.
pub fn load_closure_manifest(viewer_root: &Path) -> BTreeMap<String, String> {
    const MANIFEST_FILE_NAME: &str = "winscope-viewer-manifest.json";
    let mut candidates = vec![viewer_root.join(MANIFEST_FILE_NAME)];
    if let Some(parent) = viewer_root.parent() {
        candidates.push(parent.join(MANIFEST_FILE_NAME));
    }
    let Some(path) = candidates.into_iter().find(|path| path.is_file()) else {
        return BTreeMap::new();
    };
    let Ok(text) = std::fs::read_to_string(&path) else {
        return BTreeMap::new();
    };
    let Ok(root) = serde_json::from_str::<serde_json::Value>(&text) else {
        return BTreeMap::new();
    };
    let Some(files) = root.get("files").and_then(|files| files.as_object()) else {
        return BTreeMap::new();
    };
    files
        .iter()
        .filter_map(|(path, checksum)| {
            checksum
                .as_str()
                .map(|checksum| (path.clone(), checksum.to_owned()))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn transport() -> AdbTransport {
        AdbTransport::from_location(aps_adb::AdbLocation {
            executable: PathBuf::from("adb"),
            source: aps_adb::AdbLocationSource::Path,
        })
    }

    #[test]
    fn token_is_hex_and_session_scoped() {
        let first = new_token();
        let second = new_token();
        assert_eq!(first.len(), 64);
        assert!(first.chars().all(|character| character.is_ascii_hexdigit()));
        assert_ne!(first, second);
    }

    #[test]
    fn viewer_root_resolution_prefers_explicit_candidates() {
        let fixture = std::env::temp_dir().join(format!("aps-winscope-root-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&fixture);
        std::fs::create_dir_all(&fixture).expect("dirs");
        std::fs::write(fixture.join("index.html"), b"<html>").expect("index");
        assert_eq!(resolve_viewer_root(&[fixture.clone()]), Some(fixture.clone()));
        assert_eq!(resolve_viewer_root(&["/definitely/missing".into()]), None);
        let _ = std::fs::remove_dir_all(&fixture);
    }

    #[test]
    fn manifest_loader_maps_files_and_ignores_absent_manifests() {
        let fixture = std::env::temp_dir().join(format!("aps-winscope-manifest-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&fixture);
        std::fs::create_dir_all(&fixture).expect("dirs");
        assert!(load_closure_manifest(&fixture).is_empty());
        let manifest = serde_json::json!({
            "version": "4.0.8",
            "files": {
                "index.html": "abc123",
                "js/app.js": "def456"
            }
        });
        std::fs::write(
            fixture.parent().unwrap().join("winscope-viewer-manifest.json"),
            manifest.to_string(),
        )
        .expect("manifest beside root");
        let loaded = load_closure_manifest(&fixture);
        assert_eq!(loaded.get("index.html").map(String::as_str), Some("abc123"));
        assert_eq!(loaded.get("js/app.js").map(String::as_str), Some("def456"));
        let _ = std::fs::remove_file(fixture.parent().unwrap().join("winscope-viewer-manifest.json"));
        let _ = std::fs::remove_dir_all(&fixture);
    }

    #[test]
    fn service_snapshot_defaults_to_stopped() {
        let service = WinscopeService::new(transport(), PathBuf::from("/missing"), Default::default(), None);
        let snapshot = service.snapshot();
        assert!(!snapshot.running);
        assert_eq!(snapshot.viewer_url, None);
        assert_eq!(snapshot.session_token, None);
        assert_eq!(snapshot.capture, None);
    }

    #[test]
    fn failed_start_surfaces_a_message_and_stays_stopped() {
        let service = WinscopeService::new(
            transport(),
            std::env::temp_dir().join("aps-winscope-never-an-index"),
            Default::default(),
            None,
        );
        let error = service.start().expect_err("closure missing");
        assert!(error.contains("index.html") || error.contains("missing"));
        let snapshot = service.snapshot();
        assert!(!snapshot.running);
        assert!(snapshot.error_message.is_some());
    }
}
