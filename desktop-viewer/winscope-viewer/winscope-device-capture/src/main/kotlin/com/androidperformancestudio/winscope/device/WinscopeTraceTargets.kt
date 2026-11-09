@file:Suppress("LargeClass", "LongMethod", "MaxLineLength")

package com.androidperformancestudio.winscope.device

/**
 * Device-side trace target registry, ported from AOSP `winscope_proxy.py` (v4.0.8).
 * Command text, paths, and evaluation order are part of the wire contract with the
 * bundled Winscope viewer (it decodes fetched files in TRACE_TARGETS declaration order).
 *
 * Source of truth: `docs/winscope-proxy-protocol.md` §3.1/§4; any deviation must be
 * reflected there first.
 */
public object WinscopeTraceTargets {
    public const val WINSCOPE_DIR: String = "/data/misc/wmtrace/"
    public const val WINSCOPE_BACKUP_DIR: String = "/data/local/tmp/last_winscope_tracing_session/"
    public const val SIGNAL_HANDLER_LOG: String = "/data/local/tmp/winscope_signal_handler.log"
    public const val WINSCOPE_STATUS: String = "/data/local/tmp/winscope_status"
    public const val PERFETTO_TRACE_CONFIG_FILE: String = "/data/misc/perfetto-configs/winscope-proxy-trace.conf"
    public const val PERFETTO_DUMP_CONFIG_FILE: String = "/data/misc/perfetto-configs/winscope-proxy-dump.conf"
    public const val PERFETTO_TRACE_FILE: String = "/data/misc/perfetto-traces/winscope-proxy-trace.perfetto-trace"
    public const val PERFETTO_DUMP_FILE: String = "/data/misc/perfetto-traces/winscope-proxy-dump.perfetto-trace"
    public const val PERFETTO_UNIQUE_SESSION_NAME: String = "winscope proxy perfetto tracing"
    public const val PERFETTO_DETACH_NAME: String = "WINSCOPE-PROXY-TRACING-SESSION"

    public const val KEEP_ALIVE_INTERVAL_SECONDS: Long = 5
    public const val COMMAND_TIMEOUT_SECONDS: Long = 15

    /** Data source names checked by the modern-path probe (matches proxy.py order). */
    public val PROBED_DATA_SOURCES: List<String> =
        listOf(
            "android.inputmethod",
            "android.protolog",
            "android.surfaceflinger.layers",
            "android.surfaceflinger.transactions",
            "com.android.wm.shell.transition",
            "android.viewcapture",
            "android.windowmanager",
            "android.input.inputevent",
        )

    /** Files collected after a capture ends; [matcher] null means a fixed path. */
    public data class CollectedFile(
        val fixedPath: String?,
        val directory: String,
        val matcher: String,
        val fileType: String,
    )

    public data class TraceTarget(
        val name: String,
        /** Perfetto data source name gating the modern path; null = never modern. */
        val perfettoDataSource: String?,
        val legacyStart: String,
        val legacyStop: String,
        val files: List<CollectedFile>,
        val hasOptionalConfig: Boolean,
    )

    public data class DumpTarget(
        val name: String,
        val perfettoDataSource: String?,
        val dumpCommand: String,
        val files: List<CollectedFile>,
        val hasOptionalConfig: Boolean,
    )

    private fun winscopeMatcher(
        directory: String,
        matcher: String,
        fileType: String,
    ): List<CollectedFile> =
        listOf(
            CollectedFile(fixedPath = null, directory = directory, matcher = "$matcher.winscope", fileType = fileType),
            CollectedFile(fixedPath = null, directory = directory, matcher = "$matcher.pb", fileType = fileType),
        )

    public val TRACE_TARGETS: Map<String, TraceTarget> =
        listOf(
            TraceTarget(
                name = "view_capture_trace",
                perfettoDataSource = "android.viewcapture",
                legacyStart =
                    """
                    su root settings put global view_capture_enabled 1
                    echo 'ViewCapture tracing (legacy) started.'
                    """.trimIndent(),
                legacyStop =
                    """
                    su root sh -c 'cmd launcherapps dump-view-hierarchies >/data/misc/wmtrace/view_capture_trace.zip'
                    su root settings put global view_capture_enabled 0
                    echo 'ViewCapture tracing (legacy) stopped.'
                    """.trimIndent(),
                files = listOf(CollectedFile("/data/misc/wmtrace/view_capture_trace.zip", "", "", "view_capture_trace.zip")),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "window_trace",
                perfettoDataSource = "android.windowmanager",
                legacyStart = "su root cmd window tracing start",
                legacyStop = "su root cmd window tracing stop >/dev/null 2>&1",
                files = winscopeMatcher(WINSCOPE_DIR, "wm_trace", "window_trace"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "layers_trace",
                perfettoDataSource = "android.surfaceflinger.layers",
                legacyStart = "su root service call SurfaceFlinger 1025 i32 1",
                legacyStop = "su root service call SurfaceFlinger 1025 i32 0 >/dev/null 2>&1",
                files = winscopeMatcher(WINSCOPE_DIR, "layers_trace", "layers_trace"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "screen_recording",
                perfettoDataSource = null,
                legacyStart =
                    """
                    settings put system show_touches 1 && \
                    settings put system pointer_location 1 && \
                    screenrecord --bugreport --bit-rate 8M {options} /data/local/tmp/screen_{trace_identifier}.mp4 & \
                    echo "ScreenRecorder started."
                    """.trimIndent(),
                legacyStop =
                    """
                    settings put system pointer_location 0 && \
                    settings put system show_touches 0 && \
                    pkill -l SIGINT screenrecord >/dev/null 2>&1
                    """.trimIndent(),
                files =
                    listOf(
                        CollectedFile("/data/local/tmp/screen_{trace_identifier}.mp4", "", "", "screen_recording_{trace_identifier}"),
                    ),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "transactions",
                perfettoDataSource = "android.surfaceflinger.transactions",
                legacyStart = "su root service call SurfaceFlinger 1041 i32 1",
                legacyStop = "su root service call SurfaceFlinger 1041 i32 0 >/dev/null 2>&1",
                files = winscopeMatcher(WINSCOPE_DIR, "transactions_trace", "transactions"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "transactions_legacy",
                perfettoDataSource = null,
                legacyStart = "su root service call SurfaceFlinger 1020 i32 1",
                legacyStop = "su root service call SurfaceFlinger 1020 i32 0 >/dev/null 2>&1",
                files =
                    winscopeMatcher(WINSCOPE_DIR, "transaction_trace", "transactions_legacy") +
                        listOf(
                            CollectedFile(null, WINSCOPE_DIR, "transaction_merges_*", "transaction_merges"),
                        ),
                hasOptionalConfig = false,
            ),
            TraceTarget(
                name = "proto_log",
                perfettoDataSource = "android.protolog",
                legacyStart = "su root cmd window logging start",
                legacyStop = "su root cmd window logging stop >/dev/null 2>&1",
                files = winscopeMatcher(WINSCOPE_DIR, "wm_log", "proto_log"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "ime",
                perfettoDataSource = "android.inputmethod",
                legacyStart = "su root ime tracing start",
                legacyStop = "su root ime tracing stop >/dev/null 2>&1",
                files =
                    winscopeMatcher(WINSCOPE_DIR, "ime_trace_clients", "ime_trace_clients") +
                        winscopeMatcher(WINSCOPE_DIR, "ime_trace_service", "ime_trace_service") +
                        winscopeMatcher(WINSCOPE_DIR, "ime_trace_managerservice", "ime_trace_managerservice"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "wayland_trace",
                perfettoDataSource = null,
                legacyStart = "su root service call Wayland 26 i32 1 >/dev/null",
                legacyStop = "su root service call Wayland 26 i32 0 >/dev/null",
                files = winscopeMatcher("/data/misc/wltrace", "wl_trace", "wl_trace"),
                hasOptionalConfig = false,
            ),
            TraceTarget(
                name = "eventlog",
                perfettoDataSource = null,
                legacyStart = "rm -f /data/local/tmp/eventlog.winscope && EVENT_LOG_TRACING_START_TIME=\$EPOCHREALTIME",
                legacyStop =
                    "echo \"EventLog\\n\" > /data/local/tmp/eventlog.winscope && " +
                        "su root logcat -b events -v threadtime -v printable -v uid -v nsec -v epoch " +
                        "-b events -t \$EVENT_LOG_TRACING_START_TIME >> /data/local/tmp/eventlog.winscope",
                files = winscopeMatcher("/data/local/tmp", "eventlog", "eventlog"),
                hasOptionalConfig = false,
            ),
            TraceTarget(
                name = "transition_traces",
                perfettoDataSource = "com.android.wm.shell.transition",
                legacyStart =
                    "su root cmd window shell tracing start && " +
                        "su root dumpsys activity service SystemUIService WMShell transitions tracing start",
                legacyStop =
                    "su root cmd window shell tracing stop && " +
                        "su root dumpsys activity service SystemUIService WMShell transitions tracing stop >/dev/null 2>&1",
                files =
                    winscopeMatcher(WINSCOPE_DIR, "wm_transition_trace", "wm_transition_trace") +
                        winscopeMatcher(WINSCOPE_DIR, "shell_transition_trace", "shell_transition_trace"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "input",
                perfettoDataSource = "android.input.inputevent",
                legacyStart = "",
                legacyStop = "",
                files = winscopeMatcher(WINSCOPE_DIR, "input_trace", "input_trace"),
                hasOptionalConfig = true,
            ),
            TraceTarget(
                name = "perfetto_trace",
                perfettoDataSource = "*", // available when any winscope data source probes true
                legacyStart = "",
                legacyStop = "",
                files = listOf(CollectedFile(PERFETTO_TRACE_FILE, "", "", "trace.perfetto-trace")),
                hasOptionalConfig = false,
            ),
        ).associateBy(TraceTarget::name)

    public val DUMP_TARGETS: Map<String, DumpTarget> =
        listOf(
            DumpTarget(
                name = "window_dump",
                perfettoDataSource = null,
                dumpCommand = "su root dumpsys window --proto > /data/local/tmp/wm_dump.winscope",
                files = listOf(CollectedFile("/data/local/tmp/wm_dump.winscope", "", "", "window_dump")),
                hasOptionalConfig = false,
            ),
            DumpTarget(
                name = "layers_dump",
                perfettoDataSource = "android.surfaceflinger.layers",
                dumpCommand = "su root dumpsys SurfaceFlinger --proto > /data/local/tmp/sf_dump.winscope",
                files = listOf(CollectedFile("/data/local/tmp/sf_dump.winscope", "", "", "layers_dump")),
                hasOptionalConfig = true,
            ),
            DumpTarget(
                name = "screenshot",
                perfettoDataSource = null,
                dumpCommand = "screencap -p {options}> /data/local/tmp/screenshot_{trace_identifier}.png",
                files =
                    listOf(
                        CollectedFile("/data/local/tmp/screenshot_{trace_identifier}.png", "", "", "screenshot_{trace_identifier}.png"),
                    ),
                hasOptionalConfig = true,
            ),
            DumpTarget(
                name = "perfetto_dump",
                perfettoDataSource = "*",
                dumpCommand = "",
                files = listOf(CollectedFile(PERFETTO_DUMP_FILE, "", "", "dump.perfetto-trace")),
                hasOptionalConfig = false,
            ),
        ).associateBy(DumpTarget::name)

    /** Whether the target can take the modern perfetto path given the probe result. */
    public fun isPerfettoAvailable(
        targetName: String,
        probeResult: String,
    ): Boolean {
        val target = TRACE_TARGETS[targetName] ?: return false
        return when (val source = target.perfettoDataSource) {
            null -> false
            "*" -> PROBED_DATA_SOURCES.any { probeResult.contains(it) }
            else -> probeResult.contains(source)
        }
    }

    public fun isDumpPerfettoAvailable(
        targetName: String,
        probeResult: String,
    ): Boolean {
        val target = DUMP_TARGETS[targetName] ?: return false
        return when (val source = target.perfettoDataSource) {
            null -> false
            "*" -> PROBED_DATA_SOURCES.any { probeResult.contains(it) }
            else -> probeResult.contains(source)
        }
    }
}
