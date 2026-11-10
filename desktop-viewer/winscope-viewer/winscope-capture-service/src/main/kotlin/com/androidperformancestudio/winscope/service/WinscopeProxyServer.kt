@file:Suppress("TooManyFunctions", "MagicNumber", "MaxLineLength")

package com.androidperformancestudio.winscope.service

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.winscope.device.WinscopeBadRequestException
import com.androidperformancestudio.winscope.device.WinscopeConfigEntry
import com.androidperformancestudio.winscope.device.WinscopeDeviceCaptureEngine
import com.androidperformancestudio.winscope.device.WinscopeDeviceCaptureException
import com.androidperformancestudio.winscope.device.WinscopeDeviceCatalog
import com.androidperformancestudio.winscope.device.WinscopeTraceRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Loopback-only implementation of the Winscope ADB proxy protocol (client version
 * `4.0.8`): token-gated endpoints, `Winscope-Proxy-Version` header, and the endpoint
 * set the bundled viewer probes at the hardcoded `http://localhost:5544` origin.
 *
 * Protocol contract: `docs/winscope-proxy-protocol.md`.
 */
public class WinscopeProxyServer(
    private val engine: WinscopeDeviceCaptureEngine,
    private val catalog: WinscopeDeviceCatalog,
    private val adbClient: AdbClient,
    private val port: Int = DEFAULT_PORT,
) {
    public companion object {
        public const val DEFAULT_PORT: Int = 5544
        public const val PROXY_VERSION: String = "4.0.8"
        public const val VERSION_HEADER: String = "Winscope-Proxy-Version"
        public const val TOKEN_HEADER: String = "Winscope-Token"
        private const val BAD_TOKEN_BODY: String =
            "Bad Winscope authorization token!\nThis is Winscope ADB proxy.\n"
        private const val BAD_REQUEST_PREFIX: String =
            "Bad request!\nThis is Winscope ADB proxy.\n\n"
        private val SERIAL_PATTERN: Regex = Regex("[A-Za-z0-9._:\\-]+")
    }

    private var server: HttpServer? = null

    @Volatile
    private var token: String? = null

    public val isRunning: Boolean
        get() = server != null

    /** Actual bound port after [start]; `null` while stopped (supports ephemeral test ports). */
    public val boundPort: Int?
        get() = server?.address?.port

    /** Returns the loopback URL the viewer's proxy detection targets. */
    public val proxyUrl: String
        get() = "http://localhost:$port"

    /**
     * Starts serving. Fails (does not pick another port) when [port] is occupied —
     * the viewer hardcodes the port, so drifting would silently break detection.
     */
    public fun start(newToken: String) {
        check(server == null) { "Winscope proxy server is already running" }
        token = newToken
        val created =
            try {
                HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            } catch (bindError: BindException) {
                throw IllegalStateException(
                    "Port $port is already in use - close any other Winscope proxy (winscope_proxy.py) and retry",
                    bindError,
                )
            }
        created.apply {
            createContext("/") { exchange -> route(exchange) }
            executor = Executors.newCachedThreadPool()
            start()
        }
        server = created
    }

    public fun stop() {
        server?.stop(0)
        server = null
        token = null
    }

    // ------------------------------------------------------------------ routing

    private fun route(exchange: HttpExchange) {
        try {
            // CORS preflight must pass WITHOUT a token: browsers never attach custom
            // headers to the OPTIONS preflight, and the reference implementation's
            // do_OPTIONS bypasses token validation for exactly this reason.
            if (exchange.requestMethod == "OPTIONS") {
                respond(exchange, 200, "GET,POST".toByteArray(), "text/txt")
                return
            }
            val requestToken = exchange.requestHeaders.getFirst(TOKEN_HEADER)
            if (requestToken == null || requestToken != token) {
                respond(exchange, 403, BAD_TOKEN_BODY.toByteArray(), "text/txt")
                return
            }
            val segments =
                exchange.requestURI.path
                    .trim('/')
                    .split('/')
                    .filter(String::isNotEmpty)
            if (segments.isEmpty()) {
                respondBadRequest(exchange, "No endpoint specified")
                return
            }
            when (exchange.requestMethod) {
                "GET" -> routeGet(exchange, segments)
                "POST" -> routePost(exchange, segments)
                else -> respondBadRequest(exchange, "Unsupported method ${exchange.requestMethod}")
            }
        } catch (bad: WinscopeBadRequestException) {
            respondBadRequest(exchange, bad.message)
        } catch (device: WinscopeDeviceCaptureException) {
            respond(exchange, 500, device.message.toByteArray(), "text/txt")
        } catch (
            error:
                @Suppress("SwallowedException")
                Exception,
        ) {
            // Unexpected handler failure: the message is returned to the viewer (the
            // reference implementation responds with repr(error) in the same shape).
            respond(exchange, 500, error.toString().toByteArray(), "text/txt")
        } finally {
            exchange.close()
        }
    }

    private fun routeGet(
        exchange: HttpExchange,
        segments: List<String>,
    ) {
        when (segments.first()) {
            "devices" -> respond(exchange, 200, devicesJson().toByteArray(), "text/json")
            "checkwayland" -> respond(exchange, 200, checkWayland().toByteArray(), "text/json")
            "status" -> handleStatus(exchange, segments.drop(1))
            "fetch" -> handleFetch(exchange, segments.drop(1))
            else -> respondBadRequest(exchange, "Unknown endpoint /${segments.first()}/")
        }
    }

    private fun routePost(
        exchange: HttpExchange,
        segments: List<String>,
    ) {
        when (segments.first()) {
            "start" -> handleStart(exchange, segments.drop(1), isDump = false)
            "end" -> handleEnd(exchange, segments.drop(1))
            "dump" -> handleStart(exchange, segments.drop(1), isDump = true)
            else -> respondBadRequest(exchange, "Unknown endpoint /${segments.first()}/")
        }
    }

    // ---------------------------------------------------------------- endpoints

    private fun devicesJson(): String =
        runBlocking { catalog.listDevices() }.let { devices ->
            buildJsonObject {
                devices.forEach { (serial, info) ->
                    put(
                        serial,
                        buildJsonObject {
                            put("authorized", info.authorized)
                            put("model", info.model)
                            put("displays", buildJsonArray { info.displays.forEach { add(JsonPrimitive(it)) } })
                            put("screenrecord_version", info.screenrecordVersion)
                        },
                    )
                }
            }.toString()
        }

    private fun checkWayland(): String =
        runBlocking {
            val devices = catalog.listDevices().values.toList()
            if (devices.size != 1 || !devices[0].authorized || devices[0].model.isEmpty()) {
                false
            } else {
                val result =
                    runCatching {
                        adbClient
                            .shell(devices[0].serial, listOf("service", "check", "Wayland"))
                            .stdout
                    }.getOrDefault("")
                !result.contains("not found")
            }
        }.let { if (it) "true" else "false" }

    private fun handleStatus(
        exchange: HttpExchange,
        path: List<String>,
    ) {
        val serial = requireSerial(path)
        if (!engine.hasActiveCapture(serial)) {
            respondBadRequest(exchange, "No trace in progress for $serial")
            return
        }
        engine.resetKeepAlive(serial)
        // Python str(bool) semantics: the viewer string-compares "True" exactly and
        // treats any other body as a dead trace, auto-stopping the capture.
        val alive = if (engine.isCaptureAlive(serial)) "True" else "False"
        respond(exchange, 200, alive.toByteArray(), "text/plain")
    }

    private fun handleFetch(
        exchange: HttpExchange,
        path: List<String>,
    ) {
        val serial = requireSerial(path)
        val files = runBlocking { engine.fetchFiles(serial) }
        respond(exchange, 200, encodeFetchPayload(files).toByteArray(), "text/json")
    }

    private fun handleStart(
        exchange: HttpExchange,
        path: List<String>,
        isDump: Boolean,
    ) {
        val serial = requireSerial(path)
        val requests = parseRequests(exchange)
        val result =
            runBlocking {
                if (isDump) engine.dump(serial, requests) else engine.startCapture(serial, requests)
            }
        respond(
            exchange,
            200,
            encodeList(result.warnings).toByteArray(),
            if (isDump) "text/plain" else "text/json",
        )
    }

    private fun handleEnd(
        exchange: HttpExchange,
        path: List<String>,
    ) {
        val serial = requireSerial(path)
        val errors = runBlocking { engine.endCapture(serial) }
        respond(exchange, 200, encodeList(errors).toByteArray(), "text/plain")
    }

    // ------------------------------------------------------------------ helpers

    private fun requireSerial(path: List<String>): String {
        val serial = path.firstOrNull() ?: throw WinscopeBadRequestException("Device id not specified")
        if (!SERIAL_PATTERN.matches(serial)) {
            throw WinscopeBadRequestException("Device id not specified")
        }
        return serial
    }

    @Suppress("SwallowedException")
    private fun parseRequests(exchange: HttpExchange): List<WinscopeTraceRequest> {
        val length =
            exchange.requestHeaders.getFirst("Content-Length")?.toIntOrNull()
                ?: throw WinscopeBadRequestException("Missing Content-Length header")
        val body = exchange.requestBody.readNBytes(length).toString(Charsets.UTF_8)
        val root =
            try {
                Json.parseToJsonElement(body)
            } catch (parseError: Exception) {
                // The viewer receives the reference-shaped 400 message; the raw parse
                // error is not propagated over the wire.
                throw WinscopeBadRequestException("Malformed JSON body")
            }
        return root.jsonArray.map { entry ->
            val obj = entry.jsonObject
            WinscopeTraceRequest(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                config =
                    (obj["config"] as? JsonArray)?.map { configEntry ->
                        val configObj = configEntry.jsonObject
                        WinscopeConfigEntry(
                            key = configObj["key"]?.jsonPrimitive?.content ?: "",
                            value = configObj["value"]?.toJsonValue(),
                        )
                    } ?: emptyList(),
            )
        }
    }

    private fun JsonElement.toJsonValue(): Any? =
        when (this) {
            is JsonPrimitive ->
                if (isString) content else content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
            is JsonArray -> map { element -> element.jsonPrimitive.content }
            else -> null
        }

    /** `/fetch` shape: `{ "<file name>.gz": [ "<base64-mime>" ] }`. */
    private fun encodeFetchPayload(files: Map<String, List<String>>): String =
        buildJsonObject {
            files.forEach { (name, chunks) ->
                put(name, buildJsonArray { chunks.forEach { add(JsonPrimitive(it)) } })
            }
        }.toString()

    private fun encodeList(items: List<String>): String = buildJsonArray { items.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun respondBadRequest(
        exchange: HttpExchange,
        message: String,
    ) {
        respond(exchange, 400, (BAD_REQUEST_PREFIX + message).toByteArray(), "text/txt")
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
        mime: String,
    ) {
        val headers = exchange.responseHeaders
        headers.add("Content-Type", mime)
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate")
        headers.add("Access-Control-Allow-Origin", "*")
        headers.add("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "$TOKEN_HEADER, Content-Type, Content-Length")
        headers.add("Access-Control-Expose-Headers", VERSION_HEADER)
        headers.add(VERSION_HEADER, PROXY_VERSION)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }
}
