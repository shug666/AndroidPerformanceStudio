@file:Suppress("ReturnCount")

package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.CompatibilityIssue
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.GateDecision
import com.androidperformancestudio.benchmark.model.GateReason
import com.androidperformancestudio.benchmark.model.ReleaseGatePolicy
import com.androidperformancestudio.benchmark.model.ReleaseGateResult
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionReport

/**
 * Evaluates a [RegressionReport] against a versioned [ReleaseGatePolicy].
 *
 * Ordering rules from the implementation plan (§15.4, §15.6):
 * - environment incompatibility takes precedence over a regression verdict, so an incompatible run
 *   is reported as INCONCLUSIVE instead of a confident PASS/FAIL;
 * - a regression below [ReleaseGatePolicy.minimumConfidence] never blocks on its own; low-sample
 *   evidence can only make the gate inconclusive, never a high-confidence regression;
 * - no numeric score is produced. The decision, the blocking comparisons and the policy version are
 *   the whole result.
 */
public class ReleaseGateEvaluator {
    public fun evaluate(
        report: RegressionReport,
        policy: ReleaseGatePolicy,
    ): ReleaseGateResult {
        val hardIssues = report.compatibilityIssues.filter(CompatibilityIssue::hard)
        val incompatibleComparisons =
            report.comparisons.filter { it.classification == RegressionClassification.INCOMPATIBLE }
        val regressions = report.comparisons.filter { it.classification == RegressionClassification.REGRESSED }
        val confidentRegressions = regressions.filter { it.confidence.rank() >= policy.minimumConfidence.rank() }
        val blockingRegressions = confidentRegressions.filter { policy.blocksMetric(it.metricName) }
        val blocking = blockingRegressions + incompatibleComparisons
        val incompatibleEvidence = hardIssues.isNotEmpty() || incompatibleComparisons.isNotEmpty()
        val exceeded = policy.failOnRegression && blockingRegressions.size > policy.maxAllowedRegressions
        val inconclusiveEvidence =
            report.comparisons.any { it.classification == RegressionClassification.INCONCLUSIVE } ||
                regressions.any { it !in confidentRegressions }

        val reasons =
            buildList {
                if (incompatibleEvidence) {
                    add(GateReason.INCOMPATIBLE_EVIDENCE)
                }
                if (exceeded) {
                    add(GateReason.BLOCKING_REGRESSIONS)
                }
                if (inconclusiveEvidence) {
                    add(
                        if (policy.allowInconclusive) {
                            GateReason.INCONCLUSIVE_EVIDENCE
                        } else {
                            GateReason.INCONCLUSIVE_DISALLOWED
                        },
                    )
                }
                if (isEmpty()) {
                    add(GateReason.NO_BLOCKING_REGRESSION)
                }
            }

        val decision =
            when {
                incompatibleEvidence -> GateDecision.INCONCLUSIVE
                exceeded -> GateDecision.FAIL
                inconclusiveEvidence -> if (policy.allowInconclusive) GateDecision.INCONCLUSIVE else GateDecision.FAIL
                else -> GateDecision.PASS
            }

        return ReleaseGateResult(
            decision = decision,
            policyVersion = policy.policyVersion,
            blockingComparisons = blocking,
            reasons = reasons,
        )
    }
}

/** Confidence ordering used to decide whether a regression is strong enough to block a release. */
internal fun EvidenceConfidence.rank(): Int =
    when (this) {
        EvidenceConfidence.EXACT -> 4
        EvidenceConfidence.DERIVED -> 3
        EvidenceConfidence.INFERRED -> 2
        EvidenceConfidence.PARTIAL -> 1
        EvidenceConfidence.UNKNOWN -> 0
    }
