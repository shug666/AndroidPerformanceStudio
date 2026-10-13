package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.CompatibilityIssue
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.GateDecision
import com.androidperformancestudio.benchmark.model.GateReason
import com.androidperformancestudio.benchmark.model.MetricComparison
import com.androidperformancestudio.benchmark.model.ReleaseGatePolicy
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionReport
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseGateEvaluatorTest {
    private val evaluator = ReleaseGateEvaluator()

    @Test
    fun `an exact regression fails the gate and is listed as blocking`() {
        val comparison = comparison(RegressionClassification.REGRESSED)
        val result = evaluator.evaluate(report(listOf(comparison)), ReleaseGatePolicy())

        assertEquals(GateDecision.FAIL, result.decision)
        assertEquals(listOf(comparison), result.blockingComparisons)
        assertTrue(result.reasons.contains(GateReason.BLOCKING_REGRESSIONS))
        assertEquals("1", result.policyVersion)
    }

    @Test
    fun `incompatible environments take precedence over the regression verdict`() {
        val report =
            report(
                comparisons = listOf(comparison(RegressionClassification.REGRESSED)),
                issues = listOf(CompatibilityIssue("device.apiLevel", "34", "35", hard = true)),
            )
        val result = evaluator.evaluate(report, ReleaseGatePolicy())

        assertEquals(GateDecision.INCONCLUSIVE, result.decision)
        assertTrue(result.reasons.contains(GateReason.INCOMPATIBLE_EVIDENCE))
    }

    @Test
    fun `a low confidence regression is inconclusive instead of blocking`() {
        val comparison = comparison(RegressionClassification.REGRESSED, EvidenceConfidence.PARTIAL)
        val result = evaluator.evaluate(report(listOf(comparison)), ReleaseGatePolicy())

        assertEquals(GateDecision.INCONCLUSIVE, result.decision)
        assertTrue(result.blockingComparisons.isEmpty())
        assertTrue(result.reasons.contains(GateReason.INCONCLUSIVE_EVIDENCE))
    }

    @Test
    fun `a policy that forbids inconclusive evidence fails on it`() {
        val comparison = comparison(RegressionClassification.REGRESSED, EvidenceConfidence.PARTIAL)
        val result =
            evaluator.evaluate(
                report(listOf(comparison)),
                ReleaseGatePolicy(allowInconclusive = false),
            )

        assertEquals(GateDecision.FAIL, result.decision)
        assertTrue(result.reasons.contains(GateReason.INCONCLUSIVE_DISALLOWED))
    }

    @Test
    fun `blocking metric patterns decide which regressions can fail the gate`() {
        val regression = comparison(RegressionClassification.REGRESSED, metric = "timeMs")
        val result =
            evaluator.evaluate(
                report(listOf(regression)),
                ReleaseGatePolicy(blockingMetricPatterns = listOf("startupMs")),
            )

        assertEquals(GateDecision.PASS, result.decision)
        assertFalse(result.reasons.contains(GateReason.BLOCKING_REGRESSIONS))
    }

    @Test
    fun `the configured regression allowance is honoured`() {
        val comparisons =
            listOf(
                comparison(RegressionClassification.REGRESSED, metric = "timeMs"),
                comparison(RegressionClassification.REGRESSED, metric = "frameMs"),
            )
        assertEquals(GateDecision.PASS, evaluator.evaluate(report(comparisons), ReleaseGatePolicy(maxAllowedRegressions = 2)).decision)
        assertEquals(GateDecision.FAIL, evaluator.evaluate(report(comparisons), ReleaseGatePolicy(maxAllowedRegressions = 1)).decision)
    }

    @Test
    fun `a clean comparison passes and reports the policy version`() {
        val result =
            evaluator.evaluate(
                report(listOf(comparison(RegressionClassification.IMPROVED))),
                ReleaseGatePolicy(policyVersion = "2026.1"),
            )

        assertEquals(GateDecision.PASS, result.decision)
        assertEquals("2026.1", result.policyVersion)
        assertTrue(result.reasons.contains(GateReason.NO_BLOCKING_REGRESSION))
    }

    @Test
    fun `minimum confidence accepts derived evidence when the policy asks for it`() {
        val comparison = comparison(RegressionClassification.REGRESSED, EvidenceConfidence.DERIVED)
        val blocking =
            evaluator.evaluate(
                report(listOf(comparison)),
                ReleaseGatePolicy(minimumConfidence = EvidenceConfidence.DERIVED),
            )
        assertEquals(GateDecision.FAIL, blocking.decision)

        val inconclusive = evaluator.evaluate(report(listOf(comparison)), ReleaseGatePolicy())
        assertEquals(GateDecision.INCONCLUSIVE, inconclusive.decision)
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
            baselineValue = 100.0,
            currentValue = 120.0,
            absoluteDelta = 20.0,
            relativeDeltaPercent = 20.0,
            classification = classification,
            confidence = confidence,
            reasons = emptyList(),
        )

    private fun report(
        comparisons: List<MetricComparison>,
        issues: List<CompatibilityIssue> = emptyList(),
    ): RegressionReport = RegressionReport("baseline", "current", Instant.EPOCH, comparisons, issues)
}
