//! Device-side trace target registry, ported from AOSP `winscope_proxy.py`
//! (v4.0.8). Command text, paths, and evaluation order are part of the wire
//! contract with the bundled Winscope viewer (it decodes fetched files in
//! `TRACE_TARGETS` declaration order).
//!
//! Source of truth: `docs/winscope-proxy-protocol.md` §3.1/§4; any deviation must
//! be reflected there first.

/// Response header advertised by the proxy; the viewer checks major equality and
/// (minor greater or equal patch).
pub const PROXY_VERSION: &str = "4.0.8";

pub const WINSCOPE_DIR: &str = "/data/misc/wmtrace/";
pub const WINSCOPE_BACKUP_DIR: &str = "/data/local/tmp/last_winscope_tracing_session/";
pub const SIGNAL_HANDLER_LOG: &str = "/data/local/tmp/winscope_signal_handler.log";
pub const WINSCOPE_STATUS_PREFIX: &str = "/data/local/tmp/winscope_status";
pub const PERFETTO_TRACE_CONFIG_FILE: &str =
    "/data/misc/perfetto-configs/winscope-proxy-trace.conf";
pub const PERFETTO_DUMP_CONFIG_FILE: &str = "/data/misc/perfetto-configs/winscope-proxy-dump.conf";
pub const PERFETTO_TRACE_FILE: &str =
    "/data/misc/perfetto-traces/winscope-proxy-trace.perfetto-trace";
pub const PERFETTO_DUMP_FILE: &str = "/data/misc/perfetto-traces/winscope-proxy-dump.perfetto-trace";
pub const PERFETTO_UNIQUE_SESSION_NAME: &str = "winscope proxy perfetto tracing";
pub const PERFETTO_DETACH_NAME: &str = "WINSCOPE-PROXY-TRACING-SESSION";

pub const KEEP_ALIVE_INTERVAL_SECONDS: u64 = 5;
pub const COMMAND_TIMEOUT_SECONDS: u64 = 15;

/// Data source names checked by the modern-path probe (matches proxy.py order).
pub const PROBED_DATA_SOURCES: [&str; 8] = [
    "android.inputmethod",
    "android.protolog",
    "android.surfaceflinger.layers",
    "android.surfaceflinger.transactions",
    "com.android.wm.shell.transition",
    "android.viewcapture",
    "android.windowmanager",
    "android.input.inputevent",
];

/// Files collected after a capture ends; `fixed_path: Some` means one known path
/// (`{trace_identifier}` substituted), `None` means `su root find directory -name
/// matcher`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollectedFile {
    pub fixed_path: Option<&'static str>,
    pub directory: &'static str,
    pub matcher: &'static str,
    pub file_type: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceTarget {
    pub name: &'static str,
    /// Perfetto data source name gating the modern path; `None` = never modern;
    /// `"*"` = available when any winscope data source probes true.
    pub perfetto_data_source: Option<&'static str>,
    pub legacy_start: &'static str,
    pub legacy_stop: &'static str,
    pub files: &'static [CollectedFile],
    pub has_optional_config: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DumpTarget {
    pub name: &'static str,
    pub perfetto_data_source: Option<&'static str>,
    pub dump_command: &'static str,
    pub files: &'static [CollectedFile],
    pub has_optional_config: bool,
}

const fn fixed_file(path: &'static str, file_type: &'static str) -> CollectedFile {
    CollectedFile {
        fixed_path: Some(path),
        directory: "",
        matcher: "",
        file_type,
    }
}

// `const fn` cannot format strings, so the `.winscope`/`.pb` matcher pairs are
// spelled out as literal tables below.
const VIEW_CAPTURE_FILES: [CollectedFile; 1] =
    [fixed_file("/data/misc/wmtrace/view_capture_trace.zip", "view_capture_trace.zip")];
const WM_TRACE_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_trace.winscope", file_type: "window_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_trace.pb", file_type: "window_trace" },
];
const LAYERS_TRACE_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "layers_trace.winscope", file_type: "layers_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "layers_trace.pb", file_type: "layers_trace" },
];
const SCREEN_RECORDING_FILES: [CollectedFile; 1] = [fixed_file(
    "/data/local/tmp/screen_{trace_identifier}.mp4",
    "screen_recording_{trace_identifier}",
)];
const TRANSACTIONS_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "transactions_trace.winscope", file_type: "transactions" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "transactions_trace.pb", file_type: "transactions" },
];
const TRANSACTIONS_LEGACY_FILES: [CollectedFile; 3] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "transaction_trace.winscope", file_type: "transactions_legacy" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "transaction_trace.pb", file_type: "transactions_legacy" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "transaction_merges_*", file_type: "transaction_merges" },
];
const PROTO_LOG_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_log.winscope", file_type: "proto_log" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_log.pb", file_type: "proto_log" },
];
const IME_FILES: [CollectedFile; 6] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_clients.winscope", file_type: "ime_trace_clients" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_clients.pb", file_type: "ime_trace_clients" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_service.winscope", file_type: "ime_trace_service" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_service.pb", file_type: "ime_trace_service" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_managerservice.winscope", file_type: "ime_trace_managerservice" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "ime_trace_managerservice.pb", file_type: "ime_trace_managerservice" },
];
const WAYLAND_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: "/data/misc/wltrace", matcher: "wl_trace.winscope", file_type: "wl_trace" },
    CollectedFile { fixed_path: None, directory: "/data/misc/wltrace", matcher: "wl_trace.pb", file_type: "wl_trace" },
];
const EVENTLOG_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: "/data/local/tmp", matcher: "eventlog.winscope", file_type: "eventlog" },
    CollectedFile { fixed_path: None, directory: "/data/local/tmp", matcher: "eventlog.pb", file_type: "eventlog" },
];
const TRANSITION_FILES: [CollectedFile; 4] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_transition_trace.winscope", file_type: "wm_transition_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "wm_transition_trace.pb", file_type: "wm_transition_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "shell_transition_trace.winscope", file_type: "shell_transition_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "shell_transition_trace.pb", file_type: "shell_transition_trace" },
];
const INPUT_FILES: [CollectedFile; 2] = [
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "input_trace.winscope", file_type: "input_trace" },
    CollectedFile { fixed_path: None, directory: WINSCOPE_DIR, matcher: "input_trace.pb", file_type: "input_trace" },
];
const PERFETTO_TRACE_FILES: [CollectedFile; 1] =
    [fixed_file(PERFETTO_TRACE_FILE, "trace.perfetto-trace")];

#[allow(clippy::needless_raw_string_lengths)]
pub const TRACE_TARGETS: &[TraceTarget] = &[
    TraceTarget {
        name: "view_capture_trace",
        perfetto_data_source: Some("android.viewcapture"),
        legacy_start: "su root settings put global view_capture_enabled 1\necho 'ViewCapture tracing (legacy) started.'",
        legacy_stop: "su root sh -c 'cmd launcherapps dump-view-hierarchies >/data/misc/wmtrace/view_capture_trace.zip'\nsu root settings put global view_capture_enabled 0\necho 'ViewCapture tracing (legacy) stopped.'",
        files: &VIEW_CAPTURE_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "window_trace",
        perfetto_data_source: Some("android.windowmanager"),
        legacy_start: "su root cmd window tracing start",
        legacy_stop: "su root cmd window tracing stop >/dev/null 2>&1",
        files: &WM_TRACE_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "layers_trace",
        perfetto_data_source: Some("android.surfaceflinger.layers"),
        legacy_start: "su root service call SurfaceFlinger 1025 i32 1",
        legacy_stop: "su root service call SurfaceFlinger 1025 i32 0 >/dev/null 2>&1",
        files: &LAYERS_TRACE_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "screen_recording",
        perfetto_data_source: None,
        legacy_start: "settings put system show_touches 1 && \\\nsettings put system pointer_location 1 && \\\nscreenrecord --bugreport --bit-rate 8M {options} /data/local/tmp/screen_{trace_identifier}.mp4 & \\\necho \"ScreenRecorder started.\"",
        legacy_stop: "settings put system pointer_location 0 && \\\nsettings put system show_touches 0 && \\\npkill -l SIGINT screenrecord >/dev/null 2>&1",
        files: &SCREEN_RECORDING_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "transactions",
        perfetto_data_source: Some("android.surfaceflinger.transactions"),
        legacy_start: "su root service call SurfaceFlinger 1041 i32 1",
        legacy_stop: "su root service call SurfaceFlinger 1041 i32 0 >/dev/null 2>&1",
        files: &TRANSACTIONS_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "transactions_legacy",
        perfetto_data_source: None,
        legacy_start: "su root service call SurfaceFlinger 1020 i32 1",
        legacy_stop: "su root service call SurfaceFlinger 1020 i32 0 >/dev/null 2>&1",
        files: &TRANSACTIONS_LEGACY_FILES,
        has_optional_config: false,
    },
    TraceTarget {
        name: "proto_log",
        perfetto_data_source: Some("android.protolog"),
        legacy_start: "su root cmd window logging start",
        legacy_stop: "su root cmd window logging stop >/dev/null 2>&1",
        files: &PROTO_LOG_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "ime",
        perfetto_data_source: Some("android.inputmethod"),
        legacy_start: "su root ime tracing start",
        legacy_stop: "su root ime tracing stop >/dev/null 2>&1",
        files: &IME_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "wayland_trace",
        perfetto_data_source: None,
        legacy_start: "su root service call Wayland 26 i32 1 >/dev/null",
        legacy_stop: "su root service call Wayland 26 i32 0 >/dev/null",
        files: &WAYLAND_FILES,
        has_optional_config: false,
    },
    TraceTarget {
        name: "eventlog",
        perfetto_data_source: None,
        legacy_start: "rm -f /data/local/tmp/eventlog.winscope && EVENT_LOG_TRACING_START_TIME=$EPOCHREALTIME",
        legacy_stop: "echo \"EventLog\\n\" > /data/local/tmp/eventlog.winscope && su root logcat -b events -v threadtime -v printable -v uid -v nsec -v epoch -b events -t $EVENT_LOG_TRACING_START_TIME >> /data/local/tmp/eventlog.winscope",
        files: &EVENTLOG_FILES,
        has_optional_config: false,
    },
    TraceTarget {
        name: "transition_traces",
        perfetto_data_source: Some("com.android.wm.shell.transition"),
        legacy_start: "su root cmd window shell tracing start && su root dumpsys activity service SystemUIService WMShell transitions tracing start",
        legacy_stop: "su root cmd window shell tracing stop && su root dumpsys activity service SystemUIService WMShell transitions tracing stop >/dev/null 2>&1",
        files: &TRANSITION_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "input",
        perfetto_data_source: Some("android.input.inputevent"),
        legacy_start: "",
        legacy_stop: "",
        files: &INPUT_FILES,
        has_optional_config: true,
    },
    TraceTarget {
        name: "perfetto_trace",
        perfetto_data_source: Some("*"),
        legacy_start: "",
        legacy_stop: "",
        files: &PERFETTO_TRACE_FILES,
        has_optional_config: false,
    },
];

const WINDOW_DUMP_FILES: [CollectedFile; 1] =
    [fixed_file("/data/local/tmp/wm_dump.winscope", "window_dump")];
const LAYERS_DUMP_FILES: [CollectedFile; 1] =
    [fixed_file("/data/local/tmp/sf_dump.winscope", "layers_dump")];
const SCREENSHOT_FILES: [CollectedFile; 1] = [fixed_file(
    "/data/local/tmp/screenshot_{trace_identifier}.png",
    "screenshot_{trace_identifier}.png",
)];
const PERFETTO_DUMP_FILES: [CollectedFile; 1] =
    [fixed_file(PERFETTO_DUMP_FILE, "dump.perfetto-trace")];

pub const DUMP_TARGETS: &[DumpTarget] = &[
    DumpTarget {
        name: "window_dump",
        perfetto_data_source: None,
        dump_command: "su root dumpsys window --proto > /data/local/tmp/wm_dump.winscope",
        files: &WINDOW_DUMP_FILES,
        has_optional_config: false,
    },
    DumpTarget {
        name: "layers_dump",
        perfetto_data_source: Some("android.surfaceflinger.layers"),
        dump_command: "su root dumpsys SurfaceFlinger --proto > /data/local/tmp/sf_dump.winscope",
        files: &LAYERS_DUMP_FILES,
        has_optional_config: true,
    },
    DumpTarget {
        name: "screenshot",
        perfetto_data_source: None,
        dump_command: "screencap -p {options}> /data/local/tmp/screenshot_{trace_identifier}.png",
        files: &SCREENSHOT_FILES,
        has_optional_config: true,
    },
    DumpTarget {
        name: "perfetto_dump",
        perfetto_data_source: Some("*"),
        dump_command: "",
        files: &PERFETTO_DUMP_FILES,
        has_optional_config: false,
    },
];

/// Whether the trace target can take the modern perfetto path given the probe
/// result text from `perfetto --query`.
pub fn is_perfetto_available(target_name: &str, probe_result: &str) -> bool {
    let Some(target) = trace_target(target_name) else {
        return false;
    };
    data_source_available(target.perfetto_data_source, probe_result)
}

/// Same evaluation for dump targets.
pub fn is_dump_perfetto_available(target_name: &str, probe_result: &str) -> bool {
    let Some(target) = dump_target(target_name) else {
        return false;
    };
    data_source_available(target.perfetto_data_source, probe_result)
}

fn data_source_available(data_source: Option<&str>, probe_result: &str) -> bool {
    match data_source {
        None => false,
        Some("*") => PROBED_DATA_SOURCES.iter().any(|name| probe_result.contains(name)),
        Some(name) => probe_result.contains(name),
    }
}

/// Looks up a trace target by name.
pub fn trace_target(name: &str) -> Option<&'static TraceTarget> {
    TRACE_TARGETS.iter().find(|target| target.name == name)
}

/// Looks up a dump target by name.
pub fn dump_target(name: &str) -> Option<&'static DumpTarget> {
    DUMP_TARGETS.iter().find(|target| target.name == name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn declaration_order_matches_the_wire_contract() {
        let names: Vec<_> = TRACE_TARGETS.iter().map(|target| target.name).collect();
        assert_eq!(
            names,
            [
                "view_capture_trace",
                "window_trace",
                "layers_trace",
                "screen_recording",
                "transactions",
                "transactions_legacy",
                "proto_log",
                "ime",
                "wayland_trace",
                "eventlog",
                "transition_traces",
                "input",
                "perfetto_trace",
            ]
        );
    }

    #[test]
    fn legacy_winscope_files_carry_both_suffixes() {
        let window = trace_target("window_trace").expect("window_trace exists");
        assert_eq!(window.files.len(), 2);
        assert_eq!(window.files[0].matcher, "wm_trace.winscope");
        assert_eq!(window.files[1].matcher, "wm_trace.pb");
        assert_eq!(window.files[0].directory, WINSCOPE_DIR);
    }

    #[test]
    fn ime_and_transitions_collect_multi_matcher_files() {
        assert_eq!(trace_target("ime").expect("ime").files.len(), 6);
        assert_eq!(
            trace_target("transactions_legacy")
                .expect("transactions_legacy")
                .files
                .len(),
            3
        );
    }

    #[test]
    fn perfetto_availability_follows_the_probe_result() {
        let probe = "data_sources: [\n    android.surfaceflinger.layers\n    android.windowmanager\n]";
        assert!(is_perfetto_available("window_trace", probe));
        assert!(is_perfetto_available("layers_trace", probe));
        assert!(!is_perfetto_available("transactions", probe));
        assert!(!is_perfetto_available("screen_recording", probe));
        assert!(is_perfetto_available("perfetto_trace", probe));
        assert!(!is_perfetto_available("perfetto_trace", "no data sources"));
    }

    #[test]
    fn dump_perfetto_availability_matches_trace_rules() {
        let probe = "android.surfaceflinger.layers";
        assert!(is_dump_perfetto_available("layers_dump", probe));
        assert!(is_dump_perfetto_available("perfetto_dump", probe));
        assert!(!is_dump_perfetto_available("window_dump", probe));
    }

    #[test]
    fn unknown_targets_are_never_perfetto_capable() {
        assert!(!is_perfetto_available("missing", "android.windowmanager"));
        assert!(!is_dump_perfetto_available("missing", "android.windowmanager"));
        assert!(trace_target("missing").is_none());
    }
}
