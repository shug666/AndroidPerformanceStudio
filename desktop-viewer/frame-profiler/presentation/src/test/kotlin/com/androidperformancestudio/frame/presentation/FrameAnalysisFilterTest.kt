package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameAnalysisFilterTest {
    @Test
    fun `source and problem filters preserve only matching evidence`() {
        val frames =
            listOf(
                sample(1, FrameSource.GFXINFO, 8_000_000L),
                sample(2, FrameSource.PERFETTO, 24_000_000L),
                sample(3, FrameSource.PERFETTO, 8_000_000L).copy(platformJank = true),
            )
        val analysis = FrameJankAnalyzer().analyze(frames)

        val filtered = analysis.filtered(FrameSource.PERFETTO, FrameViewFilter.JANK_ONLY)

        assertEquals(listOf(2L, 3L), filtered.frames.map { it.sample.frameId })
        assertTrue(filtered.frames.all { it.isProblemFrame() })
        assertEquals(1, filtered.summary.deadlineMissFrames)
    }

    @Test
    fun `unknown budget remains unknown after filtering`() {
        val analysis =
            FrameJankAnalyzer().analyze(
                listOf(
                    sample(1, FrameSource.GFXINFO, 20_000_000L)
                        .copy(expectedDurationSource = ExpectedDurationSource.UNKNOWN),
                ),
            )

        val filtered = analysis.filtered(null, FrameViewFilter.DEADLINE_MISSES)

        assertTrue(filtered.frames.isEmpty())
    }

    @Test
    fun `filtering does not merge clusters across excluded frames`() {
        val analysis =
            FrameJankAnalyzer(maxSmoothGap = 1).analyze(
                listOf(
                    sample(1, FrameSource.PERFETTO, 24_000_000L),
                    sample(2, FrameSource.GFXINFO, 8_000_000L),
                    sample(3, FrameSource.GFXINFO, 8_000_000L),
                    sample(4, FrameSource.PERFETTO, 24_000_000L),
                ),
            )

        val filtered = analysis.filtered(FrameSource.PERFETTO, FrameViewFilter.DEADLINE_MISSES)

        assertEquals(2, analysis.clusters.size)
        assertEquals(2, filtered.clusters.size)
        assertEquals(listOf(listOf(1L), listOf(4L)), filtered.clusters.map { it.deadlineMissFrameIds })
    }

    private fun sample(
        id: Long,
        source: FrameSource,
        durationNs: Long,
    ): FrameSample =
        FrameSample(
            frameId = id,
            sessionId = "session",
            source = source,
            totalDurationNs = durationNs,
            intendedVsyncNs = id * 20_000_000L,
            expectedDurationNs = 16_666_667L,
            expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL,
        )
}
