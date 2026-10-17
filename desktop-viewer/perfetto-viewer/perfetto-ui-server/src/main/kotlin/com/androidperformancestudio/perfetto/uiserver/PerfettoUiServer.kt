package com.androidperformancestudio.perfetto.uiserver

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.SimpleFileServer
import java.awt.Desktop
import java.net.BindException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves the active trace bytes over a short-lived local HTTP endpoint so a browser-side Perfetto UI
 * can fetch them via `?url=`, and optionally hosts local Perfetto UI assets when the user asks for
 * the self-hosted UI.
 *
 * The service is started only by an explicit user action and stopped when the page deactivates or
 * disposes. Once the official UI has fetched the trace it caches it in IndexedDB and rewrites the
 * URL to a `local_cache_key`, so the endpoint is no longer needed for refreshes.
 */
class PerfettoUiServer(
    private val preferredPort: Int = DEFAULT_PORT,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var currentTraceFile: Path? = null
    private var hasUiAssets: Boolean = false
    val isRunning: Boolean get() = server != null

    /** Port actually bound after [start]; `null` while the server is stopped. */
    val port: Int?
        get() = server?.address?.port

    /**
     * Starts the server in [mode]. A running server is reused; a trace-only server is never
     * downgraded, and starting it in [ServeMode.TRACE_AND_UI] fails when it cannot host assets.
     */
    fun start(
        uiAssetsDir: Path?,
        mode: ServeMode = ServeMode.TRACE_ONLY,
    ): StudioResult<Unit> {
        if (server != null) return StudioResult.Success(Unit)
        return try {
            server =
                createServer().apply {
                    createContext("/trace") { exchange ->
                        // Context covers /trace* so a filename suffix (e.g. /trace_file.perfetto-trace)
                        // resolves to the same handler and the trace keeps a recognizable name.
                        // Chrome's Private Network Access sends a preflight for requests from a
                        // public site (ui.perfetto.dev) to localhost and requires this acknowledgement,
                        // otherwise the fetch fails with "TypeError: Failed to fetch". Echo the
                        // request Origin instead of "*" — some Chrome versions reject the wildcard
                        // on private-network responses.
                        val origin = exchange.requestHeaders.getFirst("Origin")
                        exchange.responseHeaders.add("Access-Control-Allow-Origin", origin ?: "*")
                        exchange.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
                        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS")
                        if (origin != null) {
                            exchange.responseHeaders.add("Access-Control-Allow-Credentials", "true")
                            exchange.responseHeaders.add("Vary", "Origin")
                        }
                        when (exchange.requestMethod) {
                            "OPTIONS" -> exchange.sendResponseHeaders(204, -1)
                            "HEAD" -> {
                                val traceFile = currentTraceFile
                                if (traceFile != null && Files.exists(traceFile)) {
                                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                                    exchange.sendResponseHeaders(200, Files.size(traceFile))
                                } else {
                                    exchange.sendResponseHeaders(404, -1)
                                }
                            }
                            else -> {
                                val traceFile = currentTraceFile
                                if (traceFile != null && Files.exists(traceFile)) {
                                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                                    exchange.sendResponseHeaders(200, Files.size(traceFile))
                                    exchange.responseBody.use { output -> Files.copy(traceFile, output) }
                                } else {
                                    exchange.sendResponseHeaders(404, -1)
                                }
                            }
                        }
                    }
                    if (mode == ServeMode.TRACE_AND_UI &&
                        uiAssetsDir != null &&
                        Files.isDirectory(uiAssetsDir) &&
                        uiAssetsDir.resolve("index.html").toFile().exists()
                    ) {
                        createContext("/", SimpleFileServer.createFileHandler(uiAssetsDir))
                        hasUiAssets = true
                    } else {
                        createContext("/") { exchange ->
                            exchange.sendResponseHeaders(404, -1)
                        }
                        hasUiAssets = false
                    }
                    executor = Executors.newSingleThreadExecutor().also { this@PerfettoUiServer.executor = it }
                    start()
                }
            StudioResult.Success(Unit)
        } catch (e: Exception) {
            stop()
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "SERVER_START_FAILED",
                    message = e.message ?: "Unknown error",
                    cause = e,
                ),
            )
        }
    }

    /**
     * Opens the active trace in a browser-side Perfetto UI. When local UI assets are hosted the
     * browser targets the locally hosted UI at the same origin; otherwise it targets the official
     * https://ui.perfetto.dev via `?url=`.
     */
    fun openTrace(traceFile: Path): StudioResult<Unit> =
        try {
            if (!Files.isRegularFile(traceFile)) {
                return StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.IO,
                        code = "TRACE_FILE_NOT_FOUND",
                        message = "Trace file does not exist: $traceFile",
                    ),
                )
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "BROWSER_NOT_SUPPORTED",
                        message = "No system browser is available to open the Perfetto UI",
                    ),
                )
            }
            val boundPort = port ?: return StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "SERVER_NOT_RUNNING",
                    message = "The trace-serving endpoint is not running",
                ),
            )
            currentTraceFile = traceFile
            // Use 127.0.0.1 rather than "localhost": the JVM HttpServer binds the IPv4 wildcard
            // address, so a browser that resolves localhost to ::1 first would fail to connect.
            // A named path (instead of bare /trace) also gives the trace a filename in the UI,
            // matching what record_android_trace produces.
            val traceUrl = "http://127.0.0.1:$boundPort/trace_file.perfetto-trace"
            val targetUrl =
                if (hasUiAssets) {
                    "http://127.0.0.1:$boundPort/#!/?url=$traceUrl&referrer=record_android_trace"
                } else {
                    "https://ui.perfetto.dev/#!/?url=$traceUrl&referrer=record_android_trace"
                }
            Desktop.getDesktop().browse(URI.create(targetUrl))
            StudioResult.Success(Unit)
        } catch (exception: Exception) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "BROWSER_OPEN_FAILED",
                    message = exception.message ?: "Failed to open the Perfetto UI",
                    cause = exception,
                ),
            )
        }

    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        currentTraceFile = null
        hasUiAssets = false
    }

    /** Prefers [preferredPort]; falls back to an OS-assigned ephemeral port when it is occupied. */
    private fun createServer(): HttpServer =
        try {
            HttpServer.create(InetSocketAddress(preferredPort), 0)
        } catch (e: BindException) {
            HttpServer.create(InetSocketAddress(0), 0)
        }

    enum class ServeMode { TRACE_ONLY, TRACE_AND_UI }

    companion object {
        const val DEFAULT_PORT = 8090

        fun tryFindUiAssetsDir(
            repoRoot: Path = Paths.get(".").toAbsolutePath(),
            configuredPath: String? = System.getProperty(PERFETTO_UI_DIST_PROPERTY),
            environmentPath: String? = System.getenv(PERFETTO_UI_DIST_ENVIRONMENT),
            applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
        ): Path? {
            sequenceOf(
                configuredPath?.takeIf(String::isNotBlank)?.let(Path::of),
                environmentPath?.takeIf(String::isNotBlank)?.let(Path::of),
                applicationResourcesPath?.takeIf(String::isNotBlank)?.let { Path.of(it).resolve("perfetto-ui") },
            ).filterNotNull()
                .firstOrNull(::isUiAssetsDirectory)
                ?.let { return it }
            val roots = generateSequence(repoRoot.normalize()) { it.parent }.take(5).toList()
            roots
                .asSequence()
                .map { it.resolve("third_party/perfetto/out/ui/dist") }
                .firstOrNull(::isUiAssetsDirectory)
                ?.let { return it }
            val downloaded =
                Paths.get(
                    System.getProperty("user.home"),
                    ".android-performance-studio",
                    "tools",
                    "perfetto",
                    "ui",
                )
            if (isUiAssetsDirectory(downloaded)) {
                return downloaded
            }
            return null
        }

        private fun isUiAssetsDirectory(path: Path): Boolean = Files.isDirectory(path) && Files.isRegularFile(path.resolve("index.html"))

        private const val PERFETTO_UI_DIST_PROPERTY = "androidperformancestudio.perfettoUiDist"
        private const val PERFETTO_UI_DIST_ENVIRONMENT = "ANDROID_PERFORMANCE_STUDIO_PERFETTO_UI_DIST"
        private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
