package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.network.analysis.NetworkSummary
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import java.util.Locale

internal data class NetworkCallPresentation(
    val method: String,
    val redactedUrl: String,
    val statusCode: Int?,
    val durationNs: Long?,
    val transferredBytes: Long?,
    val protocol: String?,
    val outcome: CallOutcome,
    val source: NetworkEvidenceSource,
)

internal fun HttpCall.toPresentationRow(): NetworkCallPresentation {
    val byteCounts = exchanges.flatMap { listOfNotNull(it.requestBytes, it.responseBytes) }
    return NetworkCallPresentation(
        method = method,
        redactedUrl = redactedUrl,
        statusCode = exchanges.lastOrNull { it.statusCode != null }?.statusCode,
        durationNs = durationNs,
        transferredBytes = byteCounts.takeIf { it.isNotEmpty() }?.sum(),
        protocol = exchanges.mapNotNull { it.protocol }.distinct().joinToString(" → ").ifBlank { null },
        outcome = outcome,
        source = source,
    )
}

internal val HttpCall.totalRequestBytes: Long?
    get() = exchanges.mapNotNull { it.requestBytes }.takeIf { it.isNotEmpty() }?.sum()

internal val HttpCall.totalResponseBytes: Long?
    get() = exchanges.mapNotNull { it.responseBytes }.takeIf { it.isNotEmpty() }?.sum()

internal val NetworkSummary.transferredBytes: Long
    get() = totalRequestBytes + totalResponseBytes

internal fun formatDurationNs(durationNs: Long?): String =
    durationNs?.div(1_000_000.0)?.let(::formatDurationMs) ?: "—"

internal fun formatDurationMs(durationMs: Double?): String =
    durationMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"

internal fun formatBytes(bytes: Long?): String =
    when {
        bytes == null -> "—"
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    }
