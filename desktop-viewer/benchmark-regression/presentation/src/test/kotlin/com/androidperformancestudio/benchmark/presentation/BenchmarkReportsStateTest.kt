package com.androidperformancestudio.benchmark.presentation

import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricComparison
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionReport
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BenchmarkReportsStateTest {
    @Test
    fun `severity is derived from the verdict and its confidence`() {
        assertEquals(RegressionSeverity.HIGH, severityOf(comparison(RegressionClassification.REGRESSED, EvidenceConfidence.EXACT)))
        assertEquals(RegressionSeverity.LOW, severityOf(comparison(RegressionClassification.REGRESSED, EvidenceConfidence.PARTIAL)))
        assertEquals(RegressionSeverity.NONE, severityOf(comparison(RegressionClassification.IMPROVED)))
        assertEquals(RegressionSeverity.NONE, severityOf(comparison(RegressionClassification.STABLE)))
        assertEquals(RegressionSeverity.UNKNOWN, severityOf(comparison(RegressionClassification.INCONCLUSIVE)))
        assertEquals(RegressionSeverity.INCOMPATIBLE, severityOf(comparison(RegressionClassification.INCOMPATIBLE)))
    }

    @Test
    fun `the table filters by query, severity and classification`() {
        val state =
            BenchmarkReportsState(
                report =
                    RegressionReport(
                        "baseline",
                        "current",
                        Instant.EPOCH,
                        listOf(
                            comparison(RegressionClassification.REGRESSED, metric = "startupMs"),
                            comparison(RegressionClassification.IMPROVED, metric = "frameMs"),
                            comparison(RegressionClassification.INCONCLUSIVE, metric = "memoryMb"),
                        ),
                        emptyList(),
                    ),
            )

        assertEquals(3, state.visibleComparisons().size)
        assertEquals(1, state.copy(query = "startup").visibleComparisons().size)
        assertEquals(1, state.copy(severityFilter = RegressionSeverity.HIGH).visibleComparisons().size)
        assertEquals(1, state.copy(classificationFilter = RegressionClassification.IMPROVED).visibleComparisons().size)
        assertEquals(
            1,
            state
                .copy(
                    query = "frame",
                    classificationFilter = RegressionClassification.IMPROVED,
                ).visibleComparisons()
                .size,
        )
    }

    @Test
    fun `summary counts come from the report`() {
        val state =
            BenchmarkReportsState(
                report =
                    RegressionReport(
                        "baseline",
                        "current",
                        Instant.EPOCH,
                        listOf(
                            comparison(RegressionClassification.REGRESSED),
                            comparison(RegressionClassification.IMPROVED),
                            comparison(RegressionClassification.IMPROVED),
                            comparison(RegressionClassification.STABLE),
                        ),
                        emptyList(),
                    ),
            )

        assertEquals(4, state.comparedCount)
        assertEquals(1, state.regressionCount)
        assertEquals(2, state.improvementCount)
        assertEquals(1, state.stableCount)
    }

    private fun comparison(
        classification: RegressionClassification,
        confidence: EvidenceConfidence = EvidenceConfidence.EXACT,
        metric: String = "timeMs",
    ): MetricComparison =
        MetricComparison(
            caseIdentity = "Bench#test",
            metricName = metric,
            unit = "ms",
            baselineValue = 1.0,
            currentValue = 2.0,
            absoluteDelta = 1.0,
            relativeDeltaPercent = 100.0,
            classification = classification,
            confidence = confidence,
            reasons = emptyList(),
        )
}
