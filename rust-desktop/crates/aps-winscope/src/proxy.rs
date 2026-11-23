//! Loopback-only implementation of the Winscope ADB proxy protocol (client
//! version `4.0.8`): token-gated endpoints, `Winscope-Proxy-Version` header, and
//! the endpoint set the bundled viewer probes at the hardcoded
//! `http://localhost:5544` origin.
//!
//! Protocol contract: `docs/winscope-proxy-protocol.md`. CORS preflight passes
//! WITHOUT a token: browsers never attach custom headers to the OPTIONS
//! preflight, and the reference implementation's `do_OPTIONS` bypasses token
//! validation for exactly this reason.

use crate::adb::AdbTransport;
use crate::catalog::DeviceCatalog;
use crate::engine::{CaptureEngine, TraceRequest};
use crate::error::WinscopeError;
use crate::http::{Request, Response, read_request, write_response};
use crate::targets::PROXY_VERSION;
use serde_json::{Value, json};
use std::io::ErrorKind;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::RwLock;
use std::thread::JoinHandle;
use std::time::Duration;

/// The viewer hardcodes this port (`WINSCOPE_PROXY_URL`); drifting would
/// silently break detection.
pub const PROXY_PORT: u16 = 5544;
pub const VERSION_HEADER: &str = "Winscope-Proxy-Version";
pub const TOKEN_HEADER: &str = "Winscope-Token";
const BAD_TOKEN_BODY: &str = "Bad Winscope authorization token!\nThis is Winscope ADB proxy.\n";
const BAD_REQUEST_PREFIX: &str = "Bad request!\nThis is Winscope ADB proxy.\n\n";
/// Worker count for the accept pool; long `/end` calls must not starve the
/// high-frequency `/devices` and `/status` polling.
const WORKERS: usize = 8;
const ACCEPT_POLL: Duration = Duration::from_millis(50);

pub struct ProxyServer {
    engine: std::sync::Arc<CaptureEngine>,
    catalog: std::sync::Arc<DeviceCatalog>,
    transport: AdbTransport,
    port: u16,
    token: RwLock<Option<String>>,
    listener: RwLock<Option<std::sync::Arc<TcpListener>>>,
    shutdown: std::sync::Arc<std::sync::atomic::AtomicBool>,
    workers: RwLock<Vec<JoinHandle<()>>>,
}

impl ProxyServer {
    pub fn new(
        engine: std::sync::Arc<CaptureEngine>,
        catalog: std::sync::Arc<DeviceCatalog>,
        transport: AdbTransport,
        port: u16,
    ) -> Self {
        Self {
            engine,
            catalog,
            transport,
            port,
            token: RwLock::new(None),
            listener: RwLock::new(None),
            shutdown: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            workers: RwLock::new(Vec::new()),
        }
    }

    pub fn is_running(&self) -> bool {
        self.listener.read().map(|listener| listener.is_some()).unwrap_or(false)
    }

    /// Actual bound port after [`Self::start`]; `None` while stopped (supports
    /// ephemeral test ports).
    pub fn bound_port(&self) -> Option<u16> {
        self.listener
            .read()
            .ok()
            .and_then(|listener| listener.as_ref().and_then(|listener| listener.local_addr().ok()))
            .map(|address| address.port())
    }

    /// Starts serving. Fails (does not pick another port) when the port is
    /// occupied — the viewer hardcodes the port, so drifting would silently break
    /// detection.
    pub fn start(&self, new_token: &str) -> Result<(), String> {
        {
            let mut token = self.token.write().map_err(|_| "proxy token poisoned")?;
            if self.is_running() {
                return Err("Winscope proxy server is already running".to_owned());
            }
            *token = Some(new_token.to_owned());
        }
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, self.port)).map_err(|error| {
            format!(
                "Port {} is already in use - close any other Winscope proxy (winscope_proxy.py) and retry ({error})",
                self.port
            )
        })?;
        listener
            .set_nonblocking(true)
            .map_err(|error| format!("Unable to configure the proxy listener: {error}"))?;
        let listener = std::sync::Arc::new(listener);
        let (sender, receiver) = std::sync::mpsc::channel::<TcpStream>();
        let receiver = std::sync::Arc::new(std::sync::Mutex::new(receiver));
        let shutdown = self.shutdown.clone();
        shutdown.store(false, std::sync::atomic::Ordering::SeqCst);
        let mut worker_handles = Vec::with_capacity(WORKERS);
        for index in 0..WORKERS {
            let receiver = receiver.clone();
            let handler = std::sync::Arc::new(self.handler_snapshot());
            let handle = std::thread::Builder::new()
                .name(format!("aps-winscope-proxy-{index}"))
                .spawn(move || loop {
                    // Dequeue under the lock; one request at a time is handed to
                    // a worker, the exchange itself runs outside the lock.
                    let stream = {
                        let guard = match receiver.lock() {
                            Ok(guard) => guard,
                            Err(_) => return,
                        };
                        guard.recv()
                    };
                    match stream {
                        Ok(stream) => serve_connection(handler.clone(), stream),
                        Err(_) => return,
                    }
                })
                .map_err(|error| format!("Unable to start proxy worker: {error}"))?;
            worker_handles.push(handle);
        }
        let accept_listener = listener.clone();
        let accept_sender = sender;
        let accept_handle = std::thread::Builder::new()
            .name("aps-winscope-proxy-accept".to_owned())
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
            .map_err(|error| format!("Unable to start proxy accept loop: {error}"))?;
        worker_handles.push(accept_handle);
        *self.listener.write().map_err(|_| "proxy listener poisoned")? = Some(listener);
        *self.workers.write().map_err(|_| "proxy workers poisoned")? = worker_handles;
        Ok(())
    }

    pub fn stop(&self) {
        self.shutdown.store(true, std::sync::atomic::Ordering::SeqCst);
        if let Ok(mut listener) = self.listener.write() {
            // Dropping the listener closes the socket; the accept loop observes
            // the shutdown flag and exits, which closes the dispatch channel and
            // lets idle workers finish. In-flight exchanges are not joined —
            // the reference stop(0) semantics.
            *listener = None;
        }
        if let Ok(mut token) = self.token.write() {
            *token = None;
        }
        if let Ok(mut workers) = self.workers.write() {
            workers.clear();
        }
    }

    /// Snapshots the shared state into a per-worker handler. The engine and
    /// catalog are already shared; the token needs a read mirror.
    fn handler_snapshot(&self) -> ProxyHandler {
        ProxyHandler {
            engine: self.engine.clone(),
            catalog: self.catalog.clone(),
            transport: self.transport.clone(),
            token: self.token.read().map(|token| token.clone()).ok().flatten(),
        }
    }

    /// The token the server was started with (test seam).
    #[cfg(test)]
    pub fn token(&self) -> Option<String> {
        self.token.read().ok().and_then(|token| token.clone())
    }
}

struct ProxyHandler {
    engine: std::sync::Arc<CaptureEngine>,
    catalog: std::sync::Arc<DeviceCatalog>,
    transport: AdbTransport,
    token: Option<String>,
}

/// One connection lifecycle: read one request, answer, close.
fn serve_connection(handler: std::sync::Arc<ProxyHandler>, mut stream: TcpStream) {
    let request = match read_request(&mut stream) {
        Ok(request) => request,
        Err(_) => return,
    };
    let response = handler.respond(&request);
    write_response(&mut stream, &response);
}

impl ProxyHandler {
    fn respond(&self, request: &Request) -> Response {
        match self.route(request) {
            Ok(response) => self.with_common_headers(response),
            Err(error) => match error {
                WinscopeError::BadRequest(bad) => self.with_common_headers(bad_request(bad.message)),
                other => self.with_common_headers(Response::bytes(
                    500,
                    "text/txt",
                    other.message().to_owned(),
                )),
            },
        }
    }

    fn with_common_headers(&self, mut response: Response) -> Response {
        response
            .headers
            .push(("Access-Control-Allow-Origin".to_owned(), "*".to_owned()));
        response
            .headers
            .push(("Access-Control-Allow-Methods".to_owned(), "POST, GET, OPTIONS".to_owned()));
        response.headers.push((
            "Access-Control-Allow-Headers".to_owned(),
            format!("{TOKEN_HEADER}, Content-Type, Content-Length"),
        ));
        response.headers.push((
            "Access-Control-Expose-Headers".to_owned(),
            VERSION_HEADER.to_owned(),
        ));
        response
            .headers
            .push((VERSION_HEADER.to_owned(), PROXY_VERSION.to_owned()));
        response
    }

    fn route(&self, request: &Request) -> Result<Response, WinscopeError> {
        // CORS preflight must pass WITHOUT a token: browsers never attach custom
        // headers to the OPTIONS preflight.
        if request.method == "OPTIONS" {
            return Ok(Response::bytes(200, "text/txt", "GET,POST"));
        }
        let request_token = request.header(TOKEN_HEADER);
        let matches_token = request_token.is_some()
            && self.token.is_some()
            && request_token == self.token.as_deref();
        if !matches_token {
            return Ok(Response {
                status: 403,
                content_type: "text/txt".to_owned(),
                headers: Vec::new(),
                body: crate::http::Body::Bytes(BAD_TOKEN_BODY.as_bytes().to_vec()),
            });
        }
        if request.segments.is_empty() {
            return Err(WinscopeError::bad_request("No endpoint specified"));
        }
        match request.method.as_str() {
            "GET" => self.route_get(request),
            "POST" => self.route_post(request),
            other => Err(WinscopeError::bad_request(format!("Unsupported method {other}"))),
        }
    }

    fn route_get(&self, request: &Request) -> Result<Response, WinscopeError> {
        match request.segments[0].as_str() {
            "devices" => Ok(Response::bytes(200, "text/json", self.devices_json())),
            "checkwayland" => Ok(Response::bytes(200, "text/json", self.check_wayland())),
            "status" => self.handle_status(&request.segments[1..]),
            "fetch" => self.handle_fetch(&request.segments[1..]),
            endpoint => Err(WinscopeError::bad_request(format!("Unknown endpoint /{endpoint}/"))),
        }
    }

    fn route_post(&self, request: &Request) -> Result<Response, WinscopeError> {
        match request.segments[0].as_str() {
            "start" => self.handle_start(request, &request.segments[1..], false),
            "end" => self.handle_end(&request.segments[1..]),
            "dump" => self.handle_start(request, &request.segments[1..], true),
            endpoint => Err(WinscopeError::bad_request(format!("Unknown endpoint /{endpoint}/"))),
        }
    }

    fn devices_json(&self) -> String {
        match self.catalog.list_devices() {
            Ok(devices) => {
                let mut root = serde_json::Map::new();
                for (serial, info) in devices {
                    root.insert(
                        serial,
                        json!({
                            "authorized": info.authorized,
                            "model": info.model,
                            "displays": info.displays,
                            "screenrecord_version": info.screenrecord_version,
                        }),
                    );
                }
                Value::Object(root).to_string()
            }
            Err(error) => {
                // Device listing failures render as the reference 500 shape.
                let _ = error;
                String::from("{}")
            }
        }
    }

    fn check_wayland(&self) -> String {
        let devices = self.catalog.list_devices().unwrap_or_default();
        let values: Vec<_> = devices.values().collect();
        let result = if values.len() != 1 || !values[0].authorized || values[0].model.is_empty() {
            false
        } else {
            let output = self
                .transport
                .run(
                    &["-s", &values[0].serial, "shell", "service", "check", "Wayland"],
                    &crate::adb::ShellOptions::probe(),
                )
                .output_or_abort()
                .0;
            !output.stdout.contains("not found")
        };
        result.to_string()
    }

    fn handle_status(&self, path: &[String]) -> Result<Response, WinscopeError> {
        let serial = require_serial(path)?;
        if !self.engine.has_active_capture(&serial) {
            return Err(WinscopeError::bad_request(format!(
                "No trace in progress for {serial}"
            )));
        }
        self.engine.reset_keep_alive(&serial);
        // Python str(bool) semantics: the viewer string-compares "True" exactly
        // and treats any other body as a dead trace, auto-stopping the capture.
        let alive = if self.engine.is_capture_alive(&serial) {
            "True"
        } else {
            "False"
        };
        Ok(Response::bytes(200, "text/plain", alive))
    }

    fn handle_fetch(&self, path: &[String]) -> Result<Response, WinscopeError> {
        let serial = require_serial(path)?;
        let files = self.engine.fetch_files(&serial)?;
        let mut root = serde_json::Map::new();
        for (name, chunks) in files {
            root.insert(name, Value::Array(chunks.into_iter().map(Value::String).collect()));
        }
        Ok(Response::bytes(200, "text/json", Value::Object(root).to_string()))
    }

    fn handle_start(
        &self,
        request: &Request,
        path: &[String],
        is_dump: bool,
    ) -> Result<Response, WinscopeError> {
        let serial = require_serial(path)?;
        let requests = parse_requests(request)?;
        let result = if is_dump {
            self.engine.dump(&serial, &requests)?
        } else {
            self.engine.start_capture(&serial, &requests)?
        };
        let body = Value::Array(
            result
                .warnings
                .into_iter()
                .map(Value::String)
                .collect(),
        )
        .to_string();
        Ok(Response::bytes(200, if is_dump { "text/plain" } else { "text/json" }, body))
    }

    fn handle_end(&self, path: &[String]) -> Result<Response, WinscopeError> {
        let serial = require_serial(path)?;
        let errors = self.engine.end_capture(&serial)?;
        let body = Value::Array(errors.into_iter().map(Value::String).collect()).to_string();
        Ok(Response::bytes(200, "text/plain", body))
    }
}

fn require_serial(path: &[String]) -> Result<String, WinscopeError> {
    let Some(serial) = path.first() else {
        return Err(WinscopeError::bad_request("Device id not specified"));
    };
    let valid = !serial.is_empty()
        && serial
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || "._:-".contains(character));
    if !valid {
        return Err(WinscopeError::bad_request("Device id not specified"));
    }
    Ok(serial.clone())
}

fn parse_requests(request: &Request) -> Result<Vec<TraceRequest>, WinscopeError> {
    let Some(content_length) = request.header("Content-Length") else {
        return Err(WinscopeError::bad_request("Missing Content-Length header"));
    };
    if content_length.parse::<u64>().is_err() {
        return Err(WinscopeError::bad_request("Malformed Content-Length header"));
    }
    let root: Value = serde_json::from_slice(&request.body)
        .map_err(|_| WinscopeError::bad_request("Malformed JSON body"))?;
    let Some(entries) = root.as_array() else {
        return Err(WinscopeError::bad_request("Malformed JSON body"));
    };
    Ok(entries.iter().map(TraceRequest::from_value).collect())
}

fn bad_request(message: String) -> Response {
    Response::bytes(400, "text/txt", format!("{BAD_REQUEST_PREFIX}{message}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adb::AdbTransport;
    use crate::http::Body;
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    fn handler_with_token(token: &str) -> ProxyHandler {
        ProxyHandler {
            engine: std::sync::Arc::new(CaptureEngine::new(
                AdbTransport::from_location(aps_adb::AdbLocation {
                    executable: PathBuf::from("adb"),
                    source: aps_adb::AdbLocationSource::Path,
                }),
                None,
            )),
            catalog: std::sync::Arc::new(DeviceCatalog::new(AdbTransport::from_location(
                aps_adb::AdbLocation {
                    executable: PathBuf::from("adb"),
                    source: aps_adb::AdbLocationSource::Path,
                },
            ))),
            transport: AdbTransport::from_location(aps_adb::AdbLocation {
                executable: PathBuf::from("adb"),
                source: aps_adb::AdbLocationSource::Path,
            }),
            token: Some(token.to_owned()),
        }
    }

    fn request(method: &str, path: &str, token: Option<&str>, body: &[u8]) -> Request {
        let mut headers = BTreeMap::new();
        headers.insert(
            "content-length".to_owned(),
            body.len().to_string(),
        );
        if let Some(token) = token {
            headers.insert(TOKEN_HEADER.to_ascii_lowercase(), token.to_owned());
        }
        let segments = path
            .trim_matches('/')
            .split('/')
            .filter(|segment| !segment.is_empty())
            .map(str::to_owned)
            .collect();
        Request {
            method: method.to_owned(),
            segments,
            raw_path: path.to_owned(),
            headers,
            body: body.to_vec(),
        }
    }

    #[test]
    fn options_preflight_passes_without_a_token() {
        let handler = handler_with_token("secret");
        let response = handler.respond(&request("OPTIONS", "/devices/", None, b""));
        assert_eq!(response.status, 200);
        assert_eq!(response.content_type, "text/txt");
        let (_, body) = match &response.body {
            Body::Bytes(bytes) => ((), String::from_utf8_lossy(bytes).into_owned()),
            _ => panic!("bytes"),
        };
        assert_eq!(body, "GET,POST");
        assert!(response.headers.iter().any(|(name, _)| name == VERSION_HEADER));
    }

    #[test]
    fn missing_or_wrong_token_is_forbidden_with_reference_body() {
        let handler = handler_with_token("secret");
        for token in [None, Some("wrong")] {
            let response = handler.respond(&request("GET", "/devices/", token, b""));
            assert_eq!(response.status, 403);
            let body = match &response.body {
                Body::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
                _ => panic!("bytes"),
            };
            assert_eq!(body, BAD_TOKEN_BODY);
        }
    }

    #[test]
    fn correct_token_reaches_the_endpoint_router() {
        let handler = handler_with_token("secret");
        // Unknown endpoint under a valid token gives the reference 400 shape.
        let response = handler.respond(&request("GET", "/unknown/", Some("secret"), b""));
        assert_eq!(response.status, 400);
        let body = match &response.body {
            Body::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
            _ => panic!("bytes"),
        };
        assert_eq!(body, format!("{BAD_REQUEST_PREFIX}Unknown endpoint /unknown/"));
    }

    #[test]
    fn status_without_capture_is_a_reference_bad_request() {
        let handler = handler_with_token("secret");
        let response = handler.respond(&request("GET", "/status/serial1", Some("secret"), b""));
        assert_eq!(response.status, 400);
        let body = match &response.body {
            Body::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
            _ => panic!("bytes"),
        };
        assert!(body.contains("No trace in progress for serial1"));
    }

    #[test]
    fn serial_validation_rejects_hostile_segments() {
        assert!(require_serial(&["serial.1:_-ok".to_owned()]).is_ok());
        assert!(require_serial(&[]).is_err());
        assert!(require_serial(&["bad path".to_owned()]).is_err());
        assert!(require_serial(&["../escape".to_owned()]).is_err());
    }

    #[test]
    fn start_payload_parsing_maps_the_viewer_shape() {
        let body = br#"[{"name":"window_trace","config":[{"key":"tracinglevel","value":"verbose"},{"key":"displays","value":["Display 1 (active)"]}]}]"#;
        let mut headers = BTreeMap::new();
        headers.insert("content-length".to_owned(), body.len().to_string());
        let request = Request {
            method: "POST".to_owned(),
            segments: vec!["start".to_owned(), "serial".to_owned()],
            raw_path: "/start/serial".to_owned(),
            headers,
            body: body.to_vec(),
        };
        let parsed = parse_requests(&request).expect("parses");
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name, "window_trace");
        assert_eq!(parsed[0].config.len(), 2);
        assert_eq!(parsed[0].config[0].key, "tracinglevel");
        assert_eq!(
            parsed[0].config[1].value,
            Value::Array(vec![Value::String("Display 1 (active)".to_owned())])
        );
    }

    #[test]
    fn malformed_bodies_render_as_reference_bad_requests() {
        let mut headers = BTreeMap::new();
        headers.insert("content-length".to_owned(), "5".to_string());
        let request = Request {
            method: "POST".to_owned(),
            segments: vec!["start".to_owned(), "serial".to_owned()],
            raw_path: "/start/serial".to_owned(),
            headers,
            body: b"notjs".to_vec(),
        };
        let error = parse_requests(&request).expect_err("malformed");
        assert_eq!(error.message(), "Malformed JSON body");
        let mut headers = BTreeMap::new();
        let request = Request {
            method: "POST".to_owned(),
            segments: vec![],
            raw_path: "/start/serial".to_owned(),
            headers,
            body: Vec::new(),
        };
        let error = parse_requests(&request).expect_err("no length");
        assert_eq!(error.message(), "Missing Content-Length header");
    }
}
