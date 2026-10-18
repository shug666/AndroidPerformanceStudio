package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.network.model.CallOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkWorkspaceModelTest {
    private val calls get() = requireNotNull(networkVisualState().result).calls

    @Test
    fun `search and outcome filters intersect and never inspect headers`() {
        assertEquals(listOf("har-2"), filterNetworkCalls(calls, "post", CallOutcome.FAILED).map { it.callId })
        assertEquals(listOf("har-2"), filterNetworkCalls(calls, "503", null).map { it.callId })
        assertEquals(listOf("har-1"), filterNetworkCalls(calls, "API.EXAMPLE", null).map { it.callId })
        assertTrue(filterNetworkCalls(calls, "authorization", null).isEmpty())
        assertTrue(filterNetworkCalls(calls, "cdn.example", CallOutcome.FAILED).isEmpty())
        assertEquals(calls, filterNetworkCalls(calls, "   ", null))
    }

    @Test
    fun `domain grouping parses redacted paths and excludes authority credentials`() {
        val first = calls.first()
        val summaries = networkDomains(listOf(first, first.copy(callId = "another", redactedUrl = "https://user:password@api.example.test/<redacted-path>")))
        assertEquals(1, summaries.size)
        assertEquals("api.example.test", summaries.single().domain)
        assertEquals(2, summaries.single().calls.size)
        assertEquals(first.toPresentationRow().transferredBytes!! * 2, summaries.single().transferredBytes)
        val unknown = first.copy(redactedUrl = "redacted://invalid-url", exchanges = emptyList())
        assertNull(networkDomains(listOf(unknown)).single().transferredBytes)
        assertNull(networkDomains(listOf(first.copy(redactedUrl = "<redacted>"))).single().domain)
    }

    @Test
    fun `activity assigns known bytes only at completion and clamps right endpoint`() {
        val activity = requireNotNull(networkActivity(calls, 2))
        assertEquals(0, activity.originNs)
        assertEquals(1_420_000_000, activity.durationNs)
        assertEquals(3, activity.timedCalls.size)
        assertEquals(0, activity.omittedCalls)
        assertEquals(calls.sumOf { it.toPresentationRow().transferredBytes!! }, activity.completionBytes.filterNotNull().sum())
        assertEquals(calls[1].toPresentationRow().transferredBytes, activity.completionBytes.last())
    }

    @Test
    fun `activity excludes absent or reversed endpoints and preserves unknown byte buckets`() {
        val first = calls.first()
        assertNull(networkActivity(emptyList()))
        assertNull(networkActivity(listOf(first.copy(endedNs = null))))
        val activity = requireNotNull(networkActivity(listOf(first.copy(exchanges = emptyList()), first.copy(callId = "pending", endedNs = null), first.copy(callId = "invalid", endedNs = -1))))
        assertEquals(2, activity.omittedCalls)
        assertTrue(activity.completionBytes.all { it == null })
    }

    @Test
    fun `partial directional bytes invalidate whole domain and shared completion bucket`() {
        val known = calls.first()
        val partial = known.copy(
            callId = "partial",
            exchanges = known.exchanges.map { it.copy(requestBytes = null) },
        )
        assertNull(networkDomains(listOf(known, partial)).single().transferredBytes)
        val activity = requireNotNull(networkActivity(listOf(known, partial), 1))
        assertEquals(1, activity.incompleteByteCalls)
        assertNull(activity.completionBytes.single())
        assertEquals(2, activity.timedCalls.size)
    }

    @Test
    fun `zero-duration and zero-byte evidence stay real zeros`() {
        val first = calls.first()
        val zero = first.copy(endedNs = first.startedNs, exchanges = first.exchanges.map { it.copy(requestBytes = 0, responseBytes = 0) })
        val activity = requireNotNull(networkActivity(listOf(zero)))
        assertEquals(listOf(0L), activity.completionBytes.filterNotNull())
        assertEquals(1, activity.durationNs)
    }
}
