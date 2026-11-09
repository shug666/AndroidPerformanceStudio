@file:Suppress("LongMethod", "ReturnCount", "MaxLineLength")

package com.androidperformancestudio.winscope.service

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.defaultAdbCommandExecutable
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Real-device capture chain end-to-end (task 7.3): requires a rooted device connected
 * via adb. Drives the production service exactly the way the bundled viewer does —
 * `POST /start`, keep-alive `GET /status`, `POST /end`, `GET /fetch` — and verifies
 * the fused perfetto trace (modern path) comes back and is archived locally.
 *
 *     ./gradlew :winscope-capture-service:test \
 *         -Dwinscope.it.device=true \
 *         -Dwinscope.it.archive=/tmp/winscope-device-it
 */
@EnabledIfSystemProperty(named = "winscope.it.device", matches = "true")
class WinscopeDeviceCaptureIT {
    @Test
    fun `device capture chain start end fetch archive`() {
        val archiveRoot = System.getProperty("winscope.it.archive")?.let { Path.of(it) }
        val resources = moduleRoot()
        val closure = resources.resolve("winscope-viewer")
        val manifest = loadManifest(resources.resolve("winscope-viewer-manifest.json"))

        val adbClient: AdbClient =
            DefaultAdbClient(defaultAdbCommandExecutable(AdbConfiguration(androidSdkPath = null)))
        val service =
            WinscopeCaptureService(
                adbClient = adbClient,
                viewerResourceRoot = closure,
                closureManifest = manifest,
                proxyPort = 5544,
                preferredViewerPort = 8100,
                browserLauncher = {},
                archiveRoot = archiveRoot,
            )
        service.start()
        try {
            val devicesJson = get("http://127.0.0.1:5544/devices/", token = service.sessionToken())
            assertTrue(devicesJson.contains("\"authorized\":true"), "device must be listed: $devicesJson")

            // The viewer posts the selected trace targets; on a modern device the proxy
            // runs the fused perfetto path and skips the legacy equivalents.
            val startPayload =
                """
                [
                  {"name":"window_trace","config":[{"key":"tracinglevel","value":"debug"},{"key":"tracingtype","value":"frame"},{"key":"wmbuffersize","value":"16000"}]},
                  {"name":"layers_trace","config":[{"key":"sfbuffersize","value":"16000"}]},
                  {"name":"transition_traces","config":[]},
                  {"name":"perfetto_trace","config":[]}
                ]
                """.trimIndent()
            val startResponse = post("http://127.0.0.1:5544/start/0123456789abcdef", startPayload, service.sessionToken())
            assertEquals("[]", startResponse, "no warnings expected on a clean modern device")

            // Browser keep-alives every ~1s while recording.
            repeat(3) {
                Thread.sleep(1500)
                get("http://127.0.0.1:5544/status/0123456789abcdef", token = service.sessionToken())
            }

            val errors = post("http://127.0.0.1:5544/end/0123456789abcdef", "[]", service.sessionToken())
            assertEquals("[]", errors, "capture must end cleanly: $errors")

            val fetched = get("http://127.0.0.1:5544/fetch/0123456789abcdef", token = service.sessionToken())
            assertTrue(
                fetched.contains("trace.perfetto-trace.gz") || fetched.contains("wm_trace.winscope.gz"),
                "expected a trace artifact, got: $fetched",
            )
            // Modern device: the fused perfetto trace is the expected artifact.
            assertTrue(fetched.contains("trace.perfetto-trace.gz"), "modern path expected: $fetched")

            // Dual-path archival: the same bytes landed in the archive root with metadata.
            if (archiveRoot != null) {
                val sessions = Files.list(archiveRoot).toList()
                assertTrue(sessions.isNotEmpty(), "archive session directory missing")
                val session = sessions.first()
                assertTrue(Files.isRegularFile(session.resolve("trace.perfetto-trace")))
                val meta = Files.readString(session.resolve("capture-meta.json"))
                assertTrue(meta.contains("0123456789abcdef"))
                assertTrue(meta.contains("perfetto_trace"))
            }

            // Device tracing sessions are released after the capture.
            val query = runBlocking { adbClient.shell("0123456789abcdef", listOf("perfetto", "--query")) }.stdout
            assertTrue(
                !query.contains("winscope proxy perfetto tracing"),
                "stale winscope perfetto session must not linger after /end:\n$query",
            )
        } finally {
            service.stop()
        }
    }

    private fun moduleRoot(): Path {
        var dir = Paths.get(System.getProperty("user.dir"))
        repeat(6) {
            if (dir.resolve("winscope-resources/winscope-viewer/index.html").toFile().exists()) {
                return dir.resolve("winscope-resources")
            }
            dir = dir.parent ?: dir
        }
        error("winscope-resources directory not found from ${System.getProperty("user.dir")}")
    }

    private fun loadManifest(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val json = Json.parseToJsonElement(Files.readString(file))
        val files = json.jsonObject["files"]?.jsonObject ?: return emptyMap()
        return files.map { (path, checksum) -> path to checksum.jsonPrimitive.content }.toMap()
    }

    private fun get(
        url: String,
        token: String?,
    ): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        token?.let { connection.setRequestProperty(WinscopeProxyServer.TOKEN_HEADER, it) }
        val status = connection.responseCode
        val body =
            try {
                connection.inputStream.bufferedReader().readText()
            } catch (ioError: java.io.IOException) {
                connection.errorStream?.bufferedReader()?.readText() ?: ioError.message ?: "io error"
            }
        if (status >= 400) {
            throw AssertionError("HTTP $status for $url: $body")
        }
        return body
    }

    private fun post(
        url: String,
        body: String,
        token: String?,
    ): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        token?.let { connection.setRequestProperty(WinscopeProxyServer.TOKEN_HEADER, it) }
        val payload = body.toByteArray()
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Content-Length", payload.size.toString())
        connection.outputStream.use { it.write(payload) }
        return (connection.errorStream ?: connection.inputStream).bufferedReader().readText()
    }
}
