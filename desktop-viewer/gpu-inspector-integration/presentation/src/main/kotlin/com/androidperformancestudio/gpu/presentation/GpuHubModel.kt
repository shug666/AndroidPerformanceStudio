@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.gpu.presentation

import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Views offered by the GPU hub workspace.
 *
 * The reference design fills its centre column with AGI frame-profile panels (GPU counters, frame
 * timeline, render passes, draw calls, textures, pipeline state). Page 09 forbids those until a
 * stable artifact parser exists, so the workspace exposes the documented replacements instead:
 * artifact preview/metadata, the artifact table, related analysis and external tool capability.
 */
public enum class GpuHubTab { OVERVIEW, ARTIFACTS, RELATED_EVIDENCE, TOOL_CAPABILITY }

/**
 * Indexed-artifact facts shown by the hub summary row. Every value is counted from the local index
 * and its verification results; none is derived from AGI frame-profile content.
 */
internal data class GpuHubSummary(
    val indexedCount: Int = 0,
    val availableCount: Int = 0,
    val attentionCount: Int = 0,
    val unverifiedCount: Int = 0,
    val kindCounts: List<Pair<GpuArtifactKind, Int>> = emptyList(),
    val attentionByStatus: List<Pair<ArtifactLocationStatus, Int>> = emptyList(),
)

internal fun GpuIntegrationState.hubSummary(): GpuHubSummary {
    val statuses = artifacts.map { availabilityByArtifactId[it.id]?.locationStatus }
    val countedKinds = GpuArtifactKind.entries.mapNotNull { kind -> artifacts.count { it.kind == kind }.takeIf { it > 0 }?.let { kind to it } }
    val countedStatuses =
        ArtifactLocationStatus.entries.mapNotNull { status ->
            statuses.count { it == status }.takeIf { it > 0 }?.let { status to it }
        }
    return GpuHubSummary(
        indexedCount = artifacts.size,
        availableCount = statuses.count { it == ArtifactLocationStatus.AVAILABLE },
        attentionCount = statuses.count { it != null && it != ArtifactLocationStatus.AVAILABLE },
        unverifiedCount = statuses.count { it == null },
        kindCounts = countedKinds,
        attentionByStatus = countedStatuses.filterNot { it.first == ArtifactLocationStatus.AVAILABLE },
    )
}

/** Availability of one artifact, or null when the index has not verified it yet. */
internal fun GpuIntegrationState.availabilityOf(artifact: GpuArtifact?): GpuArtifactAvailability? = artifact?.let { availabilityByArtifactId[it.id] }

internal fun GpuIntegrationState.artifactStatus(artifact: GpuArtifact): ArtifactLocationStatus? = availabilityByArtifactId[artifact.id]?.locationStatus

/**
 * Evidence related to [artifact] by an actually observed identity: the same device serial or the
 * same package name. Matching metadata never implies that two captures share a timeline.
 */
internal fun GpuIntegrationState.relatedArtifacts(artifact: GpuArtifact?): List<GpuArtifact> {
    if (artifact == null) return emptyList()
    val serial = artifact.device?.serial
    val packageName = artifact.packageName
    return artifacts.filter { candidate ->
        candidate.id != artifact.id &&
            (
                (serial != null && serial == candidate.device?.serial) ||
                    (packageName != null && packageName == candidate.packageName)
            )
    }
}

internal fun relatedEvidenceGroups(artifacts: List<GpuArtifact>): List<Pair<GpuArtifactKind, List<GpuArtifact>>> =
    GpuArtifactKind.entries
        .map { kind -> kind to artifacts.filter { it.kind == kind } }
        .filter { it.second.isNotEmpty() }

/** Case-insensitive search over the recorded artifact identity. Selection is never inferred here. */
internal fun filterArtifacts(
    artifacts: List<GpuArtifact>,
    query: String,
): List<GpuArtifact> {
    val needle = query.trim()
    if (needle.isEmpty()) return artifacts
    return artifacts.filter { artifact -> artifact.searchTerms().any { it.contains(needle, ignoreCase = true) } }
}

private fun GpuArtifact.searchTerms(): List<String> =
    listOfNotNull(
        path.fileName?.toString(),
        path.toString(),
        packageName,
        device?.displayName,
        device?.model,
        device?.serial,
        device?.gpuRenderer,
        device?.driverVersion,
        graphicsApi?.name,
        graphicsImplementation?.name,
        kind.name,
        sha256,
    )

internal fun GpuArtifact.displayTitle(): String = path.fileName?.toString() ?: path.toString()

internal fun GpuArtifact.displayDevice(): String =
    device?.displayName ?: device?.model ?: device?.serial ?: "—"

internal fun Instant?.displayTimestamp(): String = this?.let(CAPTURE_TIMESTAMP::format) ?: "—"

internal fun formatArtifactSize(bytes: Long): String =
    when {
        bytes >= MEBIBYTE -> String.format(Locale.ROOT, "%.1f MiB", bytes / MEBIBYTE)
        bytes >= KIBIBYTE -> String.format(Locale.ROOT, "%.1f KiB", bytes / KIBIBYTE)
        else -> "$bytes B"
    }

private val CAPTURE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = 1024.0 * 1024.0
