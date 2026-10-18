package com.androidperformancestudio.gpu.presentation

import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hub may only report facts that the local artifact index and its verification produced. */
class GpuHubModelTest {
    @Test
    fun `summary separates verified, recoverable and unverified artifacts`() {
        val summary =
            stateOf(
                artifact("a") to ArtifactLocationStatus.AVAILABLE,
                artifact("b") to ArtifactLocationStatus.MISSING,
                artifact("c") to null,
            ).hubSummary()

        assertEquals(3, summary.indexedCount)
        assertEquals(1, summary.availableCount)
        assertEquals(1, summary.attentionCount)
        assertEquals(1, summary.unverifiedCount)
    }

    @Test
    fun `summary counts kinds in enum order and omits kinds without artifacts`() {
        val summary =
            stateOf(
                artifact("a", kind = GpuArtifactKind.PERFETTO_TRACE) to ArtifactLocationStatus.AVAILABLE,
                artifact("b", kind = GpuArtifactKind.PERFETTO_TRACE) to ArtifactLocationStatus.AVAILABLE,
                artifact("c", kind = GpuArtifactKind.AGI_FRAME_PROFILE) to ArtifactLocationStatus.AVAILABLE,
            ).hubSummary()

        assertEquals(listOf(GpuArtifactKind.AGI_FRAME_PROFILE to 1, GpuArtifactKind.PERFETTO_TRACE to 2), summary.kindCounts)
    }

    @Test
    fun `summary keeps every recovery status that the index reported`() {
        val summary =
            stateOf(
                artifact("a") to ArtifactLocationStatus.SIZE_CHANGED,
                artifact("b") to ArtifactLocationStatus.SIZE_CHANGED,
                artifact("c") to ArtifactLocationStatus.UNREADABLE,
                artifact("d") to ArtifactLocationStatus.AVAILABLE,
            ).hubSummary()

        assertEquals(
            listOf(ArtifactLocationStatus.SIZE_CHANGED to 2, ArtifactLocationStatus.UNREADABLE to 1),
            summary.attentionByStatus,
        )
        assertEquals(3, summary.attentionCount)
    }

    @Test
    fun `an empty index reports zero counts instead of inferred values`() {
        val summary = GpuIntegrationState().hubSummary()

        assertEquals(0, summary.indexedCount)
        assertEquals(0, summary.availableCount)
        assertEquals(0, summary.attentionCount)
        assertEquals(0, summary.unverifiedCount)
        assertTrue(summary.kindCounts.isEmpty())
        assertTrue(summary.attentionByStatus.isEmpty())
    }

    @Test
    fun `related evidence matches the same device serial or package and never the artifact itself`() {
        val selected = artifact("selected", serial = "serial-pixel", packageName = "com.example.app")
        val sameDevice = artifact("device", serial = "serial-pixel", packageName = "other.app")
        val samePackage = artifact("package", serial = "serial-rog", packageName = "com.example.app")
        val unrelated = artifact("unrelated", serial = "serial-rog", packageName = "other.app")

        val related = stateOf(selected to ArtifactLocationStatus.AVAILABLE, sameDevice to ArtifactLocationStatus.AVAILABLE, samePackage to ArtifactLocationStatus.AVAILABLE, unrelated to ArtifactLocationStatus.AVAILABLE).relatedArtifacts(selected)

        assertEquals(listOf("device", "package"), related.map { it.id })
    }

    @Test
    fun `evidence without device or package identity never claims a relation`() {
        val anonymous = artifact("anonymous", serial = null, packageName = null)
        val other = artifact("other", serial = null, packageName = null)

        assertTrue(stateOf(anonymous to ArtifactLocationStatus.AVAILABLE, other to ArtifactLocationStatus.AVAILABLE).relatedArtifacts(anonymous).isEmpty())
        assertTrue(stateOf(anonymous to ArtifactLocationStatus.AVAILABLE).relatedArtifacts(null).isEmpty())
    }

    @Test
    fun `related evidence groups follow the artifact kind order`() {
        val groups =
            relatedEvidenceGroups(
                listOf(
                    artifact("trace", kind = GpuArtifactKind.PERFETTO_TRACE),
                    artifact("frame", kind = GpuArtifactKind.AGI_FRAME_PROFILE),
                    artifact("shot", kind = GpuArtifactKind.SCREENSHOT),
                ),
            )

        assertEquals(
            listOf(GpuArtifactKind.AGI_FRAME_PROFILE, GpuArtifactKind.PERFETTO_TRACE, GpuArtifactKind.SCREENSHOT),
            groups.map { it.first },
        )
        assertEquals(listOf("frame"), groups.first().second.map { it.id })
    }

    @Test
    fun `artifact search matches recorded identity fields only`() {
        val trace = artifact("trace", packageName = "com.example.app", fileName = "com.example.app_2024.gputrace")
        val report = artifact("report", packageName = "com.other.app", fileName = "report.txt", kind = GpuArtifactKind.EXTERNAL_REPORT)

        assertEquals(listOf(trace, report), filterArtifacts(listOf(trace, report), "  "))
        assertEquals(listOf(trace), filterArtifacts(listOf(trace, report), "EXAMPLE"))
        assertEquals(listOf(trace), filterArtifacts(listOf(trace, report), "gputrace"))
        assertEquals(listOf(report), filterArtifacts(listOf(trace, report), "external_report"))
        assertTrue(filterArtifacts(listOf(trace, report), "unmatched-query").isEmpty())
    }

    @Test
    fun `size and timestamp formatting stay evidence shaped`() {
        assertEquals("128 B", formatArtifactSize(128))
        assertEquals("2.0 KiB", formatArtifactSize(2048))
        assertEquals("5.0 MiB", formatArtifactSize(5L * 1024 * 1024))
        assertEquals("—", (null as Instant?).displayTimestamp())
        assertTrue(Instant.parse("2024-11-15T06:32:00Z").displayTimestamp().matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    private fun stateOf(vararg entries: Pair<GpuArtifact, ArtifactLocationStatus?>): GpuIntegrationState {
        val artifacts = entries.map { it.first }
        return GpuIntegrationState(
            artifacts = artifacts,
            selectedArtifactId = artifacts.firstOrNull()?.id,
            availabilityByArtifactId =
                entries
                    .mapNotNull { (artifact, status) -> status?.let { artifact.id to GpuArtifactAvailability(it, artifact.path, ArtifactPrimaryAction.NONE) } }
                    .toMap(),
        )
    }

    private fun artifact(
        id: String,
        kind: GpuArtifactKind = GpuArtifactKind.AGI_FRAME_PROFILE,
        serial: String? = "serial-pixel",
        packageName: String? = "com.example.app",
        fileName: String = "$id.gputrace",
    ) = GpuArtifact(
        id = id,
        kind = kind,
        path = Path.of("/tmp", fileName),
        sha256 = "sha256-$id",
        sizeBytes = 2048,
        agiVersion = "3.3.3",
        device = serial?.let { GpuDeviceContext(it, "Pixel 8 Pro", 34, "Arm", "Mali-G715", "r45p0") },
        packageName = packageName,
        graphicsApi = null,
        capturedAt = Instant.parse("2024-11-15T06:32:00Z"),
        notes = null,
        openRoute = ArtifactOpenRoute.AGI,
    )
}
