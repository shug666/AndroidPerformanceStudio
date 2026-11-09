package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.defaultAdbCommandExecutable
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.winscope.service.WinscopeCaptureService
import com.androidperformancestudio.winscope.service.WinscopeServiceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Factory binding the Winscope page to a host adb executable and the packaged
 * viewer closure. The adb binary is resolved through the shared adb-core locator
 * (configured SDK path first, system adb fallback), matching ScreencastDependencies.
 */
public class WinscopeDependencies(
    private val androidSdkPath: Path?,
    viewerResourceRootCandidates: List<Path>,
) {
    /** First candidate directory that actually contains an index.html; null when unpackaged. */
    public val viewerResourceRoot: Path? =
        viewerResourceRootCandidates.firstOrNull { Files.isRegularFile(it.resolve("index.html")) }

    private val adb: AdbClient by lazy {
        val configuration = AdbConfiguration(androidSdkPath = androidSdkPath)
        DefaultAdbClient(defaultAdbCommandExecutable(configuration))
    }

    public fun createController(): WinscopePageController =
        WinscopePageController(
            service =
                WinscopeCaptureService(
                    adbClient = adb,
                    viewerResourceRoot = viewerResourceRoot ?: Files.createTempDirectory("winscope-empty-viewer"),
                    closureManifest = loadClosureManifest(),
                ),
            viewerMissing = viewerResourceRoot == null,
        )

    /**
     * Loads the packaged checksum manifest (`winscope-viewer-manifest.json`, generated
     * by scripts/generate-winscope-closure-manifest.py) so the viewer host verifies the
     * closure before hosting; an absent manifest disables strict verification.
     */
    private fun loadClosureManifest(): Map<String, String> =
        runCatching {
            val root = viewerResourceRoot ?: error("no viewer root")
            val manifestFile =
                sequenceOf(root.resolve(MANIFEST_FILE_NAME), root.parent?.resolve(MANIFEST_FILE_NAME))
                    .filterNotNull()
                    .firstOrNull(Files::isRegularFile) ?: error("no manifest")
            val rootJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(manifestFile.toFile().readText())
            val files = rootJson.jsonObject["files"]?.jsonObject ?: error("no files section")
            files.map { (relativePath, checksum) -> relativePath to checksum.jsonPrimitive.content }.toMap()
        }.getOrDefault(emptyMap())

    private companion object {
        private const val MANIFEST_FILE_NAME = "winscope-viewer-manifest.json"
    }
}

/**
 * Drives the Winscope page from the [WinscopeCaptureService]: start/stop/open-viewer
 * actions map service snapshots onto [WinscopePageState]; failures surface as the
 * page's error message instead of crashing the shell.
 */
public class WinscopePageController(
    private val service: WinscopeCaptureService,
    private val viewerMissing: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state =
        MutableStateFlow(toPageState(service.state.value, viewerMissing))
    public val state: StateFlow<WinscopePageState> = _state.asStateFlow()

    init {
        scope.launch {
            service.state.collect { snapshot -> _state.value = toPageState(snapshot, viewerMissing) }
        }
    }

    /** Starts the bundled viewer + proxy service. */
    public fun start() {
        scope.launch {
            runCatching { service.start() }
                .onFailure { error ->
                    _state.value =
                        _state.value.copy(
                            serviceState = WinscopeServiceState.FAILED,
                            errorMessage = error.message ?: error.javaClass.simpleName,
                        )
                }
        }
    }

    /** Stops the service and invalidates the session token. */
    public fun stop() {
        scope.launch { runCatching { service.stop() } }
    }

    /** Opens the system browser at the viewer entry page with the session token. */
    public fun openViewer() {
        scope.launch { runCatching { service.openViewer() } }
    }

    public fun close() {
        scope.cancel()
    }
}

private fun toPageState(
    snapshot: WinscopeServiceSnapshot,
    viewerMissing: Boolean,
): WinscopePageState {
    val serviceState =
        when {
            snapshot.running -> WinscopeServiceState.RUNNING
            snapshot.errorMessage != null -> WinscopeServiceState.FAILED
            else -> WinscopeServiceState.STOPPED
        }
    val targets = snapshot.captureTargetNames
    return WinscopePageState(
        serviceState = serviceState,
        captureState =
            when {
                snapshot.captureSerial == null -> WinscopeCaptureState.IDLE
                targets.contains("perfetto_trace") -> WinscopeCaptureState.RECORDING
                else -> WinscopeCaptureState.RECORDING
            },
        captureDeviceSerial = snapshot.captureSerial,
        captureTargets = targets,
        captureLegacyPath = targets.isNotEmpty() && !targets.contains("perfetto_trace"),
        errorMessage = snapshot.errorMessage ?: if (viewerMissing) "viewer resource closure not packaged" else null,
        viewerPort = snapshot.viewerPort,
        viewerUrl = snapshot.viewerUrl,
        sessionToken = snapshot.sessionToken,
    )
}
