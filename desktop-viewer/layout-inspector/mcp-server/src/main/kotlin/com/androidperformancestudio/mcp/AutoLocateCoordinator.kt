package com.androidperformancestudio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

class AutoLocateCoordinator(
    private val pending: PendingLocateRequests,
    private val sourceLocator: SourceLocator,
    private val session: ServerSession,
    private val projectRoot: () -> String,
    private val pollIntervalMs: Long = 200L,
) {
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (scope.isActive) {
                val req = pending.pop()
                if (req != null) {
                    handle(req)
                } else {
                    delay(pollIntervalMs)
                }
            }
        }
    }

    private suspend fun handle(req: PendingLocateRequest) {
        System.err.println("[aps-locator] processing request: viewClass=${req.viewClassName} id=${req.resourceName} activity=${req.activityClass}")
        val root = runCatching { projectRoot() }.getOrDefault(".")
        val result = runCatching {
            sourceLocator.locate(
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
        }
        val text = result.fold(
            onSuccess = { formatSuccess(req, it, root) },
            onFailure = { formatError(req, root, it) },
        )
        System.err.println("[aps-locator] === locate result (stderr) ===")
        System.err.println(text)
        System.err.println("[aps-locator] === end ===")
        runCatching {
            session.sendLoggingMessage(
                LoggingMessageNotification(
                    LoggingMessageNotificationParams(
                        level = if (result.isSuccess) LoggingLevel.Info else LoggingLevel.Error,
                        data = JsonPrimitive(text),
                        logger = "aps-locator",
                    ),
                ),
            )
        }.onFailure { System.err.println("[aps-locator] sendLoggingMessage failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun formatSuccess(req: PendingLocateRequest, result: LocateResult, root: String): String = buildString {
        appendLine("[aps-locator] 收到 Layout Inspector 右键定位请求:")
        appendShortRequest(req)
        appendLine()
        if (result.candidates.isEmpty()) {
            appendLine("未找到候选源码位置（扫描 ${result.fileScanned} 个文件，工程 $root）。")
            appendLine("可检查锚点是否匹配：${result.anchors}")
        } else {
            appendLine("找到 ${result.candidates.size} 个候选（扫描 ${result.fileScanned} 个文件）:")
            appendLine()
            append(formatCandidatesGrouped(result.candidates))
            appendLine(">> 在 Android Studio 终端中点击上方 file:line 可跳转到对应源码。")
        }
    }

    private fun formatError(req: PendingLocateRequest, root: String, error: Throwable): String = buildString {
        appendLine("[aps-locator] 定位失败:")
        appendShortRequest(req)
        appendLine("  工程根: $root")
        appendLine("  错误: ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun StringBuilder.appendShortRequest(req: PendingLocateRequest) {
        req.viewClassName?.let { appendLine("  viewClass: $it") }
        req.resourceName?.let { appendLine("  resourceId: $it") }
        req.activityClass?.let { appendLine("  activity: $it") }
        req.text?.takeIf { it.isNotBlank() }?.let { appendLine("  text: $it") }
        req.sourceHint?.let { appendLine("  sourceHint: $it") }
    }
}