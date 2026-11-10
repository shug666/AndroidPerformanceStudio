package com.androidperformancestudio.winscope.service

import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.winscope.device.WinscopeDeviceCaptureEngine
import com.androidperformancestudio.winscope.device.WinscopeDeviceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration

/**
 * Contract tests for the Winscope proxy protocol against the reference behavior
 * (`docs/winscope-proxy-protocol.md`): token enforcement, version header, no-store,
 * no directory listing, endpoint shapes, and error mapping.
 */
class WinscopeProxyServerTest {
    private val token = "test-token"

    private class FakeAdbClient : AdbClient {
        val shells = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        var shellResponses: MutableList<String> = mutableListOf()
        var binaryResponses: MutableList<ByteArray> = mutableListOf()
        val pullResponses: ArrayDeque<ByteArray> = ArrayDeque()

        override suspend fun listDevices(): List<AdbDevice> =
            listOf(AdbDevice(serial = "FAKE001", state = AdbDeviceState.ONLINE, model = "Fake Model"))

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            shells.add(arguments)
            val response = if (shellResponses.isEmpty()) "" else shellResponses.removeAt(0)
            return AdbTextResult(exitCode = 0, stdout = response, stderr = "", duration = Duration.ZERO)
        }

        override suspend fun execOut(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbBinaryResult {
            val response = if (binaryResponses.isEmpty()) ByteArray(0) else binaryResponses.removeAt(0)
            return AdbBinaryResult(exitCode = 0, stdout = response, stderr = ByteArray(0), duration = Duration.ZERO)
        }

        override suspend fun push(
            serial: String,
            localPath: java.nio.file.Path,
            remotePath: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun pull(
            serial: String,
            remotePath: String,
            localPath: java.nio.file.Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            java.nio.file.Files
                .write(localPath, pullResponses.removeFirstOrNull() ?: ByteArray(0))
            return AdbTextResult(0, "", "", Duration.ZERO)
        }

        override suspend fun forward(
            serial: String,
            local: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun removeForward(
            serial: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun reverse(
            serial: String,
            remote: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun removeReverse(
            serial: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun bugreport(
            serial: String,
            outputPath: java.nio.file.Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)
    }

    private class TestService(
        adbClient: AdbClient,
    ) {
        val engine = WinscopeDeviceCaptureEngine(adbClient, kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
        val catalog = WinscopeDeviceCatalog(adbClient, cacheTtlMillis = 0)
        val server = WinscopeProxyServer(engine, catalog, adbClient, port = 0)
    }

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun startServer(adbClient: AdbClient): Triple<WinscopeProxyServer, Int, TestService> {
        val service = TestService(adbClient)
        service.server.start(token)
        val port = service.server.boundPort ?: error("server did not bind")
        return Triple(service.server, port, service)
    }

    private fun request(
        port: Int,
        path: String,
        method: String = "GET",
        tokenHeader: String? = token,
        body: ByteArray? = null,
    ): Pair<Int, String> {
        val connection = URI.create("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        tokenHeader?.let { connection.setRequestProperty(WinscopeProxyServer.TOKEN_HEADER, it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Content-Length", body.size.toString())
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val response = (connection.errorStream ?: connection.inputStream).bufferedReader().readText()
        return status to response
    }

    @Test
    fun `missing token is rejected with 403 and reference body`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, body) = request(port, "/devices/", tokenHeader = null)
            assertEquals(403, status)
            assertTrue(body.contains("Bad Winscope authorization token"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cors preflight without token returns ok with cors headers`() {
        // Browsers never attach custom headers (Winscope-Token) to the OPTIONS
        // preflight; the reference implementation answers it without token checks.
        // A 403 here blocks every cross-origin viewer request (regression guard).
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val connection =
                URI
                    .create("http://127.0.0.1:$port/devices/")
                    .toURL()
                    .openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            assertEquals(200, connection.responseCode)
            assertEquals(
                WinscopeProxyServer.TOKEN_HEADER + ", Content-Type, Content-Length",
                connection.getHeaderField("Access-Control-Allow-Headers"),
            )
            assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `wrong token is rejected with 403`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, _) = request(port, "/devices/", tokenHeader = "nope")
            assertEquals(403, status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `every response carries the compatible version header and no-store`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val connection = URI.create("http://127.0.0.1:$port/devices/").toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty(WinscopeProxyServer.TOKEN_HEADER, token)
            connection.responseCode
            val versionHeader = connection.getHeaderField(WinscopeProxyServer.VERSION_HEADER)
            assertEquals(WinscopeProxyServer.PROXY_VERSION, versionHeader)
            val cacheControl = connection.getHeaderField("Cache-Control") ?: ""
            assertTrue(cacheControl.contains("no-store"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `devices payload is an object keyed by serial with reference fields`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, body) = request(port, "/devices/")
            assertEquals(200, status)
            val root = Json.parseToJsonElement(body).jsonObject
            val device = root["FAKE001"]?.jsonObject ?: error("serial key missing: $body")
            assertEquals(true, device["authorized"]?.jsonPrimitive?.content?.toBooleanStrict())
            assertEquals("Fake Model", device["model"]?.jsonPrimitive?.content)
            assertTrue(device.containsKey("displays"))
            assertTrue(device.containsKey("screenrecord_version"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unknown endpoint returns 400 with reference prefix`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, body) = request(port, "/nonsense/")
            assertEquals(400, status)
            assertTrue(body.contains("Unknown endpoint /nonsense/"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `status without active capture returns 400 reference message`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, body) = request(port, "/status/FAKE001")
            assertEquals(400, status)
            assertTrue(body.contains("No trace in progress for FAKE001"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start request payload is parsed and forwarded to the engine`() {
        val adb = FakeAdbClient()
        val (server, port, service) = startServer(adb)
        try {
            val body =
                """
                [{"name":"window_trace","config":[
                {"key":"tracinglevel","value":"debug"},
                {"key":"tracingtype","value":"frame"},
                {"key":"wmbuffersize","value":"16000"}]}]
                """.trimIndent().toByteArray()
            // Ordered shell responses: rm config, perfetto --query, 3x WM legacy config,
            // root check (uid 0), backup cleanup, spawned trace shell.
            adb.shellResponses.addAll(listOf("", "", "", "", "", "0", "", ""))
            val (status, responseBody) = request(port, "/start/FAKE001", method = "POST", body = body)
            assertEquals(200, status)
            // Fake perfetto --query returns empty, so no data source is available and
            // the legacy path applies: the WM legacy config commands must run in order.
            val snapshot = adb.shells.toList()
            val joined = snapshot.joinToString("\n") { it.joinToString(" ") }
            assertTrue("cmd window tracing frame" in joined)
            assertTrue("cmd window tracing level debug" in joined)
            val sizeIndex = joined.indexOf("cmd window tracing size 16000")
            val levelIndex = joined.indexOf("cmd window tracing level debug")
            assertTrue(sizeIndex > levelIndex, "buffer size must be configured last")
            assertEquals("[]", responseBody)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetch returns gzip base64 map keyed by filename dot gz`() {
        val adb = FakeAdbClient()
        adb.shellResponses.add("/data/local/tmp/last_winscope_tracing_session/wm_trace.winscope")
        adb.pullResponses.add("TRACE-BYTES".toByteArray())
        val (server, port, _) = startServer(adb)
        try {
            val (status, body) = request(port, "/fetch/FAKE001")
            assertEquals(200, status)
            val root = Json.parseToJsonElement(body).jsonObject
            assertTrue("wm_trace.winscope.gz" in root.keys, "actual: $body")
            val chunk =
                root["wm_trace.winscope.gz"]?.let {
                    (it as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content
                } ?: ""
            val decoded =
                java.util.Base64
                    .getMimeDecoder()
                    .decode(chunk)
            val gunzip =
                java.util.zip
                    .GZIPInputStream(decoded.inputStream())
                    .readBytes()
            assertEquals("TRACE-BYTES", String(gunzip))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetch with nothing collected returns empty object`() {
        val adb = FakeAdbClient()
        val (server, port, _) = startServer(adb)
        try {
            val (status, body) = request(port, "/fetch/FAKE001")
            assertEquals(200, status)
            assertEquals(
                "{}",
                Json
                    .parseToJsonElement(body)
                    .jsonObject.keys
                    .let { if (it.isEmpty()) "{}" else body },
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `end without active capture returns 400 reference message`() {
        val (server, port, _) = startServer(FakeAdbClient())
        try {
            val (status, body) = request(port, "/end/FAKE001", method = "POST", body = ByteArray(0))
            assertEquals(400, status)
            assertTrue(body.contains("No trace in progress for FAKE001"))
        } finally {
            server.stop()
        }
    }
}
