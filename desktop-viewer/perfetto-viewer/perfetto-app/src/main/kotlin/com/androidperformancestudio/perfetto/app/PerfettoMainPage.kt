package com.androidperformancestudio.perfetto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticCategory
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.PerfettoDiagnostics
import com.androidperformancestudio.perfetto.capture.PerfettoCapabilityDetector
import com.androidperformancestudio.perfetto.capture.PerfettoCaptureSession
import com.androidperformancestudio.perfetto.capture.PerfettoConfigTextBuilder
import com.androidperformancestudio.perfetto.export.TraceExporter
import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.perfetto.model.PerfettoDeviceCapabilities
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.presentation.PerfettoCapturePage
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactButton
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactTextField
import com.androidperformancestudio.perfetto.presentation.PerfettoStatusDot
import com.androidperformancestudio.perfetto.presentation.TraceAnalyzerWorkspace
import com.androidperformancestudio.perfetto.presentation.TraceAnalyzerWorkspaceActions
import com.androidperformancestudio.perfetto.presentation.TraceAnalyzerWorkspaceState
import com.androidperformancestudio.perfetto.presentation.TraceInsightCategory
import com.androidperformancestudio.perfetto.presentation.TraceInsightEvidence
import com.androidperformancestudio.perfetto.presentation.TraceInsightSeverity
import com.androidperformancestudio.perfetto.presentation.TraceWorkspaceArtifact
import com.androidperformancestudio.perfetto.presentation.TraceWorkspaceSession
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import com.androidperformancestudio.perfetto.uiserver.NativeTraceProcessorServer
import com.androidperformancestudio.perfetto.uiserver.PerfettoUiServer
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.adb
import com.androidperformancestudio.perfetto_app.generated.resources.adb_path
import com.androidperformancestudio.perfetto_app.generated.resources.device_connected
import com.androidperformancestudio.perfetto_app.generated.resources.device_refresh_failed
import com.androidperformancestudio.perfetto_app.generated.resources.device_status
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_allocation_churn_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_loading_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_verification_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_gc_events_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_jit_dex2oat_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_frame_alignment_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_ipc_long_tail_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_system_server_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_block_io_pressure_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_frequency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_hotspots_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_data_source_limitation
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_dvfs_residency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_file_io_syscalls_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_frame_jank_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_fsync_calls_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_gpu_frequency_residency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_input_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_main_thread_blocked_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_memory_timeline_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_page_faults_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_frame_alignment_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_rails_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_result_rows
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_run_queue_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_switch_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_waking_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_activity_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_lock_wait_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thermal_throttling_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thread_states_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_wakeup_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.export_trace
import com.androidperformancestudio.perfetto_app.generated.resources.export_config_dialog
import com.androidperformancestudio.perfetto_app.generated.resources.failed_to_export_trace
import com.androidperformancestudio.perfetto_app.generated.resources.mb_n
import com.androidperformancestudio.perfetto_app.generated.resources.no_active_trace
import com.androidperformancestudio.perfetto_app.generated.resources.no_online_device
import com.androidperformancestudio.perfetto_app.generated.resources.open
import com.androidperformancestudio.perfetto_app.generated.resources.open_perfetto_trace
import com.androidperformancestudio.perfetto_app.generated.resources.perfetto_traces
import com.androidperformancestudio.perfetto_app.generated.resources.refresh
import com.androidperformancestudio.perfetto_app.generated.resources.select_device
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.AdbProcessStartException
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContext
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.fileSize

private data class DiagnosticOutcome(
    val result: DiagnosticResult? = null,
    val error: String? = null,
)

internal fun formatRecentSessionTimestamp(
    capturedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(capturedAt)

internal fun exportRawTraceFile(
    traceFile: Path,
    destination: Path,
): StudioResult<Path> =
    try {
        java.nio.file.Files.copy(
            traceFile,
            destination,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        StudioResult.Success(destination)
    } catch (exception: IOException) {
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.IO,
                code = "TRACE_EXPORT_FAILED",
                message = exception.message ?: "Failed to export trace",
                cause = exception,
            ),
        )
    }

/** Builds the textproto for [config] and writes it to [destination]. */
internal fun exportConfigText(
    config: PerfettoCaptureConfig,
    destination: Path,
): StudioResult<Path> =
    try {
        Files.writeString(destination, PerfettoConfigTextBuilder.build(config))
        StudioResult.Success(destination)
    } catch (exception: IOException) {
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.IO,
                code = "CONFIG_EXPORT_FAILED",
                message = exception.message ?: "Failed to export config",
                cause = exception,
            ),
        )
    }

/** Default export filename derived from the template, e.g. `system-overview-config.txtpb`. */
internal fun defaultConfigFileName(config: PerfettoCaptureConfig): String =
    config.template.name.lowercase().replace('_', '-') + "-config.txtpb"

@Composable
@Suppress("ktlint:standard:function-naming")
fun FrameWindowScope.PerfettoMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    isActive: Boolean = true,
    androidSdkPath: Path? = null,
    onNavigateHome: (() -> Unit)? = null,
    onOpenUserGuide: (() -> Unit)? = null,
    initialTraceFile: Path? = null,
    initialTraceRequestId: Long = 0L,
    initialTraceNotice: String? = null,
    initialTraceTimestampNanos: Long? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val captureSession = remember { PerfettoCaptureSession() }
    val capabilityDetector = remember { PerfettoCapabilityDetector() }
    val sessionStore = remember { TraceSessionStore() }
    val uiServer = remember { PerfettoUiServer() }
    val nativeServer = remember { NativeTraceProcessorServer() }
    val exporter = remember { TraceExporter() }
    val artifactFactory = remember { PerfettoArtifactFactory() }
    // Release heavy resources when the page leaves composition (eviction/deactivation): stop the
    // embedded UI HTTP server so its port frees for a clean reopen, stop the native trace
    // processor process (its memory footprint is the reason we dispose eagerly), and tear down
    // the capture session's private coroutine scope so an in-progress capture and its ADB
    // subprocesses do not outlive the page.
    DisposableEffect(uiServer, nativeServer, captureSession) {
        onDispose {
            uiServer.stop()
            nativeServer.stop()
            captureSession.close()
        }
    }
    var captureState by remember { mutableStateOf<PerfettoCaptureState>(PerfettoCaptureState.Idle) }
    var sessions by remember { mutableStateOf<List<TraceSession>>(emptyList()) }
    var sessionsLoaded by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
    var activeTraceFile by remember { mutableStateOf<Path?>(null) }
    var activeArtifact by remember { mutableStateOf<com.androidperformancestudio.contracts.CaptureArtifact?>(null) }
    var adbPath by remember(androidSdkPath) { mutableStateOf(defaultPerfettoAdbPath(androidSdkPath)) }
    var devices by remember { mutableStateOf<List<PerfettoDevice>>(emptyList()) }
    var selectedDeviceSerial by remember { mutableStateOf<String?>(null) }
    var deviceCapabilities by remember { mutableStateOf<PerfettoDeviceCapabilities?>(null) }
    var capabilityRefreshKey by remember { mutableStateOf(0) }
    var deviceRefreshInProgress by remember { mutableStateOf(false) }
    var deviceRefreshError by remember { mutableStateOf<StudioError?>(null) }
    var deviceRefreshRequest by remember { mutableStateOf(0L) }
    var analysisContext by remember { mutableStateOf<TraceAnalysisContext?>(null) }
    var analysisContexts by remember { mutableStateOf<TraceAnalysisContexts?>(null) }
    var diagnosticQuery by remember { mutableStateOf<DiagnosticQuery?>(null) }
    var diagnosticOutcomes by remember { mutableStateOf<Map<String, DiagnosticOutcome>>(emptyMap()) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    var sessionSearchQuery by remember { mutableStateOf("") }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var captureVisible by remember { mutableStateOf(false) }
    var fileDialogOpen by remember { mutableStateOf(false) }
    var traceOpenJob by remember { mutableStateOf<Job?>(null) }
    val traceOpenMutex = remember { Mutex() }
    val openTrace: (Path, com.androidperformancestudio.contracts.CaptureArtifact?) -> Unit = { traceFile, artifact ->
        traceOpenJob?.cancel()
        traceOpenJob =
            coroutineScope.launch {
                traceOpenMutex.withLock {
                    val importedArtifact =
                        artifact ?: withContext(Dispatchers.IO) {
                            artifactFactory.imported(UUID.randomUUID().toString(), traceFile, Instant.now())
                        }
                    activeTraceFile = traceFile
                    activeArtifact = importedArtifact
                    recentFiles = (listOf(traceFile) + recentFiles).distinct().take(10)
                }
            }
    }
    // Opening a trace has no side effects; launching a browser-side Perfetto UI is an explicit
    // user action via these entry points. The server, once started, stays up until the page
    // deactivates so the browser can refresh or switch between the official and local UI.
    val launchInUi: (Path?, PerfettoUiServer.ServeMode, Long?) -> Unit = { requestedTraceFile, mode, timestamp ->
        coroutineScope.launch(Dispatchers.IO) {
            val traceFile = requestedTraceFile ?: activeTraceFile ?: run {
                diagnosticError = localizedStringResource(Res.string.no_active_trace, language)
                return@launch
            }
            // The byte-serving endpoint and the native engine are mutually exclusive on port
            // 9001: take the port over from a running native process so this mode can bind.
            nativeServer.stop()
            when (val started = uiServer.start(PerfettoUiServer.tryFindUiAssetsDir(), mode)) {
                is StudioResult.Failure -> diagnosticError = started.error.message
                is StudioResult.Success ->
                    when (val opened = uiServer.openTrace(traceFile, timestamp)) {
                        is StudioResult.Failure -> diagnosticError = opened.error.message
                        is StudioResult.Success -> diagnosticError = null
                    }
            }
        }
    }
    val openInPerfettoUi: (Path?, Long?) -> Unit = { traceFile, timestamp ->
        launchInUi(traceFile, PerfettoUiServer.ServeMode.TRACE_ONLY, timestamp)
    }
    val openInLocalPerfettoUi: () -> Unit = {
        launchInUi(null, PerfettoUiServer.ServeMode.TRACE_AND_UI, null)
    }
    // The native engine and the byte-serving endpoint are mutually exclusive on port 9001: the
    // native RPC server takes the port over explicitly, and the byte server fails to rebind while
    // the native process holds it (SERVER_START_FAILED).
    val openInNativeEngine: () -> Unit = {
        coroutineScope.launch(Dispatchers.IO) {
            val traceFile =
                activeTraceFile ?: run {
                    diagnosticError = localizedStringResource(Res.string.no_active_trace, language)
                    return@launch
                }
            uiServer.stop()
            when (val resolved = TraceProcessorToolResolver().resolve()) {
                is StudioResult.Failure -> diagnosticError = resolved.error.message
                is StudioResult.Success ->
                    when (val started = nativeServer.start(resolved.value, traceFile)) {
                        is StudioResult.Failure -> diagnosticError = started.error.message
                        is StudioResult.Success -> diagnosticError = null
                    }
            }
        }
    }
    val exportRawTrace: (Path) -> Unit = { traceFile ->
        coroutineScope.launch(Dispatchers.IO) {
            val defaultName =
                traceFile.fileName
                    ?.toString()
                    .orEmpty()
                    .ifBlank { "trace.pftrace" }
            val saveFile =
                chooseSaveFile(
                    null,
                    localizedStringResource(Res.string.export_trace, language),
                    defaultName,
                ) ?: return@launch
            when (val exported = exportRawTraceFile(traceFile, saveFile.toPath())) {
                is StudioResult.Success -> diagnosticError = null
                is StudioResult.Failure -> {
                    diagnosticError =
                        exported.error.message.ifBlank {
                            localizedStringResource(Res.string.failed_to_export_trace, language)
                        }
                }
            }
        }
    }

    val exportConfig: (PerfettoCaptureConfig) -> Unit = { config ->
        coroutineScope.launch(Dispatchers.IO) {
            val saveFile =
                chooseSaveFile(
                    null,
                    localizedStringResource(Res.string.export_config_dialog, language),
                    defaultConfigFileName(config),
                ) ?: return@launch
            when (val exported = exportConfigText(config, saveFile.toPath())) {
                is StudioResult.Success -> diagnosticError = null
                is StudioResult.Failure ->
                    diagnosticError =
                        exported.error.message.ifBlank {
                            localizedStringResource(Res.string.failed_to_export_trace, language)
                        }
            }
        }
    }

    val refreshDevices: suspend () -> Unit = {
        val request = ++deviceRefreshRequest
        deviceRefreshInProgress = true
        when (val result = withContext(Dispatchers.IO) { discoverPerfettoDevices(adbPath) }) {
            is StudioResult.Success -> {
                if (request == deviceRefreshRequest) {
                    devices = result.value
                    selectedDeviceSerial = preferredDeviceSerial(selectedDeviceSerial, result.value)
                    deviceRefreshError = null
                    capabilityRefreshKey++
                    deviceRefreshInProgress = false
                }
            }

            is StudioResult.Failure -> {
                if (request == deviceRefreshRequest) {
                    deviceRefreshError = result.error
                    deviceRefreshInProgress = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { captureSession.state.collect { captureState = it } }
    LaunchedEffect(adbPath) { refreshDevices() }
    LaunchedEffect(adbPath, selectedDeviceSerial, devices, capabilityRefreshKey) {
        deviceCapabilities = null
        val device = devices.firstOrNull { it.serial == selectedDeviceSerial } ?: return@LaunchedEffect
        deviceCapabilities = withContext(Dispatchers.IO) { capabilityDetector.detect(adbPath, device) }
    }
    LaunchedEffect(Unit) {
        when (val result = sessionStore.listRecent()) {
            is StudioResult.Success -> sessions = result.value
            is StudioResult.Failure -> diagnosticError = result.error.message
        }
        sessionsLoaded = true
    }
    LaunchedEffect(initialTraceFile, initialTraceRequestId, initialTraceTimestampNanos, sessionsLoaded) {
        if (sessionsLoaded) {
            initialTraceFile?.let { trace ->
                openTrace(trace, storedArtifactForTrace(sessions, trace))
            }
        }
    }
    LaunchedEffect(captureState) {
        val completed = captureState as? PerfettoCaptureState.Completed ?: return@LaunchedEffect
        captureVisible = false
        activeTraceFile = completed.traceFile
        activeArtifact = completed.metadata.artifact
        recentFiles = (listOf(completed.traceFile) + recentFiles).distinct().take(10)
        val session =
            TraceSession(
                id = UUID.randomUUID().toString(),
                traceFile = completed.traceFile,
                captureConfig = completed.metadata.config,
                deviceSerial = completed.metadata.deviceSerial,
                deviceModel = completed.metadata.deviceModel,
                androidSdk = completed.metadata.androidSdk,
                capturedAt = completed.metadata.capturedAt,
                durationNanos = completed.metadata.durationNanos,
                fileSizeBytes = completed.metadata.traceFileSizeBytes,
                artifact = completed.metadata.artifact,
            )
        when (val saved = sessionStore.save(session)) {
            is StudioResult.Success -> {
                when (val reloaded = sessionStore.listRecent()) {
                    is StudioResult.Success -> {
                        sessions = reloaded.value
                        reloaded.value.firstOrNull { it.id == session.id }?.let { stored ->
                            selectedSessionId = stored.id
                            activeTraceFile = stored.traceFile
                            activeArtifact = stored.artifact
                        }
                    }
                    is StudioResult.Failure -> diagnosticError = reloaded.error.message
                }
            }
            is StudioResult.Failure -> diagnosticError = saved.error.message
        }
    }
    LaunchedEffect(activeTraceFile, activeArtifact) {
        val contextToClose = analysisContext
        analysisContext = null
        diagnosticOutcomes = emptyMap()
        diagnosticQuery = null
        diagnosticError = null
        withContext(Dispatchers.IO) { contextToClose?.close() }
    }
    val runDiagnostic: (DiagnosticQuery) -> Unit = { query ->
        diagnosticQuery = query
        diagnosticOutcomes = diagnosticOutcomes + (query.id to DiagnosticOutcome())
        val artifact = activeArtifact
        val traceFile = activeTraceFile
        if (artifact == null || traceFile == null) {
            diagnosticOutcomes =
                diagnosticOutcomes +
                (query.id to DiagnosticOutcome(error = "Trace evidence was not registered"))
        } else {
            coroutineScope.launch(Dispatchers.IO) {
                fun recordFailure(message: String) {
                    if (activeArtifact?.id == artifact.id) {
                        diagnosticOutcomes =
                            diagnosticOutcomes +
                            (query.id to DiagnosticOutcome(error = message))
                    }
                }

                val context =
                    analysisContext ?: when (val located = TraceProcessorToolResolver().resolve()) {
                        is StudioResult.Failure -> {
                            recordFailure(located.error.message)
                            return@launch
                        }
                        is StudioResult.Success -> {
                            val registry =
                                analysisContexts ?: TraceAnalysisContexts(located.value).also { analysisContexts = it }
                            when (val opened = registry.open(artifact, traceFile)) {
                                is StudioResult.Failure -> {
                                    recordFailure(opened.error.message)
                                    return@launch
                                }
                                is StudioResult.Success -> opened.value.also { analysisContext = it }
                            }
                        }
                    }
                when (val queryResult = context.query(query.typedQuery())) {
                    is StudioResult.Success -> {
                        if (activeArtifact?.id == artifact.id) {
                            diagnosticOutcomes =
                                diagnosticOutcomes +
                                (
                                    query.id to
                                        DiagnosticOutcome(
                                            result = DiagnosticResult(query.columns, queryResult.value),
                                        )
                                )
                        }
                    }
                    is StudioResult.Failure -> recordFailure(queryResult.error.message)
                }
            }
        }
    }

    PerfettoFileMenuBar(
        language = language,
        canExport = activeTraceFile != null,
        canOpenLocalPerfettoUi = PerfettoUiServer.tryFindUiAssetsDir() != null,
        recentFiles = recentFiles,
        onOpen = {
            if (!fileDialogOpen) {
                fileDialogOpen = true
                try {
                    // The modal chooser must run on the UI thread, or repeated clicks stack
                    // concurrent JFileChoosers (non-modal from a background thread) and deadlock AWT.
                    val file =
                        chooseOpenFile(
                            null,
                            localizedStringResource(Res.string.open_perfetto_trace, language),
                            localizedStringResource(Res.string.perfetto_traces, language),
                            "pftrace",
                            "perfetto-trace",
                        )
                    if (file != null) openTrace(file.toPath(), null)
                } finally {
                    fileDialogOpen = false
                }
            }
        },
        onOpenInLocalPerfettoUi = openInLocalPerfettoUi,
        onExportSession = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile =
                    chooseSaveFile(
                        null,
                        localizedStringResource(Res.string.export_trace, language),
                        "perfetto-session.zip",
                    ) ?: return@launch
                val session =
                    sessions.firstOrNull { it.traceFile == traceFile }
                        ?: TraceSession(
                            id = UUID.randomUUID().toString(),
                            traceFile = traceFile,
                            captureConfig = PerfettoCaptureConfig(PerfettoTraceTemplate.SYSTEM_OVERVIEW),
                            deviceSerial = "",
                            deviceModel = "",
                            androidSdk = 0,
                            capturedAt = Instant.now(),
                            durationNanos = 0,
                            fileSizeBytes = traceFile.fileSize(),
                            artifact = activeArtifact,
                        )
                when (val exported = exporter.exportSessionPackage(session, saveFile.toPath())) {
                    is StudioResult.Failure -> diagnosticError = exported.error.message
                    is StudioResult.Success -> diagnosticError = null
                }
            }
        },
        onExportRawTrace = { activeTraceFile?.let(exportRawTrace) },
        onOpenRecent = { path ->
            openTrace(path, sessions.firstOrNull { it.traceFile == path }?.artifact)
            openInPerfettoUi(path, null)
        },
        onClearRecent = { recentFiles = emptyList() },
    )

    val workspaceArtifact =
        activeArtifact?.let { artifact ->
            TraceWorkspaceArtifact(
                artifactId = artifact.id.value,
                displayName =
                    activeTraceFile
                        ?.fileName
                        ?.toString()
                        .orEmpty()
                        .ifBlank { artifact.id.value },
                locationLabel = activeTraceFile?.toString().orEmpty(),
                completenessLabel = artifact.completeness.name,
                limitations = artifact.limitations.map { it.message } + artifact.warnings,
                viewerAvailable = activeTraceFile?.let(Files::isRegularFile) == true,
            )
        }
    val workspaceSessions =
        sessions.map { session ->
            TraceWorkspaceSession(
                sessionId = session.id,
                title = session.captureConfig.targetPackage ?: session.captureConfig.template.displayName,
                capturedAtLabel = formatRecentSessionTimestamp(session.capturedAt),
                durationLabel = formatTraceDuration(session.durationNanos),
                artifact =
                    session.artifact?.let { artifact ->
                        TraceWorkspaceArtifact(
                            artifactId = artifact.id.value,
                            displayName =
                                session.traceFile.fileName
                                    ?.toString()
                                    .orEmpty(),
                            locationLabel = session.traceFile.toString(),
                            completenessLabel = artifact.completeness.name,
                            limitations = artifact.limitations.map { it.message } + artifact.warnings,
                            viewerAvailable = Files.isRegularFile(session.traceFile),
                        )
                    },
            )
        }
    val workspaceInsights =
        activeArtifact
            ?.let { artifact ->
                PerfettoDiagnostics.all.map { query ->
                    val outcome = diagnosticOutcomes[query.id]
                    val result = outcome?.result
                    TraceInsightEvidence(
                        queryId = query.id,
                        artifactId = artifact.id.value,
                        title = query.localizedTitle(language),
                        resultRowsSummary =
                            result?.let {
                                localizedStringResource(Res.string.diagnostic_result_rows, language, it.rows.size)
                            },
                        limitations =
                            (artifact.limitations.map { it.message } + artifact.warnings).ifEmpty {
                                listOf(localizedStringResource(Res.string.diagnostic_data_source_limitation, language))
                            },
                        error = outcome?.error,
                        timestampNanos = result?.firstTimelineTimestampNanos(query),
                        severity = if (outcome?.error != null) TraceInsightSeverity.WARNING else TraceInsightSeverity.INFO,
                        category = query.workspaceCategory(),
                    )
                }
            }.orEmpty()
    val workspaceState =
        TraceAnalyzerWorkspaceState(
            sessions = workspaceSessions,
            selectedSessionId = selectedSessionId,
            searchQuery = sessionSearchQuery,
            activeTrace = workspaceArtifact,
            insights = workspaceInsights,
            selectedQueryId = diagnosticQuery?.id,
            captureVisible = captureVisible,
            status = activeTraceFile?.fileName?.toString(),
            error = diagnosticError,
        )
    val workspaceActions =
        TraceAnalyzerWorkspaceActions(
            onSearchChange = { sessionSearchQuery = it },
            onSelectSession = { selectedSessionId = it },
            onOpenSession = { sessionId ->
                sessions.firstOrNull { it.id == sessionId }?.let { session ->
                    selectedSessionId = session.id
                    openTrace(session.traceFile, session.artifact)
                }
            },
            onDeleteSession = { sessionId ->
                coroutineScope.launch {
                    when (val deleted = sessionStore.delete(sessionId)) {
                        is StudioResult.Success -> {
                            sessions = sessions.filterNot { it.id == sessionId }
                            if (selectedSessionId == sessionId) selectedSessionId = null
                        }
                        is StudioResult.Failure -> diagnosticError = deleted.error.message
                    }
                }
            },
            onToggleCapture = { captureVisible = !captureVisible },
            onRunDiagnostic = { queryId ->
                PerfettoDiagnostics.all.firstOrNull { it.id == queryId }?.let(runDiagnostic)
            },
            onOpenInTimeline = { insight ->
                val traceFile = activeTraceFile
                val artifact = activeArtifact
                if (traceFile != null && artifact?.id?.value == insight.artifactId) {
                    openTrace(traceFile, artifact)
                    insight.timestampNanos?.let { timestamp -> openInPerfettoUi(traceFile, timestamp) }
                }
            },
            onOpenViewer = {
                val traceFile = activeTraceFile
                if (traceFile != null) {
                    openTrace(traceFile, activeArtifact)
                    openInPerfettoUi(traceFile, null)
                }
            },
            onOpenNativeEngine = {
                val traceFile = activeTraceFile
                if (traceFile != null) {
                    openTrace(traceFile, activeArtifact)
                    openInNativeEngine()
                }
            },
            onExport = { activeTraceFile?.let(exportRawTrace) },
        )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderToolbar(
                language = language,
                onNavigateHome = onNavigateHome,
                onNavigateSettings = null,
            ) {
                PerfettoToolbarContent(
                    language = language,
                    adbPath = adbPath,
                    onAdbPathChange = { adbPath = it },
                    devices = devices,
                    selectedDeviceSerial = selectedDeviceSerial,
                    onSelectDevice = { selectedDeviceSerial = it },
                    deviceRefreshInProgress = deviceRefreshInProgress,
                    onRefreshDevices = { coroutineScope.launch { refreshDevices() } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            deviceRefreshError?.let {
                Text(
                    text = localizedStringResource(Res.string.device_refresh_failed, language),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.label.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            if (initialTraceFile != null && initialTraceNotice != null) {
                InitialTraceNotice(
                    traceFile = initialTraceFile,
                    notice = initialTraceNotice,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
                )
            }
            TraceAnalyzerWorkspace(
                state = workspaceState,
                actions = workspaceActions,
                language = language,
                modifier = Modifier.fillMaxWidth().weight(1f),
                captureContent = {
                    PerfettoCapturePage(
                        captureState = captureState,
                        language = language,
                        selectedDeviceSerial = selectedDeviceSerial,
                        deviceCapabilities = deviceCapabilities,
                        onStartCapture = { config, deviceSerial ->
                            coroutineScope.launch {
                                captureSession.startCapture(adbPath, deviceSerial, config)
                            }
                        },
                        onStopCapture = { coroutineScope.launch { captureSession.stopCapture() } },
                        onOpenTrace = { traceFile ->
                            openTrace(traceFile, sessions.firstOrNull { it.traceFile == traceFile }?.artifact)
                            openInPerfettoUi(traceFile, null)
                        },
                        onExportConfig = exportConfig,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            PerfettoStatusBar(
                devices = devices,
                selectedDeviceSerial = selectedDeviceSerial,
                language = language,
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            captureSession.cancelCapture()
            uiServer.stop()
            nativeServer.stop()
            analysisContext?.close()
        }
    }
    // The shell keeps this page composed (hidden via alpha) when navigating away, so onDispose
    // never fires. Reset the active trace/view state explicitly when the page goes inactive.
    LaunchedEffect(isActive) {
        if (!isActive) {
            traceOpenJob?.cancel()
            traceOpenJob = null
            fileDialogOpen = false
            captureSession.cancelCapture()
            uiServer.stop()
            // The native engine's multi-GB footprint and its hold on port 9001 are both released
            // eagerly on deactivation, unlike the byte server which could in principle stay up.
            nativeServer.stop()
            activeTraceFile = null
            activeArtifact = null
            diagnosticQuery = null
            diagnosticOutcomes = emptyMap()
            diagnosticError = null
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoStatusBar(
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    language: UiLanguage,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    val statusText =
        selectedDevice?.let { localizedStringResource(Res.string.device_connected, language, it.displayName) }
            ?: localizedStringResource(Res.string.no_online_device, language)
    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ViewerDimensions.footerHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        ViewerDimensions.hairline,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(0.dp),
                    ).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${localizedStringResource(Res.string.device_status, language)}:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.label.fontSize,
                maxLines = 1,
            )
            PerfettoStatusDot(
                color =
                    if (selectedDevice?.online == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.label.fontSize,
                maxLines = 1,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
private fun RowScope.PerfettoToolbarContent(
    language: UiLanguage,
    adbPath: String,
    onAdbPathChange: (String) -> Unit,
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    deviceRefreshInProgress: Boolean,
    onRefreshDevices: () -> Unit,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    Text(
        text = localizedStringResource(Res.string.adb_path, language),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.label.fontSize,
    )
    HeaderSpacer()
    PerfettoCompactTextField(
        value = adbPath,
        onValueChange = onAdbPathChange,
        modifier = Modifier.width(250.dp),
        placeholder = localizedStringResource(Res.string.adb, language),
    )
    HeaderSpacer()
    DeviceSelector(
        devices = devices,
        selectedDeviceSerial = selectedDeviceSerial,
        onSelectDevice = onSelectDevice,
        language = language,
    )
    HeaderSpacer()
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.refresh, language),
        onClick = onRefreshDevices,
        enabled = !deviceRefreshInProgress,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun DeviceSelector(
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    language: UiLanguage,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    val selectDeviceLabel = localizedStringResource(Res.string.select_device, language)
    DropdownSelector(
        items = devices,
        selectedItem = selectedDevice,
        onItemSelected = { onSelectDevice(it.serial) },
        itemLabel = PerfettoDevice::displayName,
        selectedItemLabel = PerfettoDevice::displayName,
        placeholder = selectDeviceLabel,
        modifier = Modifier.width(170.dp),
        selectorDescription = selectDeviceLabel,
        itemEnabled = PerfettoDevice::online,
        fillWidth = true,
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun InitialTraceNotice(
    traceFile: Path,
    notice: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = traceFile.fileName.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        Text(
            text = notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
        )
    }
}

internal suspend fun discoverPerfettoDevices(
    adbPath: String,
    deviceDiscovery: suspend (Path) -> StudioResult<List<AndroidDeviceInfo>> = { executable ->
        AndroidTargetMonitors.shared(executable).refreshDevices()
    },
): StudioResult<List<PerfettoDevice>> {
    if (adbPath.isBlank()) {
        return StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "PERFETTO_ADB_PATH_EMPTY",
                message = "ADB path is empty.",
            ),
        )
    }
    return try {
        val executable = Path.of(adbPath)
        when (val result = deviceDiscovery(executable)) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.map { device ->
                        PerfettoDevice(
                            serial = device.serial,
                            model = device.deviceName,
                            displayName = device.displayName,
                            online = device.online,
                        )
                    },
                )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: InvalidPathException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.CONFIGURATION,
            code = "PERFETTO_ADB_PATH_INVALID",
            error = error,
        )
    } catch (error: AdbProcessStartException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_START,
            code = "PERFETTO_ADB_PROCESS_START_FAILED",
            error = error,
        )
    } catch (error: AdbCommandTimeoutException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_TIMEOUT,
            code = "PERFETTO_ADB_COMMAND_TIMEOUT",
            error = error,
        )
    } catch (error: AdbCommandFailedException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_EXIT,
            code = "PERFETTO_ADB_COMMAND_FAILED",
            error = error,
        )
    } catch (error: AdbException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.UNKNOWN,
            code = "PERFETTO_DEVICE_DISCOVERY_FAILED",
            error = error,
        )
    }
}

private fun deviceDiscoveryFailure(
    category: ErrorCategory,
    code: String,
    error: Throwable,
): StudioResult.Failure =
    StudioResult.Failure(
        StudioError(
            category = category,
            code = code,
            message = error.message ?: "Unable to refresh ADB devices.",
            cause = error,
        ),
    )

internal fun defaultPerfettoAdbPath(
    androidSdkPath: Path?,
    isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true),
): String {
    val executableName = if (isWindows) "adb.exe" else "adb"
    val configuredExecutable = androidSdkPath?.resolve("platform-tools")?.resolve(executableName)
    return configuredExecutable?.takeIf(Files::isRegularFile)?.toString() ?: "adb"
}

internal fun preferredDeviceSerial(
    selectedSerial: String?,
    devices: List<PerfettoDevice>,
): String? =
    selectedSerial?.takeIf { serial -> devices.any { it.serial == serial && it.online } }
        ?: devices.filter(PerfettoDevice::online).singleOrNull()?.serial

private fun DiagnosticQuery.localizedTitle(language: UiLanguage): String =
    when (id) {
        "cpu_hotspots" -> localizedStringResource(Res.string.diagnostic_cpu_hotspots_title, language)
        "sched_switch" -> localizedStringResource(Res.string.diagnostic_sched_switch_title, language)
        "sched_waking" -> localizedStringResource(Res.string.diagnostic_sched_waking_title, language)
        "run_queue" -> localizedStringResource(Res.string.diagnostic_run_queue_title, language)
        "main_thread_blocked" -> localizedStringResource(Res.string.diagnostic_main_thread_blocked_title, language)
        "cpu_freq_dist" -> localizedStringResource(Res.string.diagnostic_cpu_frequency_title, language)
        "binder_latency" -> localizedStringResource(Res.string.diagnostic_binder_latency_title, language)
        "binder_ipc_long_tail" -> localizedStringResource(Res.string.diagnostic_binder_ipc_long_tail_title, language)
        "binder_system_server" -> localizedStringResource(Res.string.diagnostic_binder_system_server_title, language)
        "binder_frame_alignment" -> localizedStringResource(Res.string.diagnostic_binder_frame_alignment_title, language)
        "frame_jank" -> localizedStringResource(Res.string.diagnostic_frame_jank_title, language)
        "file_io_syscalls" -> localizedStringResource(Res.string.diagnostic_file_io_syscalls_title, language)
        "fsync_calls" -> localizedStringResource(Res.string.diagnostic_fsync_calls_title, language)
        "block_io_pressure" -> localizedStringResource(Res.string.diagnostic_block_io_pressure_title, language)
        "page_faults" -> localizedStringResource(Res.string.diagnostic_page_faults_title, language)
        "sqlite_activity" -> localizedStringResource(Res.string.diagnostic_sqlite_activity_title, language)
        "sqlite_lock_wait" -> localizedStringResource(Res.string.diagnostic_sqlite_lock_wait_title, language)
        "art_gc_events" -> localizedStringResource(Res.string.diagnostic_art_gc_events_title, language)
        "art_allocation_churn" -> localizedStringResource(Res.string.diagnostic_art_allocation_churn_title, language)
        "art_jit_dex2oat" -> localizedStringResource(Res.string.diagnostic_art_jit_dex2oat_title, language)
        "art_class_loading" -> localizedStringResource(Res.string.diagnostic_art_class_loading_title, language)
        "art_class_verification" -> localizedStringResource(Res.string.diagnostic_art_class_verification_title, language)
        "thermal_throttling" -> localizedStringResource(Res.string.diagnostic_thermal_throttling_title, language)
        "dvfs_residency" -> localizedStringResource(Res.string.diagnostic_dvfs_residency_title, language)
        "gpu_frequency_residency" -> localizedStringResource(Res.string.diagnostic_gpu_frequency_residency_title, language)
        "power_rails" -> localizedStringResource(Res.string.diagnostic_power_rails_title, language)
        "power_frame_alignment" -> localizedStringResource(Res.string.diagnostic_power_frame_alignment_title, language)
        "mem_counters" -> localizedStringResource(Res.string.diagnostic_memory_timeline_title, language)
        "input_latency" -> localizedStringResource(Res.string.diagnostic_input_latency_title, language)
        "thread_states" -> localizedStringResource(Res.string.diagnostic_thread_states_title, language)
        "wakeup_latency" -> localizedStringResource(Res.string.diagnostic_wakeup_latency_title, language)
        else -> title
    }

internal fun formatTraceDuration(durationNanos: Long): String {
    val totalSeconds = (durationNanos / 1_000_000_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
}

internal fun DiagnosticResult.firstTimelineTimestampNanos(query: DiagnosticQuery): Long? {
    val timestampColumn =
        query.columns.indexOfFirst { column ->
            column == "timestamp_ns" || column == "ts" || column.endsWith("_timestamp_ns")
        }
    if (timestampColumn < 0) return null
    return rows.firstNotNullOfOrNull { row -> row.values.getOrNull(timestampColumn)?.toLongOrNull() }
}

internal fun DiagnosticQuery.workspaceCategory(): TraceInsightCategory =
    when {
        id == "frame_jank" -> TraceInsightCategory.FRAME
        id == "main_thread_blocked" -> TraceInsightCategory.LONG_TASK
        category == DiagnosticCategory.BINDER -> TraceInsightCategory.BINDER
        category == DiagnosticCategory.GRAPHICS -> TraceInsightCategory.RENDERING
        category == DiagnosticCategory.CPU -> TraceInsightCategory.CPU
        category == DiagnosticCategory.MEMORY -> TraceInsightCategory.MEMORY
        category == DiagnosticCategory.INPUT -> TraceInsightCategory.INPUT
        else -> TraceInsightCategory.OTHER
    }
