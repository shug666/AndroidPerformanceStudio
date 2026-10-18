package com.androidperformancestudio.frame.app

import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.frame.analysis.FrameTimelineRow
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameTimelineArtifactAnalyzerTest {
    @Test
    fun `real captured trace diagnoses selected process mismatch when fixture paths are supplied`() =
        runBlocking {
            val (trace, artifact) = capturedTraceFixture()
            val result = PerfettoFrameTimelineArtifactAnalyzer().analyze(trace, artifact)

            val failure = assertIs<FrameTimelineProcessingResult.Failure>(result)
            assertTrue(failure.reason.contains("FrameTimeline rows"), failure.reason)
            assertTrue(failure.reason.contains("FrameTimeline rows, but none match selected process PID"), failure.reason)
            assertTrue(failure.reason.contains("PID ${artifact.process?.pid}"))
        }

    @Test
    fun `real captured trace maps rows for its selected process when fixture paths are supplied`() =
        runBlocking {
            val (trace, artifact) = capturedTraceFixture()
            val result = PerfettoFrameTimelineArtifactAnalyzer().analyze(trace, artifact)

            val success = assertIs<FrameTimelineProcessingResult.Success>(result)
            assertTrue(success.result.frames.isNotEmpty())
            assertTrue(success.result.frames.all { it.source == FrameSource.PERFETTO })
        }

    @Test
    fun `process match returns selected rows without querying the full trace`() =
        runBlocking {
            val requestedProcessIds = mutableListOf<Int?>()

            val result =
                resolveFrameTimelineRows(12466) { processId ->
                    requestedProcessIds += processId
                    StudioResult.Success(listOf(row(12466)))
                }

            assertEquals(listOf<Int?>(12466), requestedProcessIds)
            assertEquals(listOf(row(12466)), assertIs<FrameTimelineRowsResolution.Rows>(result).value)
        }

    @Test
    fun `rows from other processes explain selected process mismatch`() =
        runBlocking {
            val requestedProcessIds = mutableListOf<Int?>()

            val result =
                resolveFrameTimelineRows(12466) { processId ->
                    requestedProcessIds += processId
                    StudioResult.Success(if (processId == null) listOf(row(1314), row(2612)) else emptyList())
                }

            assertEquals(listOf(12466, null), requestedProcessIds)
            val failure = assertIs<FrameTimelineRowsResolution.Failure>(result)
            assertTrue(failure.reason.contains("2 FrameTimeline rows"))
            assertTrue(failure.reason.contains("PID 12466"))
            assertTrue(failure.reason.contains("process may have restarted"))
        }

    @Test
    fun `empty trace gives an actionable no rendering message`() =
        runBlocking {
            val result = resolveFrameTimelineRows(12466) { StudioResult.Success(emptyList()) }

            val failure = assertIs<FrameTimelineRowsResolution.Failure>(result)
            assertTrue(failure.reason.contains("trace contains no FrameTimeline rows"))
            assertTrue(failure.reason.contains("Keep the selected app active and rendering"))
        }

    @Test
    fun `process query failures are returned without an unrelated global query`() =
        runBlocking {
            val requestedProcessIds = mutableListOf<Int?>()

            val result =
                resolveFrameTimelineRows(12466) { processId ->
                    requestedProcessIds += processId
                    StudioResult.Failure(StudioError(ErrorCategory.PROCESS_EXIT, "QUERY_FAILED", "query failed"))
                }

            assertEquals(listOf<Int?>(12466), requestedProcessIds)
            assertEquals("QUERY_FAILED: query failed", assertIs<FrameTimelineRowsResolution.Failure>(result).reason)
        }

    private fun row(frameId: Long) =
        FrameTimelineRow(
            frameId = frameId,
            expectedTs = 1_000L,
            expectedDur = 16_666_667L,
            actualTs = 1_000L,
            actualDur = 16_000_000L,
            surfaceToken = null,
            layerName = null,
            jankType = null,
            processName = null,
            surfaceFlingerJankType = null,
        )

    private fun capturedTraceFixture(): Pair<Path, com.androidperformancestudio.contracts.CaptureArtifact> {
        val tracePath = System.getenv("APS_FRAME_TIMELINE_TRACE")
        val artifactPath = System.getenv("APS_FRAME_TIMELINE_ARTIFACT")
        assumeTrue(!tracePath.isNullOrBlank() && !artifactPath.isNullOrBlank())
        val trace = Path.of(requireNotNull(tracePath))
        val artifact = CaptureArtifactJson.decode(Files.readString(Path.of(requireNotNull(artifactPath))))
        return trace to artifact
    }
}
