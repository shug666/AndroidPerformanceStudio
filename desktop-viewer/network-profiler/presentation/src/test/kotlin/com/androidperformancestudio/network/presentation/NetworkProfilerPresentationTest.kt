package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkProfilerPresentationTest {
    @Test
    fun `request row preserves redacted URL and exposes all documented columns`() {
        val call =
            HttpCall(
                callId = "redirected",
                instrumentationId = null,
                method = "POST",
                redactedUrl = "https://api.example.test/<redacted-path>?token=<redacted>",
                startedNs = 1_000_000,
                endedNs = 47_000_000,
                exchanges =
                    listOf(
                        exchange(status = 302, protocol = "h2", requestBytes = 120, responseBytes = 500),
                        exchange(status = 201, protocol = "h3", requestBytes = 80, responseBytes = 1_300),
                    ),
                outcome = CallOutcome.COMPLETED,
                source = NetworkEvidenceSource.HAR_IMPORT,
            )

        val row = call.toPresentationRow()

        assertEquals("POST", row.method)
        assertEquals("https://api.example.test/<redacted-path>?token=<redacted>", row.redactedUrl)
        assertEquals(201, row.statusCode)
        assertEquals(46_000_000, row.durationNs)
        assertEquals(2_000, row.transferredBytes)
        assertEquals("h2 → h3", row.protocol)
        assertEquals(CallOutcome.COMPLETED, row.outcome)
        assertEquals(NetworkEvidenceSource.HAR_IMPORT, row.source)
    }

    @Test
    fun `unknown transfer evidence stays unavailable instead of reporting zero`() {
        val row =
            HttpCall(
                callId = "unknown",
                instrumentationId = null,
                method = "GET",
                redactedUrl = "redacted://invalid-url",
                startedNs = 0,
                endedNs = null,
                exchanges = listOf(exchange(status = null, protocol = null, requestBytes = null, responseBytes = null)),
                outcome = CallOutcome.INCOMPLETE,
                source = NetworkEvidenceSource.RAW_EVENT_BUNDLE,
            ).toPresentationRow()

        assertNull(row.transferredBytes)
        assertNull(row.protocol)
        assertEquals("—", formatBytes(row.transferredBytes))
        assertEquals("—", formatDurationNs(row.durationNs))
    }

    private fun exchange(
        status: Int?,
        protocol: String?,
        requestBytes: Long?,
        responseBytes: Long?,
    ) = HttpExchange(
        exchangeIndex = 0,
        connectionId = null,
        connectionUse = ConnectionUse.UNKNOWN,
        protocol = protocol,
        statusCode = status,
        requestBytes = requestBytes,
        responseBytes = responseBytes,
        decodedResponseBytes = null,
        phases = emptyList(),
        cacheDisposition = CacheDisposition.UNKNOWN,
        failure = null,
    )
}
