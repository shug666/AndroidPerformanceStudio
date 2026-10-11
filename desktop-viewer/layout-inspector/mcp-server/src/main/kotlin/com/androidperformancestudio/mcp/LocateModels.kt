package com.androidperformancestudio.mcp

import kotlinx.serialization.Serializable

@Serializable
data class PendingLocateRequest(
    val packageName: String? = null,
    val activityClass: String? = null,
    val windowTitle: String? = null,
    val windowType: String? = null,
    val viewClassName: String? = null,
    val resourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: String? = null,
    val sourceHint: String? = null,
    val ancestors: List<NodeSummary> = emptyList(),
    val siblings: List<NodeSummary> = emptyList(),
    val pushedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class NodeSummary(
    val className: String? = null,
    val resourceName: String? = null,
    val text: String? = null,
)

@Serializable
data class LocateCandidate(
    val file: String,
    val line: Int,
    val confidence: Double,
    val reason: String,
    val snippet: String? = null,
)

@Serializable
data class LocateResult(
    val candidates: List<LocateCandidate>,
    val searchedProjectRoot: String,
    val anchors: LocateAnchors,
    val fileScanned: Int,
)

@Serializable
data class LocateAnchors(
    val packageName: String? = null,
    val activityClass: String? = null,
    val viewClassName: String? = null,
    val resourceName: String? = null,
    val resourceEntry: String? = null,
    val text: String? = null,
    val sourceHint: String? = null,
)

fun formatCandidatesGrouped(candidates: List<LocateCandidate>): String = buildString {
    if (candidates.isEmpty()) return@buildString
    val xmlAll = candidates.filter { it.file.endsWith(".xml") }
    val codeAll = candidates.filter { !it.file.endsWith(".xml") }
    val xml = xmlAll.take(10)
    val code = codeAll.take(10)
    if (xml.isNotEmpty()) {
        val header = if (xmlAll.size > 10) "布局 XML (前 10 / 共 ${xmlAll.size} 个)" else "布局 XML (${xml.size} 个)"
        appendLine("── $header ──")
        xml.forEachIndexed { i, c ->
            val conf = "%.2f".format(c.confidence)
            appendLine("  ${i + 1}. ${c.file}:${c.line}  (置信度 $conf)  ${c.reason}")
            c.snippet?.let { appendLine("     | $it") }
        }
        appendLine()
    }
    if (code.isNotEmpty()) {
        val header = if (codeAll.size > 10) "Kotlin/Java 源码 (前 10 / 共 ${codeAll.size} 个)" else "Kotlin/Java 源码 (${code.size} 个)"
        appendLine("── $header ──")
        code.forEachIndexed { i, c ->
            val conf = "%.2f".format(c.confidence)
            appendLine("  ${i + 1}. ${c.file}:${c.line}  (置信度 $conf)  ${c.reason}")
            c.snippet?.let { appendLine("     | $it") }
        }
        appendLine()
    }
}