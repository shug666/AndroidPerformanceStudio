//! Perfetto config text fragments appended to the device config file for the
//! modern path, ported verbatim from `winscope_proxy.py` config classes. Text
//! content is part of the device contract; see `docs/winscope-proxy-protocol.md`
//! §3.3.

fn data_source(name: &str, body: &str) -> String {
    let mut text = String::from("data_sources: {\n");
    text.push_str("    config {\n");
    text.push_str(&format!("        name: \"{name}\"\n"));
    if !body.is_empty() {
        text.push_str("        ");
        text.push_str(body);
        text.push('\n');
    }
    text.push_str("    }\n");
    text.push_str("}\n");
    text
}

/// SurfaceFlinger layers source. The dump variant pins the full flag set and
/// `MODE_DUMP`; the active variant carries the UI-selected flags.
pub fn surface_flinger_layers(flags: &[&str], is_dump: bool) -> String {
    let mode = if is_dump { "MODE_DUMP" } else { "MODE_ACTIVE" };
    let mut body = String::new();
    body.push_str("surfaceflinger_layers_config: {\n");
    body.push_str(&format!("    mode: {mode}\n"));
    if is_dump {
        for flag in [
            "TRACE_FLAG_INPUT",
            "TRACE_FLAG_COMPOSITION",
            "TRACE_FLAG_HWC",
            "TRACE_FLAG_BUFFERS",
            "TRACE_FLAG_VIRTUAL_DISPLAYS",
        ] {
            body.push_str(&format!("    trace_flags: {flag}\n"));
        }
    } else {
        for flag in flags {
            body.push_str(&format!("    trace_flags: {flag}\n"));
        }
    }
    body.push('}');
    data_source("android.surfaceflinger.layers", &body)
}

pub fn window_manager(log_level: &str, log_frequency: &str) -> String {
    let body = format!(
        "windowmanager_config: {{\n    log_level: {log_level}\n    log_frequency: {log_frequency}\n}}"
    );
    data_source("android.windowmanager", &body)
}

pub fn transactions() -> String {
    data_source(
        "android.surfaceflinger.transactions",
        "surfaceflinger_transactions_config: {\n    mode: MODE_ACTIVE\n}",
    )
}

pub fn proto_log() -> String {
    data_source("android.protolog", "protolog_config: {\n    tracing_mode: ENABLE_ALL\n}")
}

pub fn ime() -> String {
    data_source("android.inputmethod", "")
}

pub fn view_capture() -> String {
    data_source("android.viewcapture", "")
}

pub fn transitions() -> String {
    data_source("com.android.wm.shell.transition", "")
}

pub fn input() -> String {
    data_source(
        "android.input.inputevent",
        "android_input_event_config {\n    mode: TRACE_MODE_TRACE_ALL\n}",
    )
}

/// Buffers/session header appended by the perfetto_trace target before starting
/// perfetto.
pub fn perfetto_trace_header(unique_session_name: &str) -> String {
    let mut text = String::new();
    text.push_str("buffers: {\n");
    text.push_str("    size_kb: 500000\n");
    text.push_str("    fill_policy: RING_BUFFER\n");
    text.push_str("}\n");
    text.push_str("duration_ms: 0\n");
    text.push_str("file_write_period_ms: 999999999\n");
    text.push_str("write_into_file: true\n");
    text.push_str(&format!("unique_session_name: \"{unique_session_name}\"\n"));
    text
}

pub fn perfetto_dump_header() -> String {
    let mut text = String::new();
    text.push_str("buffers: {\n");
    text.push_str("    size_kb: 500000\n");
    text.push_str("    fill_policy: RING_BUFFER\n");
    text.push_str("}\n");
    text.push_str("duration_ms: 1\n");
    text
}

/// Builds the shell command appending `text` to the device config file
/// (heredoc, like proxy.py).
pub fn append_config_command(config_file: &str, text: &str) -> String {
    format!("cat << EOF >> {config_file}\n{}\nEOF\n", text.trim_end_matches('\n'))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::targets;

    #[test]
    fn layers_config_lists_selected_flags_in_order() {
        let text = surface_flinger_layers(&["TRACE_FLAG_INPUT", "TRACE_FLAG_HWC"], false);
        assert!(text.contains("data_sources: {\n"));
        assert!(text.contains("name: \"android.surfaceflinger.layers\"\n"));
        assert!(text.contains("mode: MODE_ACTIVE\n"));
        assert!(text.contains("    trace_flags: TRACE_FLAG_INPUT\n"));
        assert!(text.contains("    trace_flags: TRACE_FLAG_HWC\n"));
        assert!(!text.contains("TRACE_FLAG_BUFFERS"));
    }

    #[test]
    fn layers_dump_config_pins_mode_and_flags() {
        let text = surface_flinger_layers(&[], true);
        assert!(text.contains("mode: MODE_DUMP\n"));
        assert!(text.contains("trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS\n"));
    }

    #[test]
    fn window_manager_config_carries_level_and_frequency() {
        let text = window_manager("LOG_LEVEL_VERBOSE", "LOG_FREQUENCY_TRANSACTION");
        assert!(text.contains("log_level: LOG_LEVEL_VERBOSE\n"));
        assert!(text.contains("log_frequency: LOG_FREQUENCY_TRANSACTION\n"));
    }

    #[test]
    fn trace_header_matches_reference_values() {
        let text = perfetto_trace_header(targets::PERFETTO_UNIQUE_SESSION_NAME);
        assert!(text.contains("size_kb: 500000\n"));
        assert!(text.contains("fill_policy: RING_BUFFER\n"));
        assert!(text.contains("duration_ms: 0\n"));
        assert!(text.contains("file_write_period_ms: 999999999\n"));
        assert!(text.contains("write_into_file: true\n"));
        assert!(text.contains("unique_session_name: \"winscope proxy perfetto tracing\"\n"));
    }

    #[test]
    fn dump_header_uses_one_millisecond_duration() {
        let text = perfetto_dump_header();
        assert!(text.contains("duration_ms: 1\n"));
        assert!(!text.contains("write_into_file"));
    }

    #[test]
    fn append_command_writes_heredoc_with_single_trailing_eof() {
        let command = append_config_command("/data/misc/perfetto-configs/conf", "a: 1\n\n");
        assert_eq!(command, "cat << EOF >> /data/misc/perfetto-configs/conf\na: 1\nEOF\n");
    }
}
