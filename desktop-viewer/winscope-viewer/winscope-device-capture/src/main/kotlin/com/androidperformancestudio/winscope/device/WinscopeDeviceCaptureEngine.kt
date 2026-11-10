@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "TooManyFunctions",
    "LargeClass",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "MaxLineLength",
)

package com.androidperformancestudio.winscope.device

import com.androidperformancestudio.platform.adb.AdbClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * One capture configuration entry sent by the bundled viewer with `/start`/`/dump`
 * (`{"key": ..., "value": ...}`); value is a string or a list of strings.
 */
public data class WinscopeConfigEntry(
    val key: String,
    val value: Any?,
)

/** One trace request entry sent by the bundled viewer (`{"name": ..., "config": [...]}`). */
public data class WinscopeTraceRequest(
    val name: String,
    val config: List<WinscopeConfigEntry>,
)

/** A target selected for execution with its resolved modern/legacy path and identifiers. */
internal data class PreparedTarget(
    val name: String,
    val isPerfetto: Boolean,
    val isDump: Boolean,
    val identifiers: List<String>,
    /** Optional start args per identifier (screen recording display ids); keyed by identifier. */
    val optionsByIdentifier: Map<String, String>,
)

/** Result of a completed start/end/dump device exchange. */
public data class WinscopePrepareResult(
    val warnings: List<String>,
)

/** Runtime state of one running trace shell on a device. */
internal class ActiveTrace(
    val targetName: String,
    val identifier: String,
    val statusFilename: String,
    val stopRequested: AtomicBoolean,
    val job: CompletableDeferred<AdbShellOutcome>,
)

internal data class AdbShellOutcome(
    val stdout: String,
    val stderr: String,
    val cancelled: Boolean,
)

/**
 * Executes the Winscope capture decision table on a device via ADB, ported from
 * `winscope_proxy.py` (v4.0.8). Behavior contract: `docs/winscope-proxy-protocol.md`.
 *
 * Modern path first: when the device's perfetto instance exposes the requested winscope
 * data sources (and fewer than five concurrent sessions exist), all sources are collected
 * into one fused perfetto trace; otherwise each target falls back to its legacy commands
 * and files are collected from `/data/misc/wmtrace/` etc.
 */
public class WinscopeDeviceCaptureEngine(
    private val adbClient: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val activeCaptures = ConcurrentHashMap<String, MutableList<ActiveTrace>>()
    private val lastCaptureTargets = ConcurrentHashMap<String, List<String>>()

    /**
     * Invoked when a capture starts (`serial`, target names) or ends (`null`, empty).
     * The service layer mirrors this into the page state so the in-app page reflects
     * the browser-driven capture.
     */
    public var captureStateListener: ((String?, List<String>) -> Unit)? = null

    /**
     * Invoked after a successful `/fetch` with the raw (uncompressed) collected bytes
     * per file, so the host application can archive capture artifacts locally.
     */
    public var fetchArchiveHook: ((serial: String, targets: List<String>, files: Map<String, ByteArray>) -> Unit)? = null

    public fun hasActiveCapture(serial: String): Boolean = activeCaptures.containsKey(serial)

    public fun activeTargetNames(serial: String): List<String> = activeCaptures[serial]?.map(ActiveTrace::targetName) ?: emptyList()

    /**
     * Whether the first active trace shell for [serial] is still running. The viewer
     * polls `/status` every second and string-compares against Python-style "True":
     * anything else is treated as a dead trace and triggers an automatic stop.
     */
    public fun isCaptureAlive(serial: String): Boolean = activeCaptures[serial]?.firstOrNull()?.let { !it.job.isCompleted } ?: false

    // ------------------------------------------------------------------ start

    /**
     * Prepares and starts a capture for [serial]. Returns viewer-facing warnings
     * (e.g. session-limit fallback). Throws [WinscopeBadRequestException] for
     * unsupported config keys and [WinscopeDeviceCaptureException] for device failures.
     */
    public suspend fun startCapture(
        serial: String,
        requests: List<WinscopeTraceRequest>,
    ): WinscopePrepareResult =
        withContext(Dispatchers.IO) {
            // Reference behavior for a duplicate start is an empty 200; we keep the client
            // contract but skip spawning a second set of trace shells.
            if (hasActiveCapture(serial)) return@withContext WinscopePrepareResult(emptyList())
            val (prepared, warnings) = prepare(serial, requests, isDump = false)

            prepared.forEach { target ->
                target.identifiers.forEach { identifier ->
                    val traceTarget = WinscopeTraceTargets.TRACE_TARGETS.getValue(target.name)
                    val startCommand =
                        if (identifier.isNotEmpty()) {
                            traceTarget.legacyStart
                                .replace("{trace_identifier}", identifier)
                                .replace("{options}", target.optionsByIdentifier[identifier] ?: "")
                        } else if (target.isPerfetto) {
                            perfettoTraceStartCommand()
                        } else {
                            traceTarget.legacyStart
                        }
                    val stopCommand =
                        if (target.isPerfetto) {
                            perfettoTraceStopCommand()
                        } else if (identifier.isNotEmpty()) {
                            traceTarget.legacyStop
                                .replace("{trace_identifier}", identifier)
                                .replace("{options}", target.optionsByIdentifier[identifier] ?: "")
                        } else {
                            traceTarget.legacyStop
                        }
                    val statusFilename = WinscopeTraceTargets.WINSCOPE_STATUS + "_" + target.name
                    val script = startShellScript(statusFilename, stopCommand, startCommand)
                    val stopRequested = AtomicBoolean(false)
                    val outcome = CompletableDeferred<AdbShellOutcome>()
                    val active =
                        ActiveTrace(
                            targetName = target.name,
                            identifier = identifier,
                            statusFilename = statusFilename,
                            stopRequested = stopRequested,
                            job = outcome,
                        )
                    activeCaptures.compute(serial) { _, list -> (list ?: mutableListOf()).also { it.add(active) } }
                    scope.launch {
                        val result = runShellIgnoringCancellation(serial, script, stopRequested)
                        outcome.complete(result)
                    }
                }
            }
            keepAliveDeadlineMillis[serial] = System.currentTimeMillis() + keepAliveTimeoutMillis
            captureStateListener?.invoke(serial, prepared.map { it.name })
            WinscopePrepareResult(warnings)
        }

    // -------------------------------------------------------------------- end

    /**
     * Stops the active capture for [serial], collects device files into the backup
     * directory, and returns viewer-facing errors (empty list on success).
     */
    public suspend fun endCapture(serial: String): List<String> =
        withContext(Dispatchers.IO) {
            val activeList =
                activeCaptures.remove(serial)
                    ?: throw WinscopeBadRequestException("No trace in progress for $serial")
            lastCaptureTargets[serial] = activeList.map(ActiveTrace::targetName)
            val errors = mutableListOf<String>()

            activeList.forEach { trace ->
                trace.stopRequested.set(true)
                val outcome = trace.job.await()
                val statusSeen = pollStatusOk(serial, trace.statusFilename)

                // A cancelled shell is the reference implementation's intended stop
                // mechanism (SIGINT to the local adb process triggers the device-side
                // trap); only an uncancelled non-empty stderr means the trace failed.
                val success =
                    if (trace.targetName == "perfetto_trace") {
                        statusSeen
                    } else {
                        statusSeen && (outcome.cancelled || outcome.stderr.isBlank())
                    }
                if (!success) {
                    errors += "Error ending trace ${trace.targetName} on the device: ${outcome.stderr.ifBlank { "<no stderr>" }}"
                }
                moveCollectedFiles(serial, trace.targetName, trace.identifier)
            }
            activeList.forEach { trace ->
                runCatching {
                    adbClient.shell(serial, listOf("su", "root", "rm", "-f", trace.statusFilename))
                }
            }
            captureStateListener?.invoke(null, emptyList())
            errors
        }

    // ------------------------------------------------------------------- dump

    /** Runs a one-shot dump for [serial] and returns viewer-facing warnings. */
    public suspend fun dump(
        serial: String,
        requests: List<WinscopeTraceRequest>,
    ): WinscopePrepareResult =
        withContext(Dispatchers.IO) {
            val (prepared, warnings) = prepare(serial, requests, isDump = true)

            val dumpCommands =
                prepared.flatMap { target ->
                    val dumpTarget = WinscopeTraceTargets.DUMP_TARGETS.getValue(target.name)
                    if (target.name == "perfetto_dump") {
                        listOf(perfettoDumpCommand())
                    } else if (target.identifiers.isEmpty()) {
                        listOf(dumpTarget.dumpCommand)
                    } else {
                        target.identifiers.map { identifier ->
                            dumpTarget.dumpCommand
                                .replace("{trace_identifier}", identifier)
                                .replace("{options}", target.optionsByIdentifier[identifier] ?: "")
                        }
                    }
                }
            if (dumpCommands.isNotEmpty()) {
                adbClient
                    .shell(serial, listOf("sh", "-c", dumpCommands.joinToString("\n")))
                    .requireDeviceSuccess("Error executing dump command.")
            }
            prepared.forEach { target -> moveCollectedFiles(serial, target.name, target.identifiers.firstOrNull() ?: "") }
            WinscopePrepareResult(warnings)
        }

    // ------------------------------------------------------------------ fetch

    /**
     * Pulls collected files from the device backup directory and encodes them the way
     * the bundled viewer expects: `{ "<name>.gz": [ "<base64-mime>" ] }`.
     */
    @Suppress("SwallowedException")
    public suspend fun fetchFiles(serial: String): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            val paths = listBackupFiles(serial)
            val result = linkedMapOf<String, MutableList<String>>()
            val raw = linkedMapOf<String, ByteArray>()
            paths.forEach { path ->
                // Pull to a temp file like the reference implementation: a streaming
                // pull bounds memory independently of the trace size, while exec-out
                // cat pre-allocates an output buffer of the cap on both streams.
                val tempFile = Files.createTempFile("winscope-fetch", ".bin")
                try {
                    adbClient
                        .pull(serial, path, tempFile)
                        .requireDeviceSuccess("Unable to fetch file $path")
                    val bytes = Files.readAllBytes(tempFile)
                    val fileName = path.substringAfterLast('/')
                    raw[fileName] = bytes
                    result.getOrPut("$fileName.gz") { mutableListOf() }.add(gzipBase64(bytes))
                } catch (fetchError: Exception) {
                    // The message is carried to the viewer in the reference 500 shape;
                    // the raw cause is not wire-relevant.
                    throw WinscopeDeviceCaptureException(
                        "Unable to fetch file $path - ${fetchError.message ?: fetchError.javaClass.simpleName}",
                    )
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            }
            if (raw.isNotEmpty()) {
                runCatching { fetchArchiveHook?.invoke(serial, lastCaptureTargets[serial] ?: emptyList(), raw) }
            }
            result
        }

    // --------------------------------------------------------------- keepalive

    /** Resets the keep-alive deadline for [serial] (invoked by `GET /status`). */
    public fun resetKeepAlive(serial: String) {
        keepAliveDeadlineMillis[serial] = System.currentTimeMillis() + keepAliveTimeoutMillis
    }

    /**
     * Watchdog sweep: ends captures whose viewer keep-alive expired so device tracing
     * sessions are released instead of hanging forever.
     */
    public suspend fun sweepExpired(): List<String> {
        val now = System.currentTimeMillis()
        val expired = keepAliveDeadlineMillis.entries.filter { it.value < now }.map { it.key }
        expired.forEach { serial ->
            keepAliveDeadlineMillis.remove(serial)
            runCatching { endCapture(serial) }
        }
        return expired
    }

    private val keepAliveDeadlineMillis = ConcurrentHashMap<String, Long>()
    private val keepAliveTimeoutMillis: Long = WinscopeTraceTargets.KEEP_ALIVE_INTERVAL_SECONDS * 2 * 1000

    // ----------------------------------------------------------------- prepare

    private suspend fun prepare(
        serial: String,
        requests: List<WinscopeTraceRequest>,
        isDump: Boolean,
    ): Pair<List<PreparedTarget>, List<String>> {
        val configFile =
            if (isDump) WinscopeTraceTargets.PERFETTO_DUMP_CONFIG_FILE else WinscopeTraceTargets.PERFETTO_TRACE_CONFIG_FILE
        adbClient
            .shell(serial, listOf("su", "root", "rm", "-f", configFile))
            .requireDeviceSuccess("Failed to clear perfetto config file")

        val warnings = mutableListOf<String>()
        val probeResult = adbClient.shell(serial, listOf("perfetto", "--query")).stdout
        val tooManySessions = resolveTooManySessions(serial, probeResult)
        if (tooManySessions) {
            warnings += "Limit of 5 Perfetto sessions reached on device. Will attempt to collect legacy traces."
        }
        val anySourceAvailable = WinscopeTraceTargets.PROBED_DATA_SOURCES.any { probeResult.contains(it) }

        val prepared = mutableListOf<PreparedTarget>()
        requests.forEach { request ->
            if (isDump) {
                val target = WinscopeTraceTargets.DUMP_TARGETS[request.name] ?: return@forEach
                val isPerfetto = !tooManySessions && WinscopeTraceTargets.isDumpPerfettoAvailable(request.name, probeResult)
                applyConfig(serial, request, isPerfetto, isDump)
                val included =
                    (request.name == "perfetto_dump" && !tooManySessions && anySourceAvailable) ||
                        (request.name != "perfetto_dump" && !isPerfetto)
                if (included) {
                    prepared += preparedDump(target, isPerfetto, request)
                }
            } else {
                val target = WinscopeTraceTargets.TRACE_TARGETS[request.name] ?: return@forEach
                val isPerfetto = !tooManySessions && WinscopeTraceTargets.isPerfettoAvailable(request.name, probeResult)
                applyConfig(serial, request, isPerfetto, isDump)
                val included =
                    (request.name == "perfetto_trace" && !tooManySessions && anySourceAvailable) ||
                        (request.name != "perfetto_trace" && !isPerfetto)
                if (included) {
                    prepared += preparedTrace(target, isPerfetto, request)
                }
            }
        }
        // The perfetto target must come last so its buffers/session header lands after
        // all data-source config entries in the device config file.
        val sorted = prepared.sortedBy { if (it.isPerfetto) 1 else 0 }

        requireRoot(serial)
        clearLastTracingSession(serial)
        return sorted to warnings
    }

    private fun preparedTrace(
        target: WinscopeTraceTargets.TraceTarget,
        isPerfetto: Boolean,
        request: WinscopeTraceRequest,
    ): PreparedTarget {
        val displays = request.config.firstOrNull { it.key == "displays" }?.value
        val identifiers =
            when {
                target.name == "screen_recording" && displays is String && displays.isNotBlank() ->
                    listOf(displays.split(" ").first())
                target.name == "screen_recording" && displays is List<*> && displays.isNotEmpty() ->
                    displays.map { it.toString().split(" ").first() }
                target.name == "screen_recording" -> listOf("active")
                else -> listOf("")
            }
        val options =
            identifiers.associateWith { identifier ->
                if (identifier == "active" || identifier.isEmpty()) "" else "--display-id $identifier"
            }
        return PreparedTarget(target.name, isPerfetto, isDump = false, identifiers = identifiers, optionsByIdentifier = options)
    }

    private fun preparedDump(
        target: WinscopeTraceTargets.DumpTarget,
        isPerfetto: Boolean,
        request: WinscopeTraceRequest,
    ): PreparedTarget {
        val displays = request.config.firstOrNull { it.key == "displays" }?.value
        val identifiers =
            when {
                target.name == "screenshot" && displays is String && displays.isNotBlank() ->
                    listOf(displays.split(" ").first())
                target.name == "screenshot" && displays is List<*> && displays.isNotEmpty() ->
                    displays.map { it.toString().split(" ").first() }
                target.name == "screenshot" -> listOf("active")
                else -> listOf("")
            }
        val options =
            identifiers.associateWith { identifier ->
                if (identifier == "active" || identifier.isEmpty()) "" else "-d $identifier"
            }
        return PreparedTarget(target.name, isPerfetto, isDump = true, identifiers = identifiers, optionsByIdentifier = options)
    }

    private suspend fun applyConfig(
        serial: String,
        request: WinscopeTraceRequest,
        isPerfetto: Boolean,
        isDump: Boolean,
    ) {
        val configFile =
            if (isDump) WinscopeTraceTargets.PERFETTO_DUMP_CONFIG_FILE else WinscopeTraceTargets.PERFETTO_TRACE_CONFIG_FILE
        when (request.name) {
            "layers_trace", "layers_dump" -> {
                val flags = request.config.filter { it.key in SF_LEGACY_FLAGS }.map { it.key }
                val bufferSize =
                    request.config
                        .firstOrNull { it.key == "sfbuffersize" }
                        ?.value
                        ?.toString() ?: "16000"
                if (isPerfetto) {
                    val mapped = flags.map { SF_PERFETTO_FLAGS.getValue(it) }
                    val text =
                        if (isDump) {
                            WinscopePerfettoConfigText.surfaceFlingerLayers(mapped, isDump = true)
                        } else {
                            WinscopePerfettoConfigText.surfaceFlingerLayers(mapped)
                        }
                    adbClient
                        .shell(serial, listOf("sh", "-c", WinscopePerfettoConfigText.appendConfigCommand(configFile, text)))
                        .requireDeviceSuccess("SF perfetto config failed")
                } else if (!isDump) {
                    val flagBits = flags.fold(0) { acc, flag -> acc or SF_LEGACY_FLAGS.getValue(flag) }
                    adbClient
                        .shell(serial, listOf("sh", "-c", "su root service call SurfaceFlinger 1033 i32 $flagBits"))
                        .requireDeviceSuccess("SF flags config failed")
                    adbClient
                        .shell(serial, listOf("sh", "-c", "su root service call SurfaceFlinger 1029 i32 $bufferSize"))
                        .requireDeviceSuccess("SF buffer size config failed")
                }
            }
            "window_trace" -> {
                val level =
                    request.config
                        .firstOrNull { it.key == "tracinglevel" }
                        ?.value
                        ?.toString() ?: "debug"
                val frequency =
                    request.config
                        .firstOrNull { it.key == "tracingtype" }
                        ?.value
                        ?.toString() ?: "frame"
                val bufferSize =
                    request.config
                        .firstOrNull { it.key == "wmbuffersize" }
                        ?.value
                        ?.toString() ?: "16000"
                if (isPerfetto) {
                    val text =
                        WinscopePerfettoConfigText.windowManager(
                            WM_PERFETTO_LOG_LEVEL.getValue(level),
                            WM_PERFETTO_LOG_FREQUENCY.getValue(frequency),
                        )
                    adbClient
                        .shell(serial, listOf("sh", "-c", WinscopePerfettoConfigText.appendConfigCommand(configFile, text)))
                        .requireDeviceSuccess("WM perfetto config failed")
                } else {
                    // Buffer size must be configured last: earlier commands may override it.
                    adbClient
                        .shell(serial, listOf("sh", "-c", "su root cmd window tracing $frequency"))
                        .requireDeviceSuccess("WM tracing type config failed")
                    adbClient
                        .shell(serial, listOf("sh", "-c", "su root cmd window tracing level $level"))
                        .requireDeviceSuccess("WM tracing level config failed")
                    adbClient
                        .shell(serial, listOf("sh", "-c", "su root cmd window tracing size $bufferSize"))
                        .requireDeviceSuccess("WM buffer size config failed")
                }
            }
            "view_capture_trace" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.viewCapture())
            "transactions" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.transactions())
            "proto_log" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.protoLog())
            "ime" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.ime())
            "transition_traces" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.transitions())
            "input" -> if (isPerfetto) appendSourceConfig(serial, configFile, WinscopePerfettoConfigText.input())
        }
    }

    private suspend fun appendSourceConfig(
        serial: String,
        configFile: String,
        text: String,
    ) {
        adbClient
            .shell(serial, listOf("sh", "-c", WinscopePerfettoConfigText.appendConfigCommand(configFile, text)))
            .requireDeviceSuccess("Perfetto source config failed")
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun requireRoot(serial: String) {
        val uid = adbClient.shell(serial, listOf("su", "root", "id", "-u")).stdout.trim()
        if (uid != "0") {
            throw WinscopeDeviceCaptureException(
                "Unable to acquire root privileges on the device - check the output of 'adb -s $serial shell su root id'",
            )
        }
    }

    private suspend fun clearLastTracingSession(serial: String) {
        val backup = WinscopeTraceTargets.WINSCOPE_BACKUP_DIR
        adbClient
            .shell(serial, listOf("sh", "-c", "su root rm -rf $backup && su root mkdir $backup"))
            .requireDeviceSuccess("Failed to clear previous tracing session files")
    }

    /** Parses `perfetto --query` sessions; stops a stale winscope session and applies the 5-session limit. */
    private suspend fun resolveTooManySessions(
        serial: String,
        probeResult: String,
    ): Boolean {
        val start = probeResult.indexOf(SESSIONS_TABLE_HEADER)
        if (start == -1) return false
        val afterHeader = probeResult.substring(start + SESSIONS_TABLE_HEADER.length)
        val end = afterHeader.indexOf(SESSIONS_TABLE_FOOTER).takeIf { it > 0 } ?: afterHeader.length
        val rows =
            afterHeader
                .substring(0, end)
                .trim()
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        var count = rows.size
        if (rows.any { it.contains(WinscopeTraceTargets.PERFETTO_UNIQUE_SESSION_NAME) }) {
            adbClient
                .shell(
                    serial,
                    listOf("sh", "-c", "perfetto --attach=${WinscopeTraceTargets.PERFETTO_DETACH_NAME} --stop"),
                )
            count -= 1
        }
        return count >= 5
    }

    private suspend fun pollStatusOk(
        serial: String,
        statusFilename: String,
    ): Boolean {
        val deadline = System.currentTimeMillis() + WinscopeTraceTargets.COMMAND_TIMEOUT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            val content =
                runCatching {
                    adbClient.shell(serial, listOf("su", "root", "cat", statusFilename)).stdout.trim()
                }.getOrDefault("")
            if (content == "TRACE_OK") return true
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    private suspend fun moveCollectedFiles(
        serial: String,
        targetName: String,
        identifier: String,
    ) {
        val files =
            WinscopeTraceTargets.TRACE_TARGETS[targetName]?.files
                ?: WinscopeTraceTargets.DUMP_TARGETS[targetName]?.files
                ?: return
        val backup = WinscopeTraceTargets.WINSCOPE_BACKUP_DIR
        files.forEach { file ->
            val paths =
                if (file.fixedPath != null) {
                    listOf(file.fixedPath.replace("{trace_identifier}", identifier))
                } else {
                    runCatching {
                        adbClient
                            .shell(serial, listOf("sh", "-c", "su root find ${file.directory} -name ${file.matcher}"))
                            .stdout
                            .trim()
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toList()
                    }.getOrDefault(emptyList())
                }
            val fileType = file.fileType.replace("{trace_identifier}", identifier)
            paths.forEach { path ->
                runCatching {
                    adbClient.shell(
                        serial,
                        listOf("sh", "-c", "su root [ ! -f $path ] || su root mv $path $backup$fileType"),
                    )
                }
            }
        }
    }

    private suspend fun listBackupFiles(serial: String): List<String> =
        runCatching {
            adbClient
                .shell(
                    serial,
                    listOf("sh", "-c", "su root find ${WinscopeTraceTargets.WINSCOPE_BACKUP_DIR}*"),
                ).stdout
                .trim()
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        }.getOrDefault(emptyList())

    private fun startShellScript(
        statusFilename: String,
        stopCommand: String,
        startCommand: String,
    ): String =
        START_SCRIPT_TEMPLATE
            .replace("{winscope_status}", statusFilename)
            .replace("{signal_handler_log}", WinscopeTraceTargets.SIGNAL_HANDLER_LOG)
            .replace("{stop_commands}", stopCommand)
            .replace("{start_commands}", startCommand)

    private fun perfettoTraceStartCommand(): String =
        WinscopePerfettoConfigText.appendConfigCommand(
            WinscopeTraceTargets.PERFETTO_TRACE_CONFIG_FILE,
            WinscopePerfettoConfigText.perfettoTraceHeader(WinscopeTraceTargets.PERFETTO_UNIQUE_SESSION_NAME),
        ) +
            "\nrm -f ${WinscopeTraceTargets.PERFETTO_TRACE_FILE}\n" +
            "perfetto --out ${WinscopeTraceTargets.PERFETTO_TRACE_FILE} --txt " +
            "--config ${WinscopeTraceTargets.PERFETTO_TRACE_CONFIG_FILE} " +
            "--detach=${WinscopeTraceTargets.PERFETTO_DETACH_NAME}\n" +
            "echo 'Started perfetto trace.'"

    private fun perfettoTraceStopCommand(): String = "perfetto --attach=${WinscopeTraceTargets.PERFETTO_DETACH_NAME} --stop"

    private fun perfettoDumpCommand(): String =
        WinscopePerfettoConfigText.appendConfigCommand(
            WinscopeTraceTargets.PERFETTO_DUMP_CONFIG_FILE,
            WinscopePerfettoConfigText.perfettoDumpHeader(),
        ) +
            "\nrm -f ${WinscopeTraceTargets.PERFETTO_DUMP_FILE}\n" +
            "perfetto --out ${WinscopeTraceTargets.PERFETTO_DUMP_FILE} --txt " +
            "--config ${WinscopeTraceTargets.PERFETTO_DUMP_CONFIG_FILE}\n" +
            "echo 'Recorded perfetto dump.'"

    private suspend fun runShellIgnoringCancellation(
        serial: String,
        script: String,
        stopRequested: AtomicBoolean,
    ): AdbShellOutcome =
        runCatching {
            val result =
                adbClient.shell(
                    serial,
                    listOf("sh", "-c", script),
                    timeout = SHELL_MAX_DURATION,
                    isCancellationRequested = stopRequested::get,
                )
            AdbShellOutcome(result.stdout, result.stderr, cancelled = false)
        }.getOrElse { error ->
            // Cancellation (viewer keep-alive timeout or /end) destroys the local adb
            // shell; the device-side trap then writes TRACE_OK and stops the trace.
            AdbShellOutcome("", error.message ?: error.javaClass.simpleName, cancelled = true)
        }

    private fun com.androidperformancestudio.platform.adb.AdbTextResult.requireDeviceSuccess(context: String) {
        if (!isSuccess) {
            throw WinscopeDeviceCaptureException(context + if (stderr.isBlank()) "" else "\n$stderr")
        }
    }

    private fun gzipBase64(bytes: ByteArray): String {
        val buffer = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(bytes) }
        // MIME line wrapping (76 chars) matches Python base64.encodebytes, which the
        // bundled viewer parses.
        return Base64.getMimeEncoder().encodeToString(buffer.toByteArray())
    }

    private companion object {
        val SHELL_MAX_DURATION: Duration = 24.hours
        val SF_LEGACY_FLAGS: Map<String, Int> =
            mapOf(
                "input" to 2,
                "composition" to 4,
                "metadata" to 8,
                "hwc" to 16,
                "tracebuffers" to 32,
                "virtualdisplays" to 64,
            )
        val SF_PERFETTO_FLAGS: Map<String, String> =
            mapOf(
                "input" to "TRACE_FLAG_INPUT",
                "composition" to "TRACE_FLAG_COMPOSITION",
                "metadata" to "TRACE_FLAG_EXTRA",
                "hwc" to "TRACE_FLAG_HWC",
                "tracebuffers" to "TRACE_FLAG_BUFFERS",
                "virtualdisplays" to "TRACE_FLAG_VIRTUAL_DISPLAYS",
            )
        val WM_PERFETTO_LOG_LEVEL: Map<String, String> =
            mapOf(
                "verbose" to "LOG_LEVEL_VERBOSE",
                "debug" to "LOG_LEVEL_DEBUG",
                "critical" to "LOG_LEVEL_CRITICAL",
            )
        val WM_PERFETTO_LOG_FREQUENCY: Map<String, String> =
            mapOf(
                "frame" to "LOG_FREQUENCY_FRAME",
                "transaction" to "LOG_FREQUENCY_TRANSACTION",
            )
        const val SESSIONS_TABLE_HEADER: String =
            "TRACING SESSIONS:\n\n" +
                "ID      UID     STATE      BUF (#) KB   DUR (s)   #DS  STARTED  NAME\n" +
                "===     ===     =====      ==========   =======   ===  =======  ====\n"
        const val SESSIONS_TABLE_FOOTER: String =
            "\nNOTE: Some tracing sessions are not reported in the list above."

        val START_SCRIPT_TEMPLATE: String =
            """
            set -e

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
            """.trimIndent()
    }
}
