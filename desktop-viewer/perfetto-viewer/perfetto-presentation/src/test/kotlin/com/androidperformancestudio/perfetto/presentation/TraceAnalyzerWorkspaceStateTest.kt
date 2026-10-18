package com.androidperformancestudio.perfetto.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceAnalyzerWorkspaceStateTest {
    @Test
    fun `session search covers presentation metadata and artifact identity`() {
        val sessions =
            listOf(
                traceSession("game", "Game trace", "artifact-game"),
                traceSession("startup", "Cold startup", "artifact-startup"),
            )

        assertEquals(listOf("game"), filterTraceWorkspaceSessions(sessions, "game").map { it.sessionId })
        assertEquals(listOf("startup"), filterTraceWorkspaceSessions(sessions, "ARTIFACT-STARTUP").map { it.sessionId })
        assertEquals(sessions, filterTraceWorkspaceSessions(sessions, "  "))
        assertTrue(filterTraceWorkspaceSessions(sessions, "missing").isEmpty())
    }

    @Test
    fun `summary values only come from successful result evidence`() {
        val summaries =
            buildTraceWorkspaceSummaries(
                listOf(
                    insight(TraceInsightCategory.FRAME, result = "12 rows"),
                    insight(TraceInsightCategory.BINDER, result = "18 rows", error = "schema unavailable"),
                    insight(TraceInsightCategory.LONG_TASK, result = null),
                ),
            ).associateBy(TraceWorkspaceSummary::category)

        assertEquals("12 rows", summaries.getValue(TraceInsightCategory.FRAME).value)
        assertNull(summaries.getValue(TraceInsightCategory.BINDER).value)
        assertNull(summaries.getValue(TraceInsightCategory.LONG_TASK).value)
        assertNull(summaries.getValue(TraceInsightCategory.RENDERING).value)
    }

    @Test
    fun `timeline action requires locatable evidence for the active artifact`() {
        val active = traceArtifact("artifact-active", viewerAvailable = true)
        val locatable = insight(TraceInsightCategory.FRAME, result = "1 row", timestampNanos = 58_123_000_000)

        assertTrue(canOpenInsightInTimeline(locatable, active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(timestampNanos = null), active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(error = "query failed"), active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(artifactId = "another-artifact"), active))
        assertFalse(canOpenInsightInTimeline(locatable, active.copy(viewerAvailable = false)))
        assertFalse(canOpenInsightInTimeline(locatable, null))
    }

    private fun traceSession(
        id: String,
        title: String,
        artifactId: String,
    ): TraceWorkspaceSession =
        TraceWorkspaceSession(
            sessionId = id,
            title = title,
            capturedAtLabel = "2026-10-15 10:20",
            durationLabel = "12 s",
            artifact = traceArtifact(artifactId),
        )

    private fun traceArtifact(
        id: String,
        viewerAvailable: Boolean = true,
    ): TraceWorkspaceArtifact =
        TraceWorkspaceArtifact(
            artifactId = id,
            displayName = "$id.pftrace",
            locationLabel = "/traces/$id.pftrace",
            completenessLabel = "Complete",
            viewerAvailable = viewerAvailable,
        )

    private fun insight(
        category: TraceInsightCategory,
        result: String?,
        error: String? = null,
        timestampNanos: Long? = null,
    ): TraceInsightEvidence =
        TraceInsightEvidence(
            queryId = "query-${category.name.lowercase()}",
            artifactId = "artifact-active",
            title = category.name,
            resultRowsSummary = result,
            error = error,
            timestampNanos = timestampNanos,
            category = category,
        )
}
