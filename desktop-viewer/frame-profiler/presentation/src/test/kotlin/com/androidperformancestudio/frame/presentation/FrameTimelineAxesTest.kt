package com.androidperformancestudio.frame.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTimelineAxesTest {
    @Test
    fun `duration ruler matches reference increments and contains the longest frame`() {
        assertEquals(listOf(0.0, 16.0, 32.0, 48.0, 64.0), frameTimelineDurationTicks(34_000_000L))
        assertEquals(listOf(0.0, 8.0, 16.0, 24.0, 32.0), frameTimelineDurationTicks(32_000_000L))
        assertEquals(listOf(0.0, 32.0, 64.0, 96.0, 128.0), frameTimelineDurationTicks(64_000_001L))
    }

    @Test
    fun `zero tiny and extreme durations retain a finite nonzero ruler`() {
        listOf(0L, 1L, 999_999L, Long.MAX_VALUE).forEach { durationNs ->
            val ticks = frameTimelineDurationTicks(durationNs)
            assertEquals(5, ticks.size)
            assertEquals(0.0, ticks.first())
            assertTrue(ticks.last().isFinite() && ticks.last() >= durationNs / 1_000_000.0)
            assertTrue(ticks.zipWithNext().all { (first, second) -> second > first })
        }
        assertEquals(
            listOf("0", "0.25", "0.5", "0.75", "1"),
            frameTimelineDurationTicks(0).map(::formatFrameTimelineTick),
        )
    }

    @Test
    fun `frame ticks span the visible window without fabricating frames in small captures`() {
        assertEquals(listOf(500, 624, 749, 874, 999), FrameTimelineWindow(500, 500).axisIndices())
        assertEquals(listOf(625, 687, 749, 811, 874), FrameTimelineWindow(625, 250).axisIndices())
        assertEquals(listOf(0), FrameTimelineWindow(0, 1).axisIndices())
        assertEquals(listOf(0, 1), FrameTimelineWindow(0, 2).axisIndices())
        assertTrue(FrameTimelineWindow(0, 0).axisIndices().isEmpty())
    }
}
