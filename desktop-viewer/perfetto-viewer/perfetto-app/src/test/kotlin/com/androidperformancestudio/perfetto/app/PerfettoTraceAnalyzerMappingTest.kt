package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.analysis.DiagnosticCategory
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticRow
import com.androidperformancestudio.perfetto.presentation.TraceInsightCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerfettoTraceAnalyzerMappingTest {
    @Test
    fun `duration labels preserve minute and second evidence`() {
        assertEquals("0s", formatTraceDuration(-1L))
        assertEquals("42s", formatTraceDuration(42_900_000_000L))
        assertEquals("2m 5s", formatTraceDuration(125_000_000_000L))
    }

    @Test
    fun `timeline timestamp comes from the first parseable timestamp column value`() {
        val query = query(id = "sched_switch", columns = listOf("thread", "timestamp_ns"))
        val result =
            DiagnosticResult(
                columns = query.columns,
                rows =
                    listOf(
                        DiagnosticRow(listOf("RenderThread", "unknown")),
                        DiagnosticRow(listOf("main", "123456789")),
                    ),
            )

        assertEquals(123_456_789L, result.firstTimelineTimestampNanos(query))
        assertNull(result.firstTimelineTimestampNanos(query(id = "aggregate", columns = listOf("count"))))
    }

    @Test
    fun `diagnostic categories map to commercial insight groups`() {
        assertEquals(
            TraceInsightCategory.FRAME,
            query("frame_jank", DiagnosticCategory.GRAPHICS).workspaceCategory(),
        )
        assertEquals(
            TraceInsightCategory.BINDER,
            query("binder_latency", DiagnosticCategory.BINDER).workspaceCategory(),
        )
        assertEquals(
            TraceInsightCategory.LONG_TASK,
            query("main_thread_blocked", DiagnosticCategory.CPU).workspaceCategory(),
        )
        assertEquals(
            TraceInsightCategory.RENDERING,
            query("gpu_work", DiagnosticCategory.GRAPHICS).workspaceCategory(),
        )
        assertEquals(
            TraceInsightCategory.OTHER,
            query("power_rails", DiagnosticCategory.POWER).workspaceCategory(),
        )
    }

    private fun query(
        id: String,
        category: DiagnosticCategory = DiagnosticCategory.CPU,
        columns: List<String> = listOf("value"),
    ): DiagnosticQuery =
        DiagnosticQuery(
            id = id,
            category = category,
            title = id,
            description = id,
            columns = columns,
            sql = "SELECT 1",
        )
}
