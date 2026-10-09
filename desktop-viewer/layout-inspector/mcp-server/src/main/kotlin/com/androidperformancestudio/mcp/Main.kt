package com.androidperformancestudio.mcp

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered

fun main(args: Array<String> = emptyArray()) {
    val bridgePort = (System.getenv("APS_BRIDGE_PORT")?.toIntOrNull() ?: 7821)
    val projectRoot = System.getenv("APS_PROJECT_ROOT") ?: System.getProperty("user.dir")
    val pending = PendingLocateRequests()
    val sourceLocator = SourceLocator()

    if (args.contains("--watch")) {
        runWatchMode(bridgePort, projectRoot, pending, sourceLocator)
        return
    }
    runMcpMode(bridgePort, projectRoot, pending, sourceLocator)
}

private fun runWatchMode(
    bridgePort: Int,
    projectRoot: String,
    pending: PendingLocateRequests,
    sourceLocator: SourceLocator,
) {
    val bridge = LocalBridgeHttpServer(bridgePort, pending)
    try {
        bridge.start()
    } catch (error: Throwable) {
        System.err.println("[aps-watch] failed to start bridge on port $bridgePort: ${error.message}")
        return
    }
    println("[aps-watch] === Layout Inspector 源码定位 watch 模式 ===")
    println("[aps-watch] bridge: 127.0.0.1:$bridgePort")
    println("[aps-watch] project root: $projectRoot")
    println("[aps-watch] 等待 Layout Inspector 右键'定位源码'请求...（Ctrl+C 退出）")
    println()
    while (true) {
        val req = pending.pop()
        if (req != null) {
            handleWatch(req, sourceLocator, projectRoot)
        } else {
            Thread.sleep(200)
        }
    }
}

private fun handleWatch(
    req: PendingLocateRequest,
    sourceLocator: SourceLocator,
    projectRoot: String,
) {
    println("[aps-watch] ────────────────────────────────────────────")
    println("[aps-watch] 收到右键定位请求:")
    req.viewClassName?.let { println("  viewClass: $it") }
    req.resourceName?.let { println("  resourceId: $it") }
    req.activityClass?.let { println("  activity: $it") }
    req.text?.takeIf { it.isNotBlank() }?.let { println("  text: $it") }
    req.sourceHint?.let { println("  sourceHint: $it") }
    println()

    val result = runCatching {
        sourceLocator.locate(
            projectRoot = projectRoot,
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
    }
    result.fold(
        onSuccess = { r ->
            if (r.candidates.isEmpty()) {
                println("[aps-watch] 未找到候选源码位置（扫描 ${r.fileScanned} 个文件，工程 $projectRoot）")
                println("[aps-watch] 锚点: ${r.anchors}")
            } else {
                println("[aps-watch] 找到 ${r.candidates.size} 个候选（扫描 ${r.fileScanned} 个文件）:")
                println()
                print(formatCandidatesGrouped(r.candidates))
                println("[aps-watch] >> 点击上方 路径:行号 可跳转到源码。")
            }
        },
        onFailure = { e ->
            println("[aps-watch] 定位失败: ${e.javaClass.simpleName}: ${e.message}")
        },
    )
    println()
}

private fun runMcpMode(
    bridgePort: Int,
    projectRoot: String,
    pending: PendingLocateRequests,
    sourceLocator: SourceLocator,
) {
    val autoLocate = System.getenv("APS_AUTO_LOCATE")?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: true
    val bridge = LocalBridgeHttpServer(bridgePort, pending)
    try {
        bridge.start()
        System.err.println("[aps-mcp] local bridge listening on 127.0.0.1:$bridgePort")
    } catch (error: Throwable) {
        System.err.println("[aps-mcp] failed to start local bridge on port $bridgePort: ${error.message}")
        System.err.println("[aps-mcp] set APS_BRIDGE_PORT to another port and retry; MCP stdio still works.")
    }
    System.err.println("[aps-mcp] auto-locate: $autoLocate (override with APS_AUTO_LOCATE=false|true)")
    System.err.println("[aps-mcp] project root: $projectRoot (override with APS_PROJECT_ROOT)")

    val captureService = ViewHierarchyCaptureService()
    val host = McpServerHost(captureService, sourceLocator, pending)
    val server = host.createServer()
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered(),
    )
    runBlocking {
        val session = server.createSession(transport)
        if (autoLocate) {
            AutoLocateCoordinator(
                pending = pending,
                sourceLocator = sourceLocator,
                session = session,
                projectRoot = { projectRoot },
            ).start(this)
        }
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
    bridge.stop()
}
