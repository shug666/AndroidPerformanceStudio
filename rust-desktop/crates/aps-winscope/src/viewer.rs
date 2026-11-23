//! Hosts the packaged Winscope viewer resource closure over loopback so the
//! bundled viewer can be opened in a system browser. Evidence disciplines:
//! `no-store` on evidence-ish content, no directory listing, and closure
//! integrity verification (checksum manifest) before hosting.

use crate::http::{Request, Response, read_request, write_response};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::io::ErrorKind;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::thread::JoinHandle;
use std::time::Duration;

/// Preferred loopback port; the host walks forward when the port is taken.
pub const VIEWER_PREFERRED_PORT: u16 = 8000;
const PORT_ATTEMPTS: u16 = 10;
const WORKERS: usize = 4;
const ACCEPT_POLL: Duration = Duration::from_millis(50);

fn mime_for_extension(extension: &str) -> &'static str {
    match extension {
        "html" => "text/html",
        "js" => "application/javascript",
        "css" => "text/css",
        "svg" => "image/svg+xml",
        "json" => "application/json",
        "wasm" => "application/wasm",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "png" => "image/png",
        "map" => "application/json",
        "txt" => "text/plain",
        _ => "application/octet-stream",
    }
}

pub struct ViewerHost {
    preferred_port: u16,
    root: RwLock<Option<PathBuf>>,
    bound: RwLock<Option<u16>>,
    listener: RwLock<Option<std::sync::Arc<TcpListener>>>,
    shutdown: std::sync::Arc<std::sync::atomic::AtomicBool>,
    workers: RwLock<Vec<JoinHandle<()>>>,
}

impl ViewerHost {
    pub fn new(preferred_port: u16) -> Self {
        Self {
            preferred_port,
            root: RwLock::new(None),
            bound: RwLock::new(None),
            listener: RwLock::new(None),
            shutdown: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            workers: RwLock::new(Vec::new()),
        }
    }

    pub fn is_running(&self) -> bool {
        self.listener.read().map(|listener| listener.is_some()).unwrap_or(false)
    }

    pub fn port(&self) -> Option<u16> {
        match self.bound.read() {
            Ok(bound) => *bound,
            Err(_) => None,
        }
    }

    /// Starts hosting `viewer_root`. `manifest` maps closure-relative paths to
    /// their expected SHA-256; a mismatch or missing tracked file rejects
    /// startup. Unlike the proxy, the viewer port walks forward on conflicts
    /// (the browser follows whatever URL the app hands it).
    pub fn start(&self, raw_viewer_root: &Path, manifest: &BTreeMap<String, String>) -> Result<(), String> {
        if self.is_running() {
            return Err("Winscope viewer host is already running".to_owned());
        }
        // Normalize once: packaged roots may carry `..` components from install
        // layouts; startsWith comparisons are component-based, so an unnormalized
        // root never matches its own targets and every request would 404 (the
        // 2026-11-11 journald diagnosis of the Kotlin port).
        let viewer_root = raw_viewer_root.canonicalize().map_err(|error| {
            format!(
                "Winscope viewer closure is missing or has no index.html: {} ({error})",
                raw_viewer_root.display()
            )
        })?;
        if !viewer_root.is_dir() || !viewer_root.join("index.html").is_file() {
            return Err(format!(
                "Winscope viewer closure is missing or has no index.html: {}",
                viewer_root.display()
            ));
        }
        verify_closure(&viewer_root, manifest)?;
        let mut last_error = String::new();
        let mut bound_listener = None;
        for attempt in 0..PORT_ATTEMPTS {
            match TcpListener::bind((Ipv4Addr::LOCALHOST, self.preferred_port + attempt)) {
                Ok(bound) => {
                    bound_listener = Some(bound);
                    break;
                }
                Err(error) => last_error = error.to_string(),
            }
        }
        let listener = bound_listener.ok_or_else(|| {
            format!(
                "No port available for the Winscope viewer host starting at {} ({last_error})",
                self.preferred_port
            )
        })?;
        listener
            .set_nonblocking(true)
            .map_err(|error| format!("Unable to configure the viewer listener: {error}"))?;
        let bound_port = listener
            .local_addr()
            .map_err(|error| format!("Unable to read bound viewer port: {error}"))?
            .port();
        let listener = std::sync::Arc::new(listener);
        let (sender, receiver) = std::sync::mpsc::channel::<TcpStream>();
        let receiver = std::sync::Arc::new(std::sync::Mutex::new(receiver));
        let shutdown = self.shutdown.clone();
        shutdown.store(false, std::sync::atomic::Ordering::SeqCst);
        let mut worker_handles = Vec::with_capacity(WORKERS);
        for index in 0..WORKERS {
            let receiver = receiver.clone();
            let root = viewer_root.clone();
            let handle = std::thread::Builder::new()
                .name(format!("aps-winscope-viewer-{index}"))
                .spawn(move || loop {
                    let stream = {
                        let guard = match receiver.lock() {
                            Ok(guard) => guard,
                            Err(_) => return,
                        };
                        guard.recv()
                    };
                    match stream {
                        Ok(stream) => serve_connection(&root, stream),
                        Err(_) => return,
                    }
                })
                .map_err(|error| format!("Unable to start viewer worker: {error}"))?;
            worker_handles.push(handle);
        }
        let accept_listener = listener.clone();
        let accept_sender = sender;
        let accept_handle = std::thread::Builder::new()
            .name("aps-winscope-viewer-accept".to_owned())
            .spawn(move || loop {
                if shutdown.load(std::sync::atomic::Ordering::SeqCst) {
                    break;
                }
                match accept_listener.accept() {
                    Ok((stream, _address)) => {
                        let _ = accept_sender.send(stream);
                    }
                    Err(error) if error.kind() == ErrorKind::WouldBlock => {
                        std::thread::sleep(ACCEPT_POLL);
                    }
                    Err(error) if error.kind() == ErrorKind::ConnectionAborted => continue,
                    Err(_) => break,
                }
            })
            .map_err(|error| format!("Unable to start viewer accept loop: {error}"))?;
        worker_handles.push(accept_handle);
        *self.root.write().map_err(|_| "viewer root poisoned")? = Some(viewer_root);
        *self.bound.write().map_err(|_| "viewer port poisoned")? = Some(bound_port);
        *self.listener.write().map_err(|_| "viewer listener poisoned")? = Some(listener);
        *self.workers.write().map_err(|_| "viewer workers poisoned")? = worker_handles;
        Ok(())
    }

    pub fn stop(&self) {
        self.shutdown.store(true, std::sync::atomic::Ordering::SeqCst);
        // In-flight exchanges are not joined — stop(0) semantics.
        if let Ok(mut listener) = self.listener.write() {
            *listener = None;
        }
        if let Ok(mut bound) = self.bound.write() {
            *bound = None;
        }
        if let Ok(mut root) = self.root.write() {
            *root = None;
        }
        if let Ok(mut workers) = self.workers.write() {
            workers.clear();
        }
    }
}

/// One connection lifecycle: read one request, answer, close.
fn serve_connection(root: &Path, mut stream: TcpStream) {
    let request = match read_request(&mut stream) {
        Ok(request) => request,
        Err(_) => return,
    };
    let response = resolve(root, &request);
    write_response(&mut stream, &response);
}

fn resolve(root: &Path, request: &Request) -> Response {
    let requested = request.raw_path.trim_start_matches('/').to_owned();
    let target = if requested.is_empty() {
        root.join("index.html")
    } else {
        root.join(&requested)
    };
    let normalized = normalize(&target);
    // Directory listing is never produced; only real files inside the closure
    // resolve.
    if !normalized.starts_with(root) || !normalized.is_file() {
        return Response::empty(404, "text/txt");
    }
    let extension = normalized
        .extension()
        .map(|extension| extension.to_string_lossy().into_owned())
        .unwrap_or_default();
    Response::file(200, mime_for_extension(&extension), normalized)
        .with_header("Cache-Control", "no-store")
}

/// Lexical normalization without touching the file system; rejects any path that
/// escapes the root via `..`.
fn normalize(path: &Path) -> PathBuf {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::ParentDir => {
                normalized.pop();
            }
            std::path::Component::CurDir => {}
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

fn verify_closure(
    viewer_root: &Path,
    manifest: &BTreeMap<String, String>,
) -> Result<(), String> {
    for (relative_path, expected_sha256) in manifest {
        let file = viewer_root.join(relative_path);
        if !file.is_file() {
            return Err(format!("Winscope viewer closure file missing: {relative_path}"));
        }
        let actual = sha256_hex(&file)?;
        if !actual.eq_ignore_ascii_case(expected_sha256) {
            return Err(format!(
                "Winscope viewer closure checksum mismatch for {relative_path} (expected {expected_sha256}, got {actual})"
            ));
        }
    }
    Ok(())
}

/// Hex-encoded SHA-256 of a file, streamed so the 33 MB entry stays bounded.
pub fn sha256_hex(path: &Path) -> Result<String, String> {
    let mut file =
        std::fs::File::open(path).map_err(|error| format!("Unable to read {}: {error}", path.display()))?;
    let mut digest = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = std::io::Read::read(&mut file, &mut buffer)
            .map_err(|error| format!("Unable to hash {}: {error}", path.display()))?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    let digest = digest.finalize();
    let mut hex = String::with_capacity(digest.len() * 2);
    for byte in digest {
        hex.push_str(&format!("{byte:02x}"));
    }
    Ok(hex)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::Body;

    #[test]
    fn mime_table_covers_the_bundle_suffixes() {
        assert_eq!(mime_for_extension("html"), "text/html");
        assert_eq!(mime_for_extension("js"), "application/javascript");
        assert_eq!(mime_for_extension("wasm"), "application/wasm");
        assert_eq!(mime_for_extension("woff2"), "font/woff2");
        assert_eq!(mime_for_extension("svg"), "image/svg+xml");
        assert_eq!(mime_for_extension("unknown"), "application/octet-stream");
    }

    #[test]
    fn normalization_rejects_parent_escapes() {
        let root = Path::new("/srv/winscope-viewer");
        assert_eq!(normalize(&root.join("index.html")), root.join("index.html"));
        let escaping = normalize(&root.join("../secret.txt"));
        assert!(!escaping.starts_with(root));
        let nested = normalize(&root.join("js").join("..").join("index.html"));
        assert_eq!(nested, root.join("index.html"));
    }

    #[test]
    fn resolve_serves_index_for_the_root_and_404_outside() {
        let fixture = std::env::temp_dir().join(format!("aps-winscope-viewer-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&fixture);
        std::fs::create_dir_all(fixture.join("js")).expect("dirs");
        std::fs::write(fixture.join("index.html"), b"<html></html>").expect("index");
        std::fs::write(fixture.join("js").join("app.js"), b"export {}").expect("app");
        let outside = std::env::temp_dir().join("aps-winscope-outside.txt");
        std::fs::write(&outside, b"secret").expect("outside");

        let root_request = Request {
            method: "GET".to_owned(),
            segments: Vec::new(),
            raw_path: "/".to_owned(),
            headers: Default::default(),
            body: Vec::new(),
        };
        let response = resolve(&fixture, &root_request);
        assert_eq!(response.status, 200);
        assert_eq!(response.content_type, "text/html");
        match &response.body {
            Body::File(path) => assert_eq!(path, fixture.join("index.html").as_path()),
            _ => panic!("file body"),
        }

        let traversal = Request {
            method: "GET".to_owned(),
            segments: vec!["..".to_owned(), outside.file_name().unwrap().to_string_lossy().into_owned()],
            raw_path: format!("/../{}", outside.file_name().unwrap().to_string_lossy()),
            headers: Default::default(),
            body: Vec::new(),
        };
        let response = resolve(&fixture, &traversal);
        assert_eq!(response.status, 404);

        let js = Request {
            method: "GET".to_owned(),
            segments: vec!["js".to_owned(), "app.js".to_owned()],
            raw_path: "/js/app.js".to_owned(),
            headers: Default::default(),
            body: Vec::new(),
        };
        let response = resolve(&fixture, &js);
        assert_eq!(response.status, 200);
        assert_eq!(response.content_type, "application/javascript");

        let _ = std::fs::remove_dir_all(&fixture);
        let _ = std::fs::remove_file(&outside);
    }

    #[test]
    fn checksum_verification_rejects_missing_and_tampered_files() {
        let fixture = std::env::temp_dir().join(format!("aps-winscope-manifest-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&fixture);
        std::fs::create_dir_all(&fixture).expect("dirs");
        std::fs::write(fixture.join("index.html"), b"<html>bundle</html>").expect("index");

        let actual = sha256_hex(&fixture.join("index.html")).expect("hash");
        let mut manifest = BTreeMap::new();
        manifest.insert("index.html".to_owned(), actual.clone());
        assert!(verify_closure(&fixture, &manifest).is_ok());

        manifest.insert("missing.js".to_owned(), "00".to_owned());
        let error = verify_closure(&fixture, &manifest).expect_err("missing file");
        assert!(error.contains("closure file missing"));

        manifest.remove("missing.js");
        manifest.insert("index.html".to_owned(), "ff".to_owned());
        let error = verify_closure(&fixture, &manifest).expect_err("checksum");
        assert!(error.contains("checksum mismatch"));

        let _ = std::fs::remove_dir_all(&fixture);
    }

    #[test]
    fn sha256_hex_is_stable_for_known_input() {
        let fixture = std::env::temp_dir().join(format!("aps-winscope-sha-test-{}", std::process::id()));
        std::fs::write(&fixture, b"abc").expect("write");
        // The well-known SHA-256 of "abc".
        assert_eq!(
            sha256_hex(&fixture).expect("hash"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        let _ = std::fs::remove_file(&fixture);
    }
}
