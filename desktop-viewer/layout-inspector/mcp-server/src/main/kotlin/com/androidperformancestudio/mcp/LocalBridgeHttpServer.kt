package com.androidperformancestudio.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json

class LocalBridgeHttpServer(
    private val port: Int,
    private val pending: PendingLocateRequests,
) {
    private var server: HttpServer? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun start() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/pending") { handlePending(it) }
        s.createContext("/health") { handleHealth(it) }
        s.createContext("/") { handleRoot(it) }
        s.executor = null
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    fun port(): Int = port

    private fun handlePending(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "POST" -> {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val request = runCatching {
                    json.decodeFromString(PendingLocateRequest.serializer(), body)
                }.getOrElse {
                    respond(exchange, 400, """{"error":"invalid body: ${it.message}"}""")
                    return
                }
                pending.push(request)
                System.err.println("[aps-mcp] bridge received POST: viewClass=${request.viewClassName} id=${request.resourceName} pending=${pending.size()}")
                respond(exchange, 200, """{"accepted":true,"pending":${pending.size()}}""")
            }
            "GET" -> {
                val peek = pending.peek()
                if (peek != null) {
                    respond(exchange, 200, json.encodeToString(PendingLocateRequest.serializer(), peek))
                } else {
                    respond(exchange, 404, """{"pending":0}""")
                }
            }
            else -> respond(exchange, 405, "Method Not Allowed")
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        respond(exchange, 200, """{"status":"ok","pending":${pending.size()}}""")
    }

    private fun handleRoot(exchange: HttpExchange) {
        respond(
            exchange,
            200,
            """{"service":"aps-layout-inspector-mcp","bridgePort":$port,"pending":${pending.size()}}""",
        )
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}