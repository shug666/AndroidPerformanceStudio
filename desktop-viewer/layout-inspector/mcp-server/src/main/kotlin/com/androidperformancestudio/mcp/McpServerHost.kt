package com.androidperformancestudio.mcp

import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.effectiveWindows
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpServerHost(
    private val captureService: ViewHierarchyCaptureService,
    private val sourceLocator: SourceLocator,
    private val pending: PendingLocateRequests,
) {
    fun createServer(): Server {
        val server = Server(
            Implementation(name = "aps-layout-inspector", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            ),
        )
        registerListDevices(server)
        registerCaptureViewHierarchy(server)
        registerLocateViewSource(server)
        registerPopPendingLocate(server)
        registerListPending(server)
        return server
    }

    private fun registerListDevices(server: Server) {
        server.addTool(
            name = "list_devices",
            description = "列出当前通过 ADB 连接并已授权的 Android 设备。",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) {
            val devices = captureService.listDevices()
            val text = if (devices.isEmpty()) {
                "没有已连接的在线设备。请检查 adb devices 与 USB 调试。"
            } else {
                devices.joinToString("\n") { d ->
                    "${d.serial} | ${d.state} | model=${d.model ?: "?"} | product=${d.product ?: "?"}"
                }
            }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    private fun registerCaptureViewHierarchy(server: Server) {
        server.addTool(
            name = "capture_view_hierarchy",
            description = "抓取当前前台 Android 应用的 View 层级结构（复用 Layout Inspector adb-gateway，" +
                "自动 Agent→fallback）。返回人类可读的层级树，含 class/resource-id/text/bounds。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("device_id") {
                        put("type", "string")
                        put("description", "设备 serial（可选，默认自动选择唯一在线设备）")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val deviceId = request.arguments?.str("device_id")
            val outcome = captureService.captureForeground(deviceId)
            if (!outcome.success) {
                CallToolResult(
                    content = listOf(TextContent("抓取层级失败: ${outcome.error}")),
                    isError = true,
                )
            } else {
                val snapshot = outcome.snapshot!!
                val tree = snapshotToTree(snapshot)
                CallToolResult(
                    content = listOf(
                        TextContent(
                            "包名: ${snapshot.packageName}\n" +
                                "窗口数: ${snapshot.effectiveWindows.size}\n" +
                                "节点总数: ${countNodes(snapshot)}\n\n$tree",
                        ),
                    ),
                )
            }
        }
    }

    private fun registerLocateViewSource(server: Server) {
        server.addTool(
            name = "locate_view_source",
            description = "在 Android 源码项目中定位 View 节点对应的源码位置。" +
                "先用 activity/window 类名确定范围，再按 resource-id 与 class 多候选搜索并按置信度排序。" +
                "返回 file:line 形式的可点击位置（在 Android Studio 终端中点击可跳转）。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("project_root") {
                        put("type", "string")
                        put("description", "Android 工程根目录绝对路径（可选，默认取当前工作目录）")
                    }
                    putJsonObject("view_class") { put("type", "string"); put("description", "View 的完整类名") }
                    putJsonObject("resource_id") { put("type", "string"); put("description", "resource-id，如 com.example:id/btn 或 btn") }
                    putJsonObject("text") { put("type", "string"); put("description", "节点显示文本") }
                    putJsonObject("activity_class") { put("type", "string"); put("description", "当前 Activity/Window 类名，用于确定搜索范围") }
                    putJsonObject("package_name") { put("type", "string"); put("description", "应用包名") }
                    putJsonObject("source_hint") { put("type", "string"); put("description", "Compose 检查提供的源码 fileName:line") }
                    putJsonObject("content_description") { put("type", "string") }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val args = request.arguments
            val root = args?.str("project_root") ?: System.getProperty("user.dir")
            val result = sourceLocator.locate(
                projectRoot = root,
                viewClassName = args?.str("view_class"),
                resourceName = args?.str("resource_id"),
                text = args?.str("text"),
                activityClass = args?.str("activity_class"),
                packageName = args?.str("package_name"),
                sourceHint = args?.str("source_hint"),
                contentDescription = args?.str("content_description"),
            )
            CallToolResult(content = listOf(TextContent(formatLocateResult(result))))
        }
    }

    private fun registerPopPendingLocate(server: Server) {
        server.addTool(
            name = "pop_pending_locate_request",
            description = "取出 Layout Inspector 右键推送的待定位 View 节点，并在指定 Android 工程中定位源码。" +
                "使用场景：在 AndroidPerformanceStudio 的 Layout Inspector 里右键 view 节点选择" +
                "'定位源码'后，到源码项目的 Android Studio 终端调用本工具，输出可点击的 file:line。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("project_root") {
                        put("type", "string")
                        put("description", "Android 工程根目录绝对路径（可选，默认取当前工作目录）")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val req = pending.pop()
            if (req == null) {
                CallToolResult(
                    content = listOf(
                        TextContent(
                            "没有待定位请求。请在 AndroidPerformanceStudio 的 Layout Inspector 中" +
                                "右键 view 节点选择'定位源码'后再调用本工具。",
                        ),
                    ),
                )
            } else {
                val root = request.arguments?.str("project_root") ?: System.getProperty("user.dir")
                val result = sourceLocator.locate(
                    projectRoot = root,
                    viewClassName = req.viewClassName,
                    resourceName = req.resourceName,
                    text = req.text,
                    activityClass = req.activityClass,
                    packageName = req.packageName,
                    sourceHint = req.sourceHint,
                    contentDescription = req.contentDescription,
                    ancestors = req.ancestors,
                    siblings = req.siblings,
                )
                val header = "已接收右键推送的节点:\n" + formatPending(req) + "\n\n"
                CallToolResult(content = listOf(TextContent(header + formatLocateResult(result))))
            }
        }
    }

    private fun registerListPending(server: Server) {
        server.addTool(
            name = "list_pending_locate_requests",
            description = "查看当前由 Layout Inspector 右键推送、尚未消费的待定位请求数量与最近一条。",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) {
            val size = pending.size()
            val peek = pending.peek()
            val text = buildString {
                appendLine("待定位请求数: $size")
                if (peek != null) appendLine("最近一条:\n${formatPending(peek)}")
            }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    private fun formatPending(req: PendingLocateRequest): String = buildString {
        req.packageName?.let { appendLine("  packageName: $it") }
        req.activityClass?.let { appendLine("  activityClass: $it") }
        req.windowTitle?.let { appendLine("  windowTitle: $it") }
        req.windowType?.let { appendLine("  windowType: $it") }
        req.viewClassName?.let { appendLine("  viewClass: $it") }
        req.resourceName?.let { appendLine("  resourceId: $it") }
        req.text?.let { appendLine("  text: $it") }
        req.sourceHint?.let { appendLine("  sourceHint: $it") }
        req.bounds?.let { appendLine("  bounds: $it") }
    }

    private fun formatLocateResult(result: LocateResult): String = buildString {
        if (result.candidates.isEmpty()) {
            appendLine("未找到候选源码位置。")
            appendLine("已搜索工程: ${result.searchedProjectRoot}")
            appendLine("已扫描文件数: ${result.fileScanned}")
            appendLine("锚点: ${result.anchors}")
            return@buildString
        }
        appendLine("找到 ${result.candidates.size} 个候选源码位置（扫描 ${result.fileScanned} 个文件）:")
        appendLine()
        append(formatCandidatesGrouped(result.candidates))
        appendLine("在 Android Studio 终端中点击上方 file:line 链接可跳转到对应源码。")
    }

    private fun snapshotToTree(snapshot: LayoutSnapshot): String = buildString {
        snapshot.effectiveWindows.forEach { window ->
            appendLine("Window[${window.type}] ${window.title}  bounds=${window.bounds}")
            appendNode(window.root, 1, this)
            appendLine()
        }
    }

    private fun appendNode(node: UiNode, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        val cls = node.className.substringAfterLast(".")
        val rid = (node as? ViewNode)?.resourceName?.entryName()
        val text = when (node) {
            is ViewNode -> node.text
            is ComposeNode -> node.text

        }
        sb.append("$indent- $cls")
        rid?.let { sb.append(" id=$it") }
        text?.let { if (it.isNotBlank()) sb.append(" text=\"$it\"") }
        sb.append(" bounds=${node.bounds}")
        sb.appendLine()
        node.children.forEach { appendNode(it, depth + 1, sb) }
    }

    private fun countNodes(snapshot: LayoutSnapshot): Int {
        var count = 0
        snapshot.effectiveWindows.forEach { w -> count += countNode(w.root) }
        return count
    }

    private fun countNode(node: UiNode): Int = 1 + node.children.sumOf { countNode(it) }

    private fun String.entryName(): String = substringAfterLast("/").ifBlank { this }

    private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}