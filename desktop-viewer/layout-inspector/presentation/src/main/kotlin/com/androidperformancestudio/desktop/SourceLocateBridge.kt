package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object SourceLocateBridge {
    const val DEFAULT_PORT = 7821
    private const val MAX_ANCESTORS = 10
    private const val MAX_SIBLINGS = 15

    fun pushLocateRequest(
        scope: CoroutineScope,
        port: Int = bridgePort(),
        packageName: String?,
        activityClass: String?,
        windowTitle: String?,
        windowType: String?,
        root: UiNode?,
        targetId: String,
    ) {
        if (root == null) return
        val path = findPath(root, targetId)
        if (path == null) return
        val target = path.last()
        val ancestors = path.dropLast(1).takeLast(MAX_ANCESTORS)
        val siblings = if (path.size >= 2) {
            path[path.size - 2].children
                .filter { it.id != targetId }
                .take(MAX_SIBLINGS)
        } else {
            emptyList()
        }
        val body = buildJsonObject {
            packageName?.let { put("packageName", it) }
            activityClass?.let { put("activityClass", it) }
            windowTitle?.let { put("windowTitle", it) }
            windowType?.let { put("windowType", it) }
            put("viewClassName", target.className)
            (target as? ViewNode)?.resourceName?.let { put("resourceName", it) }
            val text = nodeText(target)
            text?.let { put("text", it) }
            put("bounds", "${target.bounds.left},${target.bounds.top},${target.bounds.right},${target.bounds.bottom}")
            if (ancestors.isNotEmpty()) {
                put("ancestors", buildJsonArray { ancestors.forEach { add(it.toSummaryJson()) } })
            }
            if (siblings.isNotEmpty()) {
                put("siblings", buildJsonArray { siblings.forEach { add(it.toSummaryJson()) } })
            }
        }.toString()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val client = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/pending"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    System.err.println("[locate-bridge] push failed: ${response.statusCode()} ${response.body()}")
                }
            }.onFailure { System.err.println("[locate-bridge] push error: ${it.message}") }
        }
    }

    fun findPath(root: UiNode, targetId: String): List<UiNode>? {
        if (root.id == targetId) return listOf(root)
        root.children.forEach { child ->
            findPath(child, targetId)?.let { return listOf(root) + it }
        }
        return null
    }

    fun findById(root: UiNode, targetId: String): UiNode? = findPath(root, targetId)?.lastOrNull()

    private fun nodeText(node: UiNode): String? = when (node) {
        is ViewNode -> node.text
        is ComposeNode -> node.text
    }

    private fun UiNode.toSummaryJson() = buildJsonObject {
        put("className", className)
        (this@toSummaryJson as? ViewNode)?.resourceName?.let { put("resourceName", it) }
        nodeText(this@toSummaryJson)?.let { put("text", it) }
    }

    fun bridgePort(): Int =
        (System.getenv("APS_BRIDGE_PORT")?.toIntOrNull() ?: DEFAULT_PORT)
}
