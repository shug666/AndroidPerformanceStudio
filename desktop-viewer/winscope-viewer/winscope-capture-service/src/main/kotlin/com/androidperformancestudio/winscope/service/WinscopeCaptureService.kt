@file:Suppress("LongParameterList", "MagicNumber")

package com.androidperformancestudio.winscope.service

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.winscope.device.WinscopeDeviceCaptureEngine
import com.androidperformancestudio.winscope.device.WinscopeDeviceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat

/** High-level service snapshot surfaced to the Winscope page. */
public data class WinscopeServiceSnapshot(
    val running: Boolean,
    val proxyPort: Int? = null,
    val viewerPort: Int? = null,
    /** Capture state the browser currently drives, mirrored from the proxy endpoints. */
    val captureSerial: String? = null,
    val captureTargetNames: List<String> = emptyList(),
    val errorMessage: String? = null,
    /** Full viewer entry URL with the token injected; null while stopped. */
    val viewerUrl: String? = null,
    /** Session token shown for the viewer's paste-token retry dialog. */
    val sessionToken: String? = null,
)

/**
 * Facade owning the bundled viewer + proxy service lifecycle: explicit start/stop,
 * session token generation, browser launch with `?token=` injection, and the
 * keep-alive watchdog sweep. Starting the service is never a side effect of page
 * navigation; token and both endpoints die together on stop.
 */
public class WinscopeCaptureService(
    adbClient: AdbClient,
    /** Root directory of the packaged viewer closure (verified before hosting). */
    private val viewerResourceRoot: Path,
    /** Closure-relative path -> expected SHA-256; empty disables strict verification. */
    private val closureManifest: Map<String, String> = emptyMap(),
    private val proxyPort: Int = WinscopeProxyServer.DEFAULT_PORT,
    private val preferredViewerPort: Int = WinscopeViewerHost.DEFAULT_PORT,
    private val browserLauncher: (String) -> Unit = ::openSystemBrowser,
    /** Session artifact root for dual-path archival; null disables local archiving. */
    private val archiveRoot: Path? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalog = WinscopeDeviceCatalog(adbClient)
    private val engine = WinscopeDeviceCaptureEngine(adbClient)
    private val proxyServer = WinscopeProxyServer(engine, catalog, adbClient, proxyPort)
    private val viewerHost = WinscopeViewerHost(preferredViewerPort)
    private val random = SecureRandom()

    init {
        // Dual-path archival (design D5): the bytes returned to the viewer are also
        // written to the session artifact directory with device/path metadata, so a
        // later analysis pass can reference the same evidence.
        engine.fetchArchiveHook =

            fun(
                serial: String,
                targets: List<String>,
                files: Map<String, ByteArray>,
            ) {
                val root = archiveRoot ?: return
                val sessionDir = root.resolve("winscope-${System.currentTimeMillis()}-$serial")
                Files.createDirectories(sessionDir)
                files.forEach { (name, bytes) -> Files.write(sessionDir.resolve(name), bytes) }
                val metadata =
                    buildString {
                        appendLine("{")
                        appendLine("  \"serial\": \"$serial\",")
                        appendLine("  \"capturedAt\": \"${java.time.Instant.now()}\",")
                        appendLine("  \"targets\": [${targets.joinToString(",") { "\"$it\"" }}],")
                        appendLine("  \"files\": [${files.keys.joinToString(",") { "\"$it\"" }}]")
                        append("}")
                    }
                Files.writeString(sessionDir.resolve("capture-meta.json"), metadata)
            }
    }

    init {
        engine.captureStateListener = { serial, targets ->
            _state.value = _state.value.copy(captureSerial = serial, captureTargetNames = targets)
        }
    }

    private val _state = MutableStateFlow(WinscopeServiceSnapshot(running = false))
    public val state: StateFlow<WinscopeServiceSnapshot> = _state.asStateFlow()

    private var watchdog: Job? = null
    private var sessionToken: String? = null
    private var shutdownHook: Thread? = null

    public val isRunning: Boolean
        get() = proxyServer.isRunning

    /**
     * Current session token while running; the Winscope page surfaces it so the
     * viewer's paste-token retry dialog can be served manually after a stale-token 403.
     */
    public fun sessionToken(): String? = sessionToken

    /**
     * Starts the proxy and viewer host. Throws [IllegalStateException] with a
     * user-actionable message when the proxy port is occupied or the closure
     * verification fails.
     */
    public fun start() {
        if (proxyServer.isRunning) return
        try {
            val newToken = newToken()
            sessionToken = newToken
            println(
                "[winscope] starting service: root=" + viewerResourceRoot +
                    " manifest=" + closureManifest.size + " entries",
            )
            viewerHost.start(viewerResourceRoot, closureManifest)
            proxyServer.start(newToken)
            _state.value =
                WinscopeServiceSnapshot(
                    running = true,
                    proxyPort = proxyPort,
                    viewerPort = viewerHost.port,
                    viewerUrl = "http://127.0.0.1:${viewerHost.port}/index.html?token=$newToken",
                    sessionToken = newToken,
                )
            startWatchdog()
            registerShutdownHook()
        } catch (startError: Exception) {
            proxyServer.stop()
            viewerHost.stop()
            sessionToken = null
            _state.value = WinscopeServiceSnapshot(running = false, errorMessage = startError.message)
            throw startError
        }
    }

    /** Stops both endpoints and invalidates the session token. */
    public fun stop() {
        proxyServer.stop()
        viewerHost.stop()
        watchdog?.cancel()
        watchdog = null
        sessionToken = null
        _state.value = WinscopeServiceSnapshot(running = false)
        shutdownHook?.let { hook -> Runtime.getRuntime().removeShutdownHook(hook) }
        shutdownHook = null
    }

    /**
     * App-exit safety net: when the application closes without an explicit stop, end
     * any in-progress device capture (otherwise the device-side trace shells and the
     * detached perfetto session orphan) and release both ports.
     */
    private fun registerShutdownHook() {
        if (shutdownHook != null) return
        val hook =
            Thread {
                try {
                    val serials = engine.activeCaptureSerials()
                    if (serials.isNotEmpty()) {
                        println("[winscope] app exiting: ending active captures on " + serials)
                        kotlinx.coroutines.runBlocking {
                            serials.forEach { serial -> runCatching { engine.endCapture(serial) } }
                        }
                    }
                } catch (cleanupError: Throwable) {
                    println("[winscope] shutdown cleanup error: " + cleanupError)
                } finally {
                    runCatching { proxyServer.stop() }
                    runCatching { viewerHost.stop() }
                    println("[winscope] shutdown cleanup done")
                }
            }
        shutdownHook = hook
        Runtime.getRuntime().addShutdownHook(hook)
    }

    /** Opens the system browser at the viewer entry page with the session token injected. */
    public fun openViewer() {
        val token = checkNotNull(sessionToken) { "Winscope service is not running" }
        val port = checkNotNull(viewerHost.port) { "Winscope viewer host is not running" }
        browserLauncher("http://127.0.0.1:$port/index.html?token=$token")
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                while (isActive) {
                    delay(1_000)
                    runCatching { engine.sweepExpired() }
                }
            }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private companion object {
        private fun openSystemBrowser(url: String) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
                return
            }
            val os = System.getProperty("os.name").lowercase()
            val command =
                when {
                    os.contains("win") -> listOf("cmd", "/c", "start", "", url)
                    os.contains("mac") -> listOf("open", url)
                    else -> listOf("xdg-open", url)
                }
            ProcessBuilder(command).start()
        }
    }
}
