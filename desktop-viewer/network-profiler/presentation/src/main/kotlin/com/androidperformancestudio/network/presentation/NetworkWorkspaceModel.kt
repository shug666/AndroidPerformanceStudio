@file:Suppress("MagicNumber")

package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import java.net.URI
import java.util.Locale

/** Search only presentation-safe, already-redacted fields, never raw headers or payloads. */
internal fun filterNetworkCalls(calls: List<HttpCall>, query: String, outcome: CallOutcome?): List<HttpCall> {
    val needle = query.trim()
    return calls.filter { call ->
        (outcome == null || call.outcome == outcome) &&
            (
                needle.isEmpty() ||
                    listOf(
                        call.method,
                        call.redactedUrl,
                        call.toPresentationRow().statusCode?.toString().orEmpty(),
                        call.outcome.name,
                        call.source.name,
                    ).any { it.contains(needle, ignoreCase = true) }
            )
    }
}

internal data class NetworkDomainSummary(
    val domain: String?,
    val calls: List<HttpCall>,
) {
    val failures: Int get() = calls.count { it.outcome == CallOutcome.FAILED }
    val transferredBytes: Long?
        get() {
            if (calls.isEmpty()) return null
            val bytes = calls.map { it.completeTransferredBytes ?: return null }
            return bytes.sum()
        }
}

/** A total is available only when every exchange reports both directions, including real zeros. */
private val HttpCall.completeTransferredBytes: Long?
    get() {
        if (exchanges.isEmpty()) return null
        return exchanges.sumOf { exchange ->
            val request = exchange.requestBytes ?: return null
            val response = exchange.responseBytes ?: return null
            request + response
        }
    }

internal fun networkDomains(calls: List<HttpCall>): List<NetworkDomainSummary> =
    calls.groupBy { call ->
        // Parse only authority: redacted path placeholders deliberately are not valid URI characters.
        val authority = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+").find(call.redactedUrl)?.value
        runCatching { authority?.let { URI(it).host?.lowercase(Locale.ROOT) } }.getOrNull()
    }.map { (domain, entries) -> NetworkDomainSummary(domain, entries) }
        .sortedWith(compareByDescending<NetworkDomainSummary> { it.calls.size }.thenBy { it.domain.orEmpty() })

internal data class NetworkActivity(
    val originNs: Long,
    val durationNs: Long,
    val timedCalls: List<HttpCall>,
    val completionBytes: List<Long?>,
    val omittedCalls: Int,
    val incompleteByteCalls: Int,
)

/** Bytes are assigned to call-end buckets, not represented as measured instantaneous throughput. */
internal fun networkActivity(calls: List<HttpCall>, bucketCount: Int = 24): NetworkActivity? {
    require(bucketCount > 0)
    val timed = calls.filter { it.endedNs?.let { end -> end >= it.startedNs } == true }
    if (timed.isEmpty()) return null
    val origin = timed.minOf { it.startedNs }
    val duration = (timed.maxOf { requireNotNull(it.endedNs) } - origin).coerceAtLeast(1)
    val buckets = MutableList<Long?>(bucketCount) { null }
    val unknownBuckets = mutableSetOf<Int>()
    timed.forEach { call ->
        val index = (((requireNotNull(call.endedNs) - origin).toDouble() / duration) * bucketCount)
            .toInt().coerceIn(0, bucketCount - 1)
        val bytes = call.completeTransferredBytes
        if (bytes == null) {
            unknownBuckets += index
        } else {
            buckets[index] = (buckets[index] ?: 0) + bytes
        }
    }
    unknownBuckets.forEach { buckets[it] = null }
    return NetworkActivity(
        origin,
        duration,
        timed,
        buckets,
        calls.size - timed.size,
        timed.count { it.completeTransferredBytes == null },
    )
}
