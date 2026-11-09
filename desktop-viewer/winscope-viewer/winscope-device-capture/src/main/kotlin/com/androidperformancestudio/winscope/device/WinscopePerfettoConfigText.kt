@file:Suppress("TooManyFunctions", "MaxLineLength")

package com.androidperformancestudio.winscope.device

/**
 * Perfetto config text fragments appended to the device config file for the modern
 * path, ported verbatim from `winscope_proxy.py` config classes. Text content is part
 * of the device contract; see `docs/winscope-proxy-protocol.md` §3.3.
 */
public object WinscopePerfettoConfigText {
    private fun dataSource(
        name: String,
        body: String,
    ): String =
        """
        data_sources: {
            config {
                name: "$name"
                $body
            }
        }
        """.trimIndent() + "\n"

    public fun surfaceFlingerLayers(
        flags: List<String>,
        isDump: Boolean = false,
    ): String {
        val mode = if (isDump) "MODE_DUMP" else "MODE_ACTIVE"
        val body =
            buildString {
                append("surfaceflinger_layers_config: {\n")
                append("    mode: $mode\n")
                if (isDump) {
                    append("    trace_flags: TRACE_FLAG_INPUT\n")
                    append("    trace_flags: TRACE_FLAG_COMPOSITION\n")
                    append("    trace_flags: TRACE_FLAG_HWC\n")
                    append("    trace_flags: TRACE_FLAG_BUFFERS\n")
                    append("    trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS\n")
                } else {
                    flags.forEach { append("    trace_flags: $it\n") }
                }
                append("}")
            }
        return dataSource("android.surfaceflinger.layers", body)
    }

    public fun windowManager(
        logLevel: String,
        logFrequency: String,
    ): String =
        dataSource(
            "android.windowmanager",
            "windowmanager_config: {\n" +
                "    log_level: $logLevel\n" +
                "    log_frequency: $logFrequency\n" +
                "}",
        )

    public fun transactions(): String =
        dataSource(
            "android.surfaceflinger.transactions",
            "surfaceflinger_transactions_config: {\n    mode: MODE_ACTIVE\n}",
        )

    public fun protoLog(): String = dataSource("android.protolog", "protolog_config: {\n    tracing_mode: ENABLE_ALL\n}")

    public fun ime(): String = dataSource("android.inputmethod", "")

    public fun viewCapture(): String = dataSource("android.viewcapture", "")

    public fun transitions(): String = dataSource("com.android.wm.shell.transition", "")

    public fun input(): String = dataSource("android.input.inputevent", "android_input_event_config {\n    mode: TRACE_MODE_TRACE_ALL\n}")

    /** Buffers/session header appended by the perfetto_trace target before starting perfetto. */
    public fun perfettoTraceHeader(uniqueSessionName: String): String =
        """
        buffers: {
            size_kb: 500000
            fill_policy: RING_BUFFER
        }
        duration_ms: 0
        file_write_period_ms: 999999999
        write_into_file: true
        unique_session_name: "$uniqueSessionName"
        """.trimIndent() + "\n"

    public fun perfettoDumpHeader(): String =
        """
        buffers: {
            size_kb: 500000
            fill_policy: RING_BUFFER
        }
        duration_ms: 1
        """.trimIndent() + "\n"

    /** Builds the shell command appending [text] to the device config file (heredoc, like proxy.py). */
    public fun appendConfigCommand(
        configFile: String,
        text: String,
    ): String = "cat << EOF >> $configFile\n" + text.trimEnd('\n') + "\nEOF\n"
}
