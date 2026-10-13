package com.androidperformancestudio.benchmark.model

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

public enum class EvidenceConfidence { EXACT, DERIVED, INFERRED, PARTIAL, UNKNOWN }

public enum class MetricDirection { LOWER_IS_BETTER, HIGHER_IS_BETTER, UNKNOWN }

/** Workload shape used to keep startup and interaction benchmark results comparable. */
public enum class BenchmarkScenario {
    STARTUP,
    SCROLL,
    ANIMATION,
    PAGE_SWITCH,
    CUSTOM,
    UNKNOWN,
}

public enum class BaselineProfileStatus {
    NOT_REQUESTED,
    INSTALLED,
    VERIFIED,
    MISSING,
    UNKNOWN,
}

public enum class RegressionClassification { REGRESSED, IMPROVED, STABLE, INCONCLUSIVE, INCOMPATIBLE }

public enum class BaselinePolicy { PINNED, BRANCH_HEAD, ROLLING_MEDIAN, RELEASE_TAG }

public data class BenchmarkDevice(
    val model: String?,
    val brand: String?,
    val apiLevel: Int?,
    val osVersion: String?,
    val abi: String?,
    val fingerprint: String?,
    val cpuCoreCount: Int?,
    val physicalDevice: Boolean?,
)

public data class BenchmarkBuild(
    val targetPackage: String?,
    val versionName: String?,
    val versionCode: Long?,
    val variant: String?,
    val gitCommit: String?,
    val gitBranch: String?,
)

public data class BenchmarkMetric(
    val name: String,
    val unit: String,
    val direction: MetricDirection,
    val samples: List<Double>,
    val minimum: Double?,
    val median: Double?,
    val maximum: Double?,
    val confidence: EvidenceConfidence,
    val sourceFields: Map<String, String> = emptyMap(),
) {
    public fun representativeValue(): Double? = median ?: samples.sorted().medianOrNull()
}

public data class BaselineProfileEvidence(
    val status: BaselineProfileStatus = BaselineProfileStatus.NOT_REQUESTED,
    val source: String? = null,
    val artifact: Path? = null,
    val profileHash: String? = null,
    val verifiedAt: Instant? = null,
)

public data class BenchmarkCase(
    val className: String,
    val testName: String,
    val packageName: String?,
    val compilationMode: String?,
    val startupMode: String?,
    val iterationCount: Int?,
    val metrics: List<BenchmarkMetric>,
    val traceArtifacts: List<Path>,
    val scenario: BenchmarkScenario = BenchmarkScenario.UNKNOWN,
    val baselineProfile: BaselineProfileEvidence? = null,
) {
    public val identity: String get() = "$className#$testName"
}

public data class BenchmarkRun(
    val id: String = UUID.randomUUID().toString(),
    val sourceFile: Path,
    val benchmarkDataVersion: Int?,
    val benchmarkLibraryVersion: String?,
    val device: BenchmarkDevice,
    val build: BenchmarkBuild,
    val importedAt: Instant = Instant.now(),
    val cases: List<BenchmarkCase>,
    val warnings: List<String> = emptyList(),
    /** Original JSON, Perfetto traces and profile artifacts remain independently addressable. */
    val evidenceArtifacts: List<Path> = listOf(sourceFile),
)

public data class BaselineProfileComparison(
    val baselineRunId: String,
    val profiledRunId: String,
    val report: RegressionReport,
    val baselineStatus: BaselineProfileStatus,
    val profiledStatus: BaselineProfileStatus,
    val tracePairs: List<TraceArtifactPair>,
)

public data class TraceArtifactPair(
    val caseIdentity: String,
    val before: List<Path>,
    val after: List<Path>,
)

public data class CompatibilityIssue(
    val field: String,
    val baseline: String?,
    val current: String?,
    val hard: Boolean,
)

public data class MetricComparison(
    val caseIdentity: String,
    val metricName: String,
    val unit: String,
    val baselineValue: Double?,
    val currentValue: Double?,
    val absoluteDelta: Double?,
    val relativeDeltaPercent: Double?,
    val classification: RegressionClassification,
    val confidence: EvidenceConfidence,
    val reasons: List<String>,
)

public data class RegressionReport(
    val baselineRunId: String,
    val currentRunId: String,
    val createdAt: Instant,
    val comparisons: List<MetricComparison>,
    val compatibilityIssues: List<CompatibilityIssue>,
) {
    public val regressionCount: Int get() = comparisons.count { it.classification == RegressionClassification.REGRESSED }
}

public data class RegressionPolicy(
    val relativeThresholdPercent: Double? = null,
    val absoluteThreshold: Double? = null,
    val minimumSampleCount: Int = 3,
    val noiseBandMadMultiplier: Double = 3.0,
    val requireSameDevice: Boolean = true,
)

/**
 * One exported report. The index records what was produced and where it lives; it deliberately does
 * not convert report bodies into a shared schema (implementation plan §16.2).
 */
public data class BenchmarkReportEntry(
    val id: String,
    val title: String,
    val format: String,
    val createdAt: Instant,
    val path: String,
    val sizeBytes: Long,
)

/**
 * Release gate decision. The implementation plan (§15.4, §2.3) forbids inventing a numeric
 * performance score for the first commercial iteration, so the gate answers PASS / FAIL /
 * INCONCLUSIVE from the comparisons and a versioned policy instead.
 */
public enum class GateDecision { PASS, FAIL, INCONCLUSIVE }

/** Machine-stable reason identifiers so presentations localize them instead of matching prose. */
public enum class GateReason {
    INCOMPATIBLE_EVIDENCE,
    BLOCKING_REGRESSIONS,
    INCONCLUSIVE_EVIDENCE,
    INCONCLUSIVE_DISALLOWED,
    NO_BLOCKING_REGRESSION,
}

/**
 * Versioned release gate policy. [policyVersion] is exported with every report so a decision can be
 * reproduced after the policy changes.
 */
public data class ReleaseGatePolicy(
    val policyVersion: String = "1",
    val blockingMetricPatterns: List<String> = emptyList(),
    val failOnRegression: Boolean = true,
    val maxAllowedRegressions: Int = 0,
    val allowInconclusive: Boolean = true,
    val minimumConfidence: EvidenceConfidence = EvidenceConfidence.EXACT,
) {
    /** An empty pattern list treats every metric as blocking. */
    public fun blocksMetric(metricName: String): Boolean =
        blockingMetricPatterns.isEmpty() ||
            blockingMetricPatterns.any { pattern -> metricName.contains(pattern, ignoreCase = true) }
}

public data class ReleaseGateResult(
    val decision: GateDecision,
    val policyVersion: String,
    val blockingComparisons: List<MetricComparison>,
    val reasons: List<GateReason>,
)

/** One historical value of a case/metric pair, used by the trend query. */
public data class MetricHistoryPoint(
    val runId: String,
    val importedAt: Instant,
    val buildLabel: String?,
    val gitCommit: String?,
    val deviceModel: String?,
    val apiLevel: Int?,
    val value: Double,
    val sampleCount: Int,
)

/**
 * Metric history across runs. The trend only compares compatible environments, so the points keep
 * the device/API identity and callers must surface [environmentVaries] instead of drawing a trend
 * that silently mixes devices.
 */
public data class MetricHistory(
    val caseIdentity: String,
    val metricName: String,
    val unit: String,
    val direction: MetricDirection,
    val minimumSampleCount: Int,
    val points: List<MetricHistoryPoint>,
) {
    public val environmentVaries: Boolean
        get() = points.map { it.deviceModel to it.apiLevel }.distinct().size > 1

    public val hasLowSamplePoints: Boolean
        get() = points.any { it.sampleCount < minimumSampleCount }
}

public fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}
