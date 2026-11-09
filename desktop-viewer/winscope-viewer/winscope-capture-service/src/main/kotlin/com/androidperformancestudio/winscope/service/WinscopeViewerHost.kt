@file:Suppress("MagicNumber", "ThrowsCount", "UseCheckOrError")

package com.androidperformancestudio.winscope.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors

/**
 * Hosts the packaged Winscope viewer resource closure over loopback so the bundled
 * viewer can be opened in a system browser. Evidence disciplines: `no-store` on
 * evidence-ish content, no directory listing, and closure integrity verification
 * (checksum manifest) before hosting.
 */
public class WinscopeViewerHost(
    private val preferredPort: Int = DEFAULT_PORT,
) {
    public companion object {
        public const val DEFAULT_PORT: Int = 8000
        private val MIME_BY_SUFFIX: Map<String, String> =
            mapOf(
                "html" to "text/html",
                "js" to "application/javascript",
                "css" to "text/css",
                "svg" to "image/svg+xml",
                "json" to "application/json",
                "wasm" to "application/wasm",
                "png" to "image/png",
                "map" to "application/json",
                "txt" to "text/plain",
            )
    }

    private var server: HttpServer? = null

    public val isRunning: Boolean
        get() = server != null

    public val port: Int?
        get() = server?.address?.port

    /**
     * Starts hosting [viewerRoot]. [manifest] maps closure-relative paths to their
     * expected SHA-256; a mismatch or missing tracked file rejects startup.
     */
    public fun start(
        viewerRoot: Path,
        manifest: Map<String, String>,
    ) {
        check(server == null) { "Winscope viewer host is already running" }
        verifyClosure(viewerRoot, manifest)
        var lastError: IOException? = null
        for (attempt in 0 until 10) {
            val candidate = preferredPort + attempt
            try {
                val created = HttpServer.create(InetSocketAddress("127.0.0.1", candidate), 0)
                created.apply {
                    createContext("/") { exchange -> serveFile(exchange, viewerRoot) }
                    executor = Executors.newCachedThreadPool()
                    start()
                }
                server = created
                return
            } catch (bindError: IOException) {
                lastError = bindError
            }
        }
        throw IllegalStateException(
            "No port available for the Winscope viewer host starting at $preferredPort",
            lastError,
        )
    }

    public fun stop() {
        server?.stop(0)
        server = null
    }

    private fun serveFile(
        exchange: HttpExchange,
        viewerRoot: Path,
    ) {
        try {
            val requested = exchange.requestURI.path.trimStart('/')
            val target =
                if (requested.isBlank()) viewerRoot.resolve("index.html") else viewerRoot.resolve(requested).normalize()
            // Directory listing is never produced; only real files inside the closure resolve.
            if (!target.startsWith(viewerRoot) || !Files.isRegularFile(target)) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val mime = MIME_BY_SUFFIX[target.toString().substringAfterLast('.', "")] ?: "application/octet-stream"
            exchange.responseHeaders.add("Content-Type", mime)
            exchange.responseHeaders.add("Cache-Control", "no-store")
            val bytes = Files.readAllBytes(target)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }

    private fun verifyClosure(
        viewerRoot: Path,
        manifest: Map<String, String>,
    ) {
        if (!Files.isDirectory(viewerRoot) || !Files.isRegularFile(viewerRoot.resolve("index.html"))) {
            throw IllegalStateException("Winscope viewer closure is missing or has no index.html: $viewerRoot")
        }
        manifest.forEach { (relativePath, expectedSha256) ->
            val file = viewerRoot.resolve(relativePath)
            if (!Files.isRegularFile(file)) {
                throw IllegalStateException("Winscope viewer closure file missing: $relativePath")
            }
            val actual = sha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "Winscope viewer closure checksum mismatch for $relativePath " +
                        "(expected $expectedSha256, got $actual)",
                )
            }
        }
    }

    public fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
