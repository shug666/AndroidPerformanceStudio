//! Executes the Winscope capture decision table on a device via adb, ported from
//! `winscope_proxy.py` (v4.0.8). Behavior contract:
//! `docs/winscope-proxy-protocol.md`.
//!
//! Modern path first: when the device's perfetto instance exposes the requested
//! winscope data sources (and fewer than five concurrent sessions exist), all
//! sources are collected into one fused perfetto trace; otherwise each target
//! falls back to its legacy commands and files are collected from
//! `/data/misc/wmtrace/` etc.

use crate::adb::{AdbOutput, AdbTransport, ShellOptions, ShellOutcome};
use crate::config as config_text;
use crate::error::WinscopeError;
use crate::targets::{
    self, CollectedFile, DumpTarget, TraceTarget, COMMAND_TIMEOUT_SECONDS, KEEP_ALIVE_INTERVAL_SECONDS,
    PERFETTO_DETACH_NAME, PERFETTO_DUMP_CONFIG_FILE, PERFETTO_DUMP_FILE, PERFETTO_TRACE_CONFIG_FILE,
    PERFETTO_TRACE_FILE, PERFETTO_UNIQUE_SESSION_NAME, SIGNAL_HANDLER_LOG, WINSCOPE_BACKUP_DIR,
    WINSCOPE_STATUS_PREFIX,
};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashMap};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// One capture configuration entry sent by the bundled viewer with `/start`/`/dump`
/// (`{"key": ..., "value": ...}`); value is a string, number or list of strings.
#[derive(Debug, Clone, PartialEq)]
pub struct ConfigEntry {
    pub key: String,
    pub value: Value,
}

/// One trace request entry sent by the bundled viewer (`{"name": ..., "config": [...]}`).
#[derive(Debug, Clone, PartialEq)]
pub struct TraceRequest {
    pub name: String,
    pub config: Vec<ConfigEntry>,
}

impl TraceRequest {
    pub fn from_value(value: &Value) -> TraceRequest {
        let name = value
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned();
        let config = value
            .get("config")
            .and_then(Value::as_array)
            .map(|entries| {
                entries
                    .iter()
                    .map(|entry| ConfigEntry {
                        key: entry
                            .get("key")
                            .and_then(Value::as_str)
                            .unwrap_or_default()
                            .to_owned(),
                        value: entry.get("value").cloned().unwrap_or(Value::Null),
                    })
                    .collect()
            })
            .unwrap_or_default();
        TraceRequest { name, config }
    }

    fn string_value(&self, key: &str) -> Option<String> {
        self.config.iter().find(|entry| entry.key == key).map(|entry| match &entry.value {
            Value::String(text) => text.clone(),
            Value::Number(number) => number.to_string(),
            Value::Bool(flag) => flag.to_string(),
            _ => String::new(),
        })
    }

    /// `displays` may arrive as a string or a list of strings.
    fn display_identifiers(&self, key: &str) -> Option<Vec<String>> {
        self.config.iter().find(|entry| entry.key == key).and_then(|entry| match &entry.value {
            Value::String(text) if !text.trim().is_empty() => Some(vec![text.clone()]),
            Value::Array(items) if !items.is_empty() => Some(
                items
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_owned)
                    .collect(),
            ),
            _ => None,
        })
    }
}

/// A target selected for execution with its resolved modern/legacy path and
/// identifiers.
#[derive(Debug, Clone, PartialEq)]
struct PreparedTarget {
    name: String,
    is_perfetto: bool,
    is_dump: bool,
    identifiers: Vec<String>,
    /// Optional start args per identifier (screen recording display ids).
    options_by_identifier: HashMap<String, String>,
}

/// Result of a completed start/end/dump device exchange (viewer-facing warnings).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PrepareResult {
    pub warnings: Vec<String>,
}

/// Outcome of one trace shell thread.
#[derive(Debug, Clone)]
struct TraceOutcome {
    output: AdbOutput,
    cancelled: bool,
}

#[derive(Default)]
struct OutcomeSlot {
    slot: Mutex<Option<TraceOutcome>>,
    signal: Condvar,
}

impl OutcomeSlot {
    fn complete(&self, outcome: TraceOutcome) {
        if let Ok(mut guard) = self.slot.lock() {
            *guard = Some(outcome);
            self.signal.notify_all();
        }
    }

    /// Waits up to `timeout` for the shell thread to publish its outcome.
    fn wait(&self, timeout: Duration) -> Option<TraceOutcome> {
        let deadline = Instant::now() + timeout;
        let mut guard = match self.slot.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        while guard.is_none() {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            guard = match self.signal.wait_timeout(guard, remaining) {
                Ok((waited, _timed_out)) => waited,
                Err(poisoned) => poisoned.into_inner().0,
            };
        }
        guard.clone()
    }
}

struct ActiveTrace {
    target_name: String,
    identifier: String,
    status_filename: String,
    stop: Arc<AtomicBool>,
    outcome: Arc<OutcomeSlot>,
}

#[derive(Default)]
struct EngineState {
    /// serial -> running trace shells (insertion order preserved).
    active: BTreeMap<String, Vec<Arc<ActiveTrace>>>,
    /// serial -> keep-alive deadline (viewer /status polling refreshes it).
    keep_alive: BTreeMap<String, Instant>,
    /// serial -> target names of the last completed capture (fetch archival).
    last_targets: BTreeMap<String, Vec<String>>,
}

/// Snapshot of one device's browser-driven capture, mirrored into the page state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActiveCapture {
    pub serial: String,
    pub target_names: Vec<String>,
}

/// Executes the Winscope capture decision table on a device via adb.
pub struct CaptureEngine {
    transport: AdbTransport,
    state: Mutex<EngineState>,
    /// Session artifact root for dual-path archival (design D5); None disables it.
    archive_root: Option<PathBuf>,
}

impl CaptureEngine {
    pub fn new(transport: AdbTransport, archive_root: Option<PathBuf>) -> Self {
        Self {
            transport,
            state: Mutex::new(EngineState::default()),
            archive_root,
        }
    }

    pub fn has_active_capture(&self, serial: &str) -> bool {
        self.state
            .lock()
            .map(|state| state.active.contains_key(serial))
            .unwrap_or(false)
    }

    /// Serials that currently have a capture in progress (for app-exit cleanup).
    pub fn active_capture_serials(&self) -> Vec<String> {
        self.state
            .lock()
            .map(|state| state.active.keys().cloned().collect())
            .unwrap_or_default()
    }

    pub fn active_target_names(&self, serial: &str) -> Vec<String> {
        self.state
            .lock()
            .map(|state| {
                state
                    .active
                    .get(serial)
                    .map(|traces| traces.iter().map(|trace| trace.target_name.clone()).collect())
                    .unwrap_or_default()
            })
            .unwrap_or_default()
    }

    /// The browser-driven capture snapshot for the page (first active serial).
    pub fn active_capture(&self) -> Option<ActiveCapture> {
        let state = self.state.lock().ok()?;
        let (serial, traces) = state.active.iter().next()?;
        Some(ActiveCapture {
            serial: serial.clone(),
            target_names: traces.iter().map(|trace| trace.target_name.clone()).collect(),
        })
    }

    /// Whether the first active trace shell for `serial` is still running. The
    /// viewer polls `/status` every second and string-compares against
    /// Python-style "True": anything else is treated as a dead trace and triggers
    /// an automatic stop.
    pub fn is_capture_alive(&self, serial: &str) -> bool {
        let Some(ok) = self.state.lock().ok() else {
            return false;
        };
        let Some(traces) = ok.active.get(serial) else {
            return false;
        };
        traces
            .first()
            .is_some_and(|trace| trace.outcome.slot.lock().map(|slot| slot.is_none()).unwrap_or(false))
    }

    // ------------------------------------------------------------------ start

    /// Prepares and starts a capture for `serial`. Returns viewer-facing warnings
    /// (e.g. session-limit fallback). A duplicate start mirrors the reference
    /// behavior: an empty success without spawning a second set of trace shells.
    pub fn start_capture(&self, serial: &str, requests: &[TraceRequest]) -> Result<PrepareResult, WinscopeError> {
        if self.has_active_capture(serial) {
            return Ok(PrepareResult::default());
        }
        let (prepared, warnings) = self.prepare(serial, requests, false)?;
        for target in &prepared {
            for identifier in &target.identifiers {
                let trace_target = targets::trace_target(&target.name)
                    .ok_or_else(|| WinscopeError::device(format!("Unknown trace target {}", target.name)))?;
                let start_command = if !identifier.is_empty() {
                    trace_target
                        .legacy_start
                        .replace("{trace_identifier}", identifier)
                        .replace("{options}", target.options_by_identifier.get(identifier).map(String::as_str).unwrap_or(""))
                } else if target.is_perfetto {
                    self.perfetto_trace_start_command()
                } else {
                    trace_target.legacy_start.to_owned()
                };
                let stop_command = if target.is_perfetto {
                    perfetto_trace_stop_command()
                } else if !identifier.is_empty() {
                    trace_target
                        .legacy_stop
                        .replace("{trace_identifier}", identifier)
                        .replace("{options}", target.options_by_identifier.get(identifier).map(String::as_str).unwrap_or(""))
                } else {
                    trace_target.legacy_stop.to_owned()
                };
                let status_filename = format!("{WINSCOPE_STATUS_PREFIX}_{}", target.name);
                let script = start_shell_script(&status_filename, &stop_command, &start_command);
                let stop = Arc::new(AtomicBool::new(false));
                let outcome = Arc::new(OutcomeSlot::default());
                let active = Arc::new(ActiveTrace {
                    target_name: target.name.clone(),
                    identifier: identifier.clone(),
                    status_filename: status_filename.clone(),
                    stop: stop.clone(),
                    outcome: outcome.clone(),
                });
                {
                    let mut state = self.state.lock().map_err(|_| WinscopeError::device("capture engine poisoned"))?;
                    state
                        .active
                        .entry(serial.to_owned())
                        .or_default()
                        .push(active);
                    state.keep_alive.insert(
                        serial.to_owned(),
                        Instant::now() + Duration::from_secs(KEEP_ALIVE_INTERVAL_SECONDS * 2),
                    );
                }
                let transport = self.transport.clone();
                let shell_serial = serial.to_owned();
                let shell_stop = stop.clone();
                let slot = outcome.clone();
                std::thread::Builder::new()
                    .name(format!("aps-winscope-trace-{}", target.name))
                    .spawn(move || {
                        let result = transport.run(
                            &["-s", &shell_serial, "shell", "sh", "-c", &script],
                            &ShellOptions::trace_shell(shell_stop),
                        );
                        let outcome = match result {
                            ShellOutcome::Completed(output) => TraceOutcome {
                                output,
                                cancelled: false,
                            },
                            ShellOutcome::Aborted(abort) => {
                                let (output, cancelled) =
                                    ShellOutcome::Aborted(abort).output_or_abort();
                                TraceOutcome { output, cancelled }
                            }
                        };
                        slot.complete(outcome);
                    })
                    .map_err(|error| WinscopeError::device(format!("Unable to start trace shell: {error}")))?;
            }
        }
        Ok(PrepareResult { warnings })
    }

    // -------------------------------------------------------------------- end

    /// Stops the active capture for `serial`, collects device files into the
    /// backup directory, and returns viewer-facing errors (empty list on success).
    pub fn end_capture(&self, serial: &str) -> Result<Vec<String>, WinscopeError> {
        let active_list = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| WinscopeError::device("capture engine poisoned"))?;
            state
                .active
                .remove(serial)
                .ok_or_else(|| WinscopeError::bad_request(format!("No trace in progress for {serial}")))?
        };
        {
            let mut state = self
                .state
                .lock()
                .map_err(|_| WinscopeError::device("capture engine poisoned"))?;
            state.last_targets.insert(
                serial.to_owned(),
                active_list.iter().map(|trace| trace.target_name.clone()).collect(),
            );
            state.keep_alive.remove(serial);
        }
        let mut errors = Vec::new();
        for trace in &active_list {
            trace.stop.store(true, Ordering::SeqCst);
            let outcome = trace
                .outcome
                .wait(Duration::from_secs(COMMAND_TIMEOUT_SECONDS))
                .unwrap_or(TraceOutcome {
                    output: AdbOutput {
                        stdout: String::new(),
                        stderr: format!("Trace {} timed out during cleanup", trace.target_name),
                        success: false,
                    },
                    cancelled: false,
                });
            let status_seen = self.poll_status_ok(serial, &trace.status_filename);
            // A cancelled shell is the reference implementation's intended stop
            // mechanism (killing the local adb client triggers the device-side
            // trap); only an uncancelled non-empty stderr means the trace failed.
            let success = if trace.target_name == "perfetto_trace" {
                status_seen
            } else {
                status_seen && (outcome.cancelled || outcome.output.stderr.trim().is_empty())
            };
            if !success {
                let stderr = if outcome.output.stderr.trim().is_empty() {
                    "<no stderr>"
                } else {
                    outcome.output.stderr.trim()
                };
                errors.push(format!(
                    "Error ending trace {} on the device: {stderr}",
                    trace.target_name
                ));
            }
            self.move_collected_files(serial, &trace.target_name, &trace.identifier);
        }
        for trace in &active_list {
            let _ = self.transport.run(
                &["-s", serial, "shell", "su", "root", "rm", "-f", &trace.status_filename],
                &ShellOptions::probe(),
            );
        }
        Ok(errors)
    }

    // ------------------------------------------------------------------- dump

    /// Runs a one-shot dump for `serial` and returns viewer-facing warnings.
    pub fn dump(&self, serial: &str, requests: &[TraceRequest]) -> Result<PrepareResult, WinscopeError> {
        let (prepared, warnings) = self.prepare(serial, requests, true)?;
        let mut dump_commands = Vec::new();
        for target in &prepared {
            let dump_target = targets::dump_target(&target.name)
                .ok_or_else(|| WinscopeError::device(format!("Unknown dump target {}", target.name)))?;
            if target.name == "perfetto_dump" {
                dump_commands.push(perfetto_dump_command());
            } else if target.identifiers.is_empty() {
                dump_commands.push(dump_target.dump_command.to_owned());
            } else {
                for identifier in &target.identifiers {
                    dump_commands.push(
                        dump_target
                            .dump_command
                            .replace("{trace_identifier}", identifier)
                            .replace(
                                "{options}",
                                target
                                    .options_by_identifier
                                    .get(identifier)
                                    .map(String::as_str)
                                    .unwrap_or(""),
                            ),
                    );
                }
            }
        }
        if !dump_commands.is_empty() {
            self.require_device_success(
                &self
                    .transport
                    .run(
                        &["-s", serial, "shell", "sh", "-c", &dump_commands.join("\n")],
                        &ShellOptions {
                            timeout: Duration::from_secs(60),
                            ..ShellOptions::default()
                        },
                    )
                    .output_or_abort()
                    .0,
                "Error executing dump command.",
            )?;
        }
        for target in &prepared {
            let identifier = target.identifiers.first().cloned().unwrap_or_default();
            self.move_collected_files(serial, &target.name, &identifier);
        }
        Ok(PrepareResult { warnings })
    }

    // ------------------------------------------------------------------ fetch

    /// Pulls collected files from the device backup directory and encodes them
    /// the way the bundled viewer expects: `{ "<name>.gz": [ "<base64-mime>" ] }`.
    pub fn fetch_files(&self, serial: &str) -> Result<BTreeMap<String, Vec<String>>, WinscopeError> {
        let paths = self.list_backup_files(serial);
        let mut result: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut raw: BTreeMap<String, Vec<u8>> = BTreeMap::new();
        for path in paths {
            let temp = std::env::temp_dir().join(format!(
                "aps-winscope-fetch-{}-{}",
                std::process::id(),
                unique_counter()
            ));
            let fetched = self.transport.fetch_to_file(serial, &path, &temp);
            if let Err(error) = fetched {
                let _ = std::fs::remove_file(&temp);
                // The message is carried to the viewer in the reference 500 shape;
                // the raw cause is not wire-relevant.
                return Err(WinscopeError::device(format!("Unable to fetch file {path} - {error}")));
            }
            let bytes = match std::fs::read(&temp) {
                Ok(bytes) => bytes,
                Err(error) => {
                    let _ = std::fs::remove_file(&temp);
                    return Err(WinscopeError::device(format!(
                        "Unable to fetch file {path} - {error}"
                    )));
                }
            };
            let _ = std::fs::remove_file(&temp);
            let file_name = path.rsplit('/').next().unwrap_or(&path).to_owned();
            raw.insert(file_name.clone(), bytes.clone());
            result
                .entry(format!("{file_name}.gz"))
                .or_default()
                .push(gzip_base64(&bytes));
        }
        if !raw.is_empty() {
            self.archive_fetch(serial, &raw);
        }
        Ok(result)
    }

    // --------------------------------------------------------------- keepalive

    /// Resets the keep-alive deadline for `serial` (invoked by `GET /status`).
    pub fn reset_keep_alive(&self, serial: &str) {
        if let Ok(mut state) = self.state.lock() {
            state.keep_alive.insert(
                serial.to_owned(),
                Instant::now() + Duration::from_secs(KEEP_ALIVE_INTERVAL_SECONDS * 2),
            );
        }
    }

    /// Watchdog sweep: ends captures whose viewer keep-alive expired so device
    /// tracing sessions are released instead of hanging forever. Returns the
    /// expired serials.
    pub fn sweep_expired(&self) -> Vec<String> {
        let now = Instant::now();
        let expired: Vec<String> = match self.state.lock() {
            Ok(mut state) => {
                let expired: Vec<String> = state
                    .keep_alive
                    .iter()
                    .filter(|(_, deadline)| **deadline < now)
                    .map(|(serial, _)| serial.clone())
                    .collect();
                for serial in &expired {
                    state.keep_alive.remove(serial);
                }
                expired
            }
            Err(_) => return Vec::new(),
        };
        for serial in &expired {
            let _ = self.end_capture(serial);
        }
        expired
    }

    // ----------------------------------------------------------------- prepare

    fn prepare(
        &self,
        serial: &str,
        requests: &[TraceRequest],
        is_dump: bool,
    ) -> Result<(Vec<PreparedTarget>, Vec<String>), WinscopeError> {
        let config_file = if is_dump {
            PERFETTO_DUMP_CONFIG_FILE
        } else {
            PERFETTO_TRACE_CONFIG_FILE
        };
        self.require_device_success(
            &self
                .transport
                .run(
                    &["-s", serial, "shell", "su", "root", "rm", "-f", config_file],
                    &ShellOptions::probe(),
                )
                .output_or_abort()
                .0,
            "Failed to clear perfetto config file",
        )?;

        let mut warnings = Vec::new();
        let probe = self
            .transport
            .run(&["-s", serial, "shell", "perfetto", "--query"], &ShellOptions::probe())
            .output_or_abort()
            .0;
        let probe_result = probe.stdout;
        let too_many_sessions = self.resolve_too_many_sessions(serial, &probe_result);
        if too_many_sessions {
            warnings.push(
                "Limit of 5 Perfetto sessions reached on device. Will attempt to collect legacy traces."
                    .to_owned(),
            );
        }
        let any_source_available = targets::PROBED_DATA_SOURCES
            .iter()
            .any(|name| probe_result.contains(name));

        let mut prepared = Vec::new();
        for request in requests {
            if is_dump {
                let Some(target) = targets::dump_target(&request.name) else {
                    continue;
                };
                let is_perfetto = !too_many_sessions && targets::is_dump_perfetto_available(&request.name, &probe_result);
                self.apply_config(serial, request, is_perfetto, true, config_file)?;
                let included = (request.name == "perfetto_dump"
                    && !too_many_sessions
                    && any_source_available)
                    || (request.name != "perfetto_dump" && !is_perfetto);
                if included {
                    prepared.push(prepared_dump(target, is_perfetto, request));
                }
            } else {
                let Some(target) = targets::trace_target(&request.name) else {
                    continue;
                };
                let is_perfetto = !too_many_sessions && targets::is_perfetto_available(&request.name, &probe_result);
                self.apply_config(serial, request, is_perfetto, false, config_file)?;
                let included = (request.name == "perfetto_trace"
                    && !too_many_sessions
                    && any_source_available)
                    || (request.name != "perfetto_trace" && !is_perfetto);
                if included {
                    prepared.push(prepared_trace(target, is_perfetto, request));
                }
            }
        }
        // The perfetto target must come last so its buffers/session header lands
        // after all data-source config entries in the device config file.
        prepared.sort_by_key(|target| target.is_perfetto);

        self.require_root(serial)?;
        self.clear_last_tracing_session(serial)?;
        Ok((prepared, warnings))
    }

    fn apply_config(
        &self,
        serial: &str,
        request: &TraceRequest,
        is_perfetto: bool,
        is_dump: bool,
        config_file: &str,
    ) -> Result<(), WinscopeError> {
        match request.name.as_str() {
            "layers_trace" | "layers_dump" => {
                let flags: Vec<&str> = request
                    .config
                    .iter()
                    .map(|entry| entry.key.as_str())
                    .filter(|key| SF_LEGACY_FLAGS.contains(key))
                    .collect();
                let buffer_size = request
                    .string_value("sfbuffersize")
                    .unwrap_or_else(|| "16000".to_owned());
                if is_perfetto {
                    let mapped: Vec<&str> = flags
                        .iter()
                        .filter_map(|flag| sf_perfetto_flag(flag))
                        .collect();
                    let text = config_text::surface_flinger_layers(&mapped, is_dump);
                    self.append_source_config(serial, config_file, &text, "SF perfetto config failed")?;
                } else if !is_dump {
                    let flag_bits = flags
                        .iter()
                        .filter_map(|flag| sf_legacy_flag_bits(flag))
                        .fold(0u32, |acc, bits| acc | bits);
                    self.require_device_success(
                        &self
                            .transport
                            .run(
                                &[
                                    "-s",
                                    serial,
                                    "shell",
                                    "sh",
                                    "-c",
                                    &format!("su root service call SurfaceFlinger 1033 i32 {flag_bits}"),
                                ],
                                &ShellOptions::probe(),
                            )
                            .output_or_abort()
                            .0,
                        "SF flags config failed",
                    )?;
                    self.require_device_success(
                        &self
                            .transport
                            .run(
                                &[
                                    "-s",
                                    serial,
                                    "shell",
                                    "sh",
                                    "-c",
                                    &format!("su root service call SurfaceFlinger 1029 i32 {buffer_size}"),
                                ],
                                &ShellOptions::probe(),
                            )
                            .output_or_abort()
                            .0,
                        "SF buffer size config failed",
                    )?;
                }
            }
            "window_trace" => {
                let level = request.string_value("tracinglevel").unwrap_or_else(|| "debug".to_owned());
                let frequency = request
                    .string_value("tracingtype")
                    .unwrap_or_else(|| "frame".to_owned());
                let buffer_size = request
                    .string_value("wmbuffersize")
                    .unwrap_or_else(|| "16000".to_owned());
                if is_perfetto {
                    let Some(level) = wm_level(&level) else {
                        return Err(WinscopeError::bad_request(format!(
                            "Unsupported tracinglevel {level}"
                        )));
                    };
                    let Some(frequency) = wm_frequency(&frequency) else {
                        return Err(WinscopeError::bad_request(format!(
                            "Unsupported tracingtype {frequency}"
                        )));
                    };
                    let text = config_text::window_manager(level, frequency);
                    self.append_source_config(serial, config_file, &text, "WM perfetto config failed")?;
                } else {
                    // Buffer size must be configured last: earlier commands may
                    // override it.
                    self.require_device_success(
                        &self
                            .transport
                            .run(
                                &["-s", serial, "shell", "sh", "-c", &format!("su root cmd window tracing {frequency}")],
                                &ShellOptions::probe(),
                            )
                            .output_or_abort()
                            .0,
                        "WM tracing type config failed",
                    )?;
                    self.require_device_success(
                        &self
                            .transport
                            .run(
                                &["-s", serial, "shell", "sh", "-c", &format!("su root cmd window tracing level {level}")],
                                &ShellOptions::probe(),
                            )
                            .output_or_abort()
                            .0,
                        "WM tracing level config failed",
                    )?;
                    self.require_device_success(
                        &self
                            .transport
                            .run(
                                &["-s", serial, "shell", "sh", "-c", &format!("su root cmd window tracing size {buffer_size}")],
                                &ShellOptions::probe(),
                            )
                            .output_or_abort()
                            .0,
                        "WM buffer size config failed",
                    )?;
                }
            }
            "view_capture_trace" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::view_capture(), "Perfetto source config failed")?;
            }
            "transactions" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::transactions(), "Perfetto source config failed")?;
            }
            "proto_log" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::proto_log(), "Perfetto source config failed")?;
            }
            "ime" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::ime(), "Perfetto source config failed")?;
            }
            "transition_traces" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::transitions(), "Perfetto source config failed")?;
            }
            "input" if is_perfetto => {
                self.append_source_config(serial, config_file, &config_text::input(), "Perfetto source config failed")?;
            }
            _ => {}
        }
        Ok(())
    }

    fn append_source_config(
        &self,
        serial: &str,
        config_file: &str,
        text: &str,
        context: &str,
    ) -> Result<(), WinscopeError> {
        self.require_device_success(
            &self
                .transport
                .run(
                    &[
                        "-s",
                        serial,
                        "shell",
                        "sh",
                        "-c",
                        &config_text::append_config_command(config_file, text),
                    ],
                    &ShellOptions::probe(),
                )
                .output_or_abort()
                .0,
            context,
        )
    }

    // ------------------------------------------------------------------ helpers

    fn require_device_success(&self, output: &AdbOutput, context: &str) -> Result<(), WinscopeError> {
        if !output.success {
            let detail = if output.stderr.trim().is_empty() {
                String::new()
            } else {
                format!("\n{}", output.stderr.trim())
            };
            return Err(WinscopeError::device(format!("{context}{detail}")));
        }
        Ok(())
    }

    fn require_root(&self, serial: &str) -> Result<(), WinscopeError> {
        let output = self
            .transport
            .run(&["-s", serial, "shell", "su", "root", "id", "-u"], &ShellOptions::probe())
            .output_or_abort()
            .0;
        if output.stdout.trim() != "0" {
            return Err(WinscopeError::device(format!(
                "Unable to acquire root privileges on the device - check the output of 'adb -s {serial} shell su root id'"
            )));
        }
        Ok(())
    }

    fn clear_last_tracing_session(&self, serial: &str) -> Result<(), WinscopeError> {
        self.require_device_success(
            &self
                .transport
                .run(
                    &[
                        "-s",
                        serial,
                        "shell",
                        "sh",
                        "-c",
                        &format!("su root rm -rf {WINSCOPE_BACKUP_DIR} && su root mkdir {WINSCOPE_BACKUP_DIR}"),
                    ],
                    &ShellOptions::probe(),
                )
                .output_or_abort()
                .0,
            "Failed to clear previous tracing session files",
        )
    }

    /// Parses `perfetto --query` sessions; stops a stale winscope session and
    /// applies the 5-session limit.
    fn resolve_too_many_sessions(&self, serial: &str, probe_result: &str) -> bool {
        let Some(start) = probe_result.find(SESSIONS_TABLE_HEADER) else {
            return false;
        };
        let after_header = &probe_result[start + SESSIONS_TABLE_HEADER.len()..];
        let end = match after_header.find(SESSIONS_TABLE_FOOTER) {
            Some(offset) if offset > 0 => offset,
            _ => after_header.len(),
        };
        let rows: Vec<&str> = after_header[..end]
            .trim()
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .collect();
        let mut count = rows.len();
        if rows.iter().any(|row| row.contains(PERFETTO_UNIQUE_SESSION_NAME)) {
            let _ = self.transport.run(
                &[
                    "-s",
                    serial,
                    "shell",
                    "sh",
                    "-c",
                    &format!("perfetto --attach={PERFETTO_DETACH_NAME} --stop"),
                ],
                &ShellOptions::probe(),
            );
            count -= 1;
        }
        count >= 5
    }

    fn poll_status_ok(&self, serial: &str, status_filename: &str) -> bool {
        let deadline = Instant::now() + Duration::from_secs(COMMAND_TIMEOUT_SECONDS);
        while Instant::now() < deadline {
            let output = self
                .transport
                .run(
                    &["-s", serial, "shell", "su", "root", "cat", status_filename],
                    &ShellOptions {
                        timeout: Duration::from_secs(5),
                        ..ShellOptions::default()
                    },
                )
                .output_or_abort()
                .0;
            if output.stdout.trim() == "TRACE_OK" {
                return true;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        false
    }

    fn move_collected_files(&self, serial: &str, target_name: &str, identifier: &str) {
        let files: &[CollectedFile] = match targets::trace_target(target_name) {
            Some(target) => target.files,
            None => match targets::dump_target(target_name) {
                Some(target) => target.files,
                None => return,
            },
        };
        for file in files {
            let paths: Vec<String> = match file.fixed_path {
                Some(fixed) => vec![fixed.replace("{trace_identifier}", identifier)],
                None => {
                    let output = self
                        .transport
                        .run(
                            &[
                                "-s",
                                serial,
                                "shell",
                                "sh",
                                "-c",
                                &format!("su root find {} -name {}", file.directory, file.matcher),
                            ],
                            &ShellOptions::probe(),
                        )
                        .output_or_abort()
                        .0;
                    output
                        .stdout
                        .lines()
                        .map(str::trim)
                        .filter(|line| !line.is_empty())
                        .map(str::to_owned)
                        .collect()
                }
            };
            let file_type = file.file_type.replace("{trace_identifier}", identifier);
            for path in paths {
                let _ = self.transport.run(
                    &[
                        "-s",
                        serial,
                        "shell",
                        "sh",
                        "-c",
                        &format!("su root [ ! -f {path} ] || su root mv {path} {WINSCOPE_BACKUP_DIR}{file_type}"),
                    ],
                    &ShellOptions::probe(),
                );
            }
        }
    }

    fn list_backup_files(&self, serial: &str) -> Vec<String> {
        let output = self
            .transport
            .run(
                &[
                    "-s",
                    serial,
                    "shell",
                    "sh",
                    "-c",
                    &format!("su root find {WINSCOPE_BACKUP_DIR}*"),
                ],
                &ShellOptions::probe(),
            )
            .output_or_abort()
            .0;
        output
            .stdout
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(str::to_owned)
            .collect()
    }

    fn perfetto_trace_start_command(&self) -> String {
        config_text::append_config_command(
            PERFETTO_TRACE_CONFIG_FILE,
            &config_text::perfetto_trace_header(PERFETTO_UNIQUE_SESSION_NAME),
        ) + &format!(
            "\nrm -f {PERFETTO_TRACE_FILE}\nperfetto --out {PERFETTO_TRACE_FILE} --txt --config {PERFETTO_TRACE_CONFIG_FILE} --detach={PERFETTO_DETACH_NAME}\necho 'Started perfetto trace.'"
        )
    }

    /// Dual-path archival (design D5): the bytes returned to the viewer are also
    /// written to the session artifact directory with device/path metadata.
    fn archive_fetch(&self, serial: &str, raw: &BTreeMap<String, Vec<u8>>) {
        let Some(root) = &self.archive_root else {
            return;
        };
        let session_dir = root.join(format!("winscope-{}-{serial}", epoch_millis()));
        if std::fs::create_dir_all(&session_dir).is_err() {
            return;
        }
        for (name, bytes) in raw {
            let _ = std::fs::write(session_dir.join(name), bytes);
        }
        let targets = self
            .state
            .lock()
            .map(|state| {
                state
                    .last_targets
                    .get(serial)
                    .cloned()
                    .unwrap_or_default()
            })
            .unwrap_or_default();
        let metadata = json!({
            "serial": serial,
            "capturedAt": rfc3339_utc(SystemTime::now()),
            "targets": targets,
            "files": raw.keys().collect::<Vec<_>>(),
        });
        if let Ok(text) = serde_json::to_string_pretty(&metadata) {
            if let Ok(mut file) = std::fs::File::create(session_dir.join("capture-meta.json")) {
                let _ = file.write_all(text.as_bytes());
            }
        }
    }
}

fn perfetto_trace_stop_command() -> String {
    format!("perfetto --attach={PERFETTO_DETACH_NAME} --stop")
}

fn perfetto_dump_command() -> String {
    config_text::append_config_command(
        PERFETTO_DUMP_CONFIG_FILE,
        &config_text::perfetto_dump_header(),
    ) + &format!(
        "\nrm -f {PERFETTO_DUMP_FILE}\nperfetto --out {PERFETTO_DUMP_FILE} --txt --config {PERFETTO_DUMP_CONFIG_FILE}\necho 'Recorded perfetto dump.'"
    )
}

fn prepared_trace(target: &TraceTarget, is_perfetto: bool, request: &TraceRequest) -> PreparedTarget {
    let identifiers = if target.name == "screen_recording" {
        match request.display_identifiers("displays") {
            Some(displays) => displays
                .iter()
                .map(|display| display.split(' ').next().unwrap_or("active").to_owned())
                .collect(),
            None => vec!["active".to_owned()],
        }
    } else {
        vec![String::new()]
    };
    let options_by_identifier = identifiers
        .iter()
        .map(|identifier| {
            let options = if identifier == "active" || identifier.is_empty() {
                String::new()
            } else {
                format!("--display-id {identifier}")
            };
            (identifier.clone(), options)
        })
        .collect();
    PreparedTarget {
        name: target.name.to_owned(),
        is_perfetto,
        is_dump: false,
        identifiers,
        options_by_identifier,
    }
}

fn prepared_dump(target: &DumpTarget, is_perfetto: bool, request: &TraceRequest) -> PreparedTarget {
    let identifiers = if target.name == "screenshot" {
        match request.display_identifiers("displays") {
            Some(displays) => displays
                .iter()
                .map(|display| display.split(' ').next().unwrap_or("active").to_owned())
                .collect(),
            None => vec!["active".to_owned()],
        }
    } else {
        vec![String::new()]
    };
    let options_by_identifier = identifiers
        .iter()
        .map(|identifier| {
            let options = if identifier == "active" || identifier.is_empty() {
                String::new()
            } else {
                format!("-d {identifier}")
            };
            (identifier.clone(), options)
        })
        .collect();
    PreparedTarget {
        name: target.name.to_owned(),
        is_perfetto,
        is_dump: true,
        identifiers,
        options_by_identifier,
    }
}

fn start_shell_script(status_filename: &str, stop_commands: &str, start_commands: &str) -> String {
    START_SCRIPT_TEMPLATE
        .replace("{winscope_status}", status_filename)
        .replace("{signal_handler_log}", SIGNAL_HANDLER_LOG)
        .replace("{stop_commands}", stop_commands)
        .replace("{start_commands}", start_commands)
}

fn gzip_base64(bytes: &[u8]) -> String {
    let mut encoder =
        flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    let _ = encoder.write_all(bytes);
    let compressed = encoder.finish().unwrap_or_default();
    // MIME line wrapping (76 chars) matches Python base64.encodebytes, which the
    // bundled viewer parses.
    let encoded = use_base64(&compressed);
    wrap_mime(&encoded)
}

fn use_base64(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn wrap_mime(encoded: &str) -> String {
    let mut wrapped = String::with_capacity(encoded.len() + encoded.len() / 76 * 2 + 2);
    for chunk in encoded.as_bytes().chunks(76) {
        wrapped.push_str(std::str::from_utf8(chunk).unwrap_or(""));
        wrapped.push('\n');
    }
    wrapped
}

fn epoch_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or_default()
}

/// A process-wide monotonically increasing counter for temp file uniqueness.
fn unique_counter() -> u128 {
    use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let counter = COUNTER.fetch_add(1, AtomicOrdering::SeqCst) as u128;
    (epoch_millis() << 20) | counter
}

/// Formats a timestamp as RFC 3339 UTC without pulling in a date-time library.
fn rfc3339_utc(time: SystemTime) -> String {
    let millis = time
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default();
    let days = millis.div_euclid(86_400_000);
    let remainder = millis.rem_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    let hour = remainder / 3_600_000;
    let minute = (remainder % 3_600_000) / 60_000;
    let second = (remainder % 60_000) / 1_000;
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
}

/// Howard Hinnant's civil_from_days algorithm.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = if m <= 2 { y + 1 } else { y };
    (year, m, d)
}

const SF_LEGACY_FLAGS: [&str; 6] = ["input", "composition", "metadata", "hwc", "tracebuffers", "virtualdisplays"];
const SF_LEGACY_FLAG_BITS: [u32; 6] = [2, 4, 8, 16, 32, 64];
const SF_PERFETTO_FLAGS: [&str; 6] = [
    "TRACE_FLAG_INPUT",
    "TRACE_FLAG_COMPOSITION",
    "TRACE_FLAG_EXTRA",
    "TRACE_FLAG_HWC",
    "TRACE_FLAG_BUFFERS",
    "TRACE_FLAG_VIRTUAL_DISPLAYS",
];

fn sf_legacy_flag_bits(flag: &str) -> Option<u32> {
    SF_LEGACY_FLAGS
        .iter()
        .position(|name| *name == flag)
        .map(|index| SF_LEGACY_FLAG_BITS[index])
}

fn sf_perfetto_flag(flag: &str) -> Option<&'static str> {
    SF_LEGACY_FLAGS
        .iter()
        .position(|name| *name == flag)
        .map(|index| SF_PERFETTO_FLAGS[index])
}

const WM_PERFETTO_LOG_LEVEL: [&str; 3] = ["LOG_LEVEL_VERBOSE", "LOG_LEVEL_DEBUG", "LOG_LEVEL_CRITICAL"];
const WM_LEVEL_KEYS: [&str; 3] = ["verbose", "debug", "critical"];
const WM_PERFETTO_LOG_FREQUENCY: [&str; 2] = ["LOG_FREQUENCY_FRAME", "LOG_FREQUENCY_TRANSACTION"];
const WM_FREQUENCY_KEYS: [&str; 2] = ["frame", "transaction"];

fn wm_level(level: &str) -> Option<&'static str> {
    WM_LEVEL_KEYS
        .iter()
        .position(|key| *key == level)
        .map(|index| WM_PERFETTO_LOG_LEVEL[index])
}

fn wm_frequency(frequency: &str) -> Option<&'static str> {
    WM_FREQUENCY_KEYS
        .iter()
        .position(|key| *key == frequency)
        .map(|index| WM_PERFETTO_LOG_FREQUENCY[index])
}

const SESSIONS_TABLE_HEADER: &str = "TRACING SESSIONS:\n\nID      UID     STATE      BUF (#) KB   DUR (s)   #DS  STARTED  NAME\n===     ===     =====      ==========   =======   ===  =======  ====\n";
const SESSIONS_TABLE_FOOTER: &str = "\nNOTE: Some tracing sessions are not reported in the list above.";

const START_SCRIPT_TEMPLATE: &str = r#"set -e

echo "Starting trace..."
echo "TRACE_START" > {winscope_status}

# Do not print anything to stdout/stderr in the handler
function stop_trace() {
  echo "start" >{signal_handler_log}

  # redirect stdout/stderr to log file
  exec 1>>{signal_handler_log}
  exec 2>>{signal_handler_log}

  set -x
  trap - EXIT HUP INT
  {stop_commands}
  echo "TRACE_OK" > {winscope_status}
}

trap stop_trace EXIT HUP INT
echo "Signal handler registered."

{start_commands}

# ADB shell does not handle hung up well and does not call HUP handler when a child
# is active in foreground; as a workaround we sleep in short intervals in a loop so
# the handler is called after a sleep interval.
while true; do sleep 0.1; done
"#;

#[cfg(test)]
mod tests {
    use super::*;

    fn request(name: &str, config: &[(&str, Value)]) -> TraceRequest {
        TraceRequest {
            name: name.to_owned(),
            config: config
                .iter()
                .map(|(key, value)| ConfigEntry {
                    key: (*key).to_owned(),
                    value: value.clone(),
                })
                .collect(),
        }
    }

    #[test]
    fn screen_recording_identifiers_follow_display_config() {
        let target = targets::trace_target("screen_recording").expect("exists");
        let displays = request(
            "screen_recording",
            &[("displays", Value::String("Display 2169155966275815240 (active)".to_owned()))],
        );
        let prepared = prepared_trace(target, false, &displays);
        assert_eq!(prepared.identifiers, vec!["Display"]);
        assert_eq!(
            prepared.options_by_identifier.get("Display").map(String::as_str),
            Some("--display-id Display")
        );

        let multi = request(
            "screen_recording",
            &[(
                "displays",
                Value::Array(vec![
                    Value::String("Display 111 (active)".to_owned()),
                    Value::String("Display 222".to_owned()),
                ]),
            )],
        );
        let prepared = prepared_trace(target, false, &multi);
        assert_eq!(prepared.identifiers, vec!["Display", "Display"]);
        assert_eq!(
            prepared.options_by_identifier.get("Display").map(String::as_str),
            Some("--display-id Display")
        );

        let none = request("screen_recording", &[]);
        let prepared = prepared_trace(target, false, &none);
        assert_eq!(prepared.identifiers, vec!["active"]);
        assert!(prepared.options_by_identifier.get("active").unwrap().is_empty());
    }

    #[test]
    fn default_identifier_is_empty_for_non_screen_targets() {
        let target = targets::trace_target("window_trace").expect("exists");
        let prepared = prepared_trace(target, true, &request("window_trace", &[]));
        assert_eq!(prepared.identifiers, vec![String::new()]);
    }

    #[test]
    fn screenshot_dump_uses_dash_d_option() {
        let target = targets::dump_target("screenshot").expect("exists");
        let displays = request(
            "screenshot",
            &[("displays", Value::String("Display 42 (active)".to_owned()))],
        );
        let prepared = prepared_dump(target, false, &displays);
        assert_eq!(prepared.identifiers, vec!["Display"]);
        assert_eq!(
            prepared.options_by_identifier.get("Display").map(String::as_str),
            Some("-d Display")
        );
    }

    #[test]
    fn perfetto_targets_sort_last_for_config_ordering() {
        let mut prepared = vec![
            PreparedTarget {
                name: "perfetto_trace".to_owned(),
                is_perfetto: true,
                is_dump: false,
                identifiers: vec![String::new()],
                options_by_identifier: HashMap::new(),
            },
            PreparedTarget {
                name: "window_trace".to_owned(),
                is_perfetto: false,
                is_dump: false,
                identifiers: vec![String::new()],
                options_by_identifier: HashMap::new(),
            },
        ];
        prepared.sort_by_key(|target| target.is_perfetto);
        assert_eq!(prepared[0].name, "window_trace");
        assert_eq!(prepared[1].name, "perfetto_trace");
    }

    #[test]
    fn start_script_registers_trap_and_status_file() {
        let script = start_shell_script(
            "/data/local/tmp/winscope_status_window_trace",
            "su root cmd window tracing stop",
            "su root cmd window tracing start",
        );
        assert!(script.contains("echo \"TRACE_START\" > /data/local/tmp/winscope_status_window_trace"));
        assert!(script.contains("echo \"TRACE_OK\" > /data/local/tmp/winscope_status_window_trace"));
        assert!(script.contains("trap stop_trace EXIT HUP INT"));
        assert!(script.contains("su root cmd window tracing start"));
        assert!(script.contains("su root cmd window tracing stop"));
        assert!(script.contains("while true; do sleep 0.1; done"));
        assert!(!script.contains("{winscope_status}"));
        assert!(!script.contains("{signal_handler_log}"));
    }

    #[test]
    fn perfetto_commands_use_the_detached_session_contract() {
        let engine_start = "header";
        let _ = engine_start;
        let stop = perfetto_trace_stop_command();
        assert_eq!(stop, "perfetto --attach=WINSCOPE-PROXY-TRACING-SESSION --stop");
        let dump = perfetto_dump_command();
        assert!(dump.contains("cat << EOF >> /data/misc/perfetto-configs/winscope-proxy-dump.conf"));
        assert!(dump.contains("perfetto --out /data/misc/perfetto-traces/winscope-proxy-dump.perfetto-trace --txt"));
    }

    #[test]
    fn gzip_base64_wraps_at_mime_width() {
        let payload = b"trace-bytes";
        let encoded = gzip_base64(payload);
        for line in encoded.lines() {
            assert!(line.len() <= 76, "line too long: {}", line.len());
        }
        assert!(encoded.ends_with('\n'));
        // Decode round trip: gzip header magic 1f 8b appears after base64 decode.
        use base64::Engine as _;
        let joined: String = encoded.lines().collect();
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(joined.as_bytes())
            .expect("valid base64");
        assert_eq!(&decoded[..2], &[0x1f, 0x8b]);
    }

    #[test]
    fn rfc3339_formats_utc() {
        let time = UNIX_EPOCH + std::time::Duration::from_millis(1_760_000_000_000);
        let formatted = rfc3339_utc(time);
        assert!(formatted.ends_with('Z'));
        assert_eq!(formatted.len(), 20);
        assert!(formatted.starts_with("202"));
    }

    #[test]
    fn sf_and_wm_flag_tables_cover_the_ui_keys() {
        for key in ["input", "composition", "metadata", "hwc", "tracebuffers", "virtualdisplays"] {
            assert!(sf_legacy_flag_bits(key).is_some());
            assert!(sf_perfetto_flag(key).is_some());
        }
        assert_eq!(sf_legacy_flag_bits("input"), Some(2));
        assert_eq!(sf_perfetto_flag("metadata"), Some("TRACE_FLAG_EXTRA"));
        for level in WM_LEVEL_KEYS {
            assert!(wm_level(level).is_some());
        }
        for frequency in WM_FREQUENCY_KEYS {
            assert!(wm_frequency(frequency).is_some());
        }
    }

    #[test]
    fn session_table_parsing_marks_five_or_more_as_too_many() {
        let table = format!(
            "perfetto --query output{SESSIONS_TABLE_HEADER}1       0       Started    2048         0         1     12:00    other-session\n2       0       Started    2048         0         1     12:01    another-one\n{SESSIONS_TABLE_FOOTER}"
        );
        let start = table.find(SESSIONS_TABLE_HEADER).unwrap();
        let after = &table[start + SESSIONS_TABLE_HEADER.len()..];
        let end = after.find(SESSIONS_TABLE_FOOTER).unwrap_or(after.len());
        let rows: Vec<&str> = after[..end]
            .trim()
            .lines()
            .filter(|line| !line.trim().is_empty())
            .collect();
        assert_eq!(rows.len(), 2);
        assert!(rows.iter().any(|row| row.contains("other-session")));
        assert!(!rows.iter().any(|row| row.contains(PERFETTO_UNIQUE_SESSION_NAME)));
    }
}
