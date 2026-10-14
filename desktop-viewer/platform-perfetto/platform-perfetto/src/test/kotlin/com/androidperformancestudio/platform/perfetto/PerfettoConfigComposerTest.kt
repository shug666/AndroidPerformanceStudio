package com.androidperformancestudio.platform.perfetto

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

public class PerfettoConfigComposerTest {
    @Test
    public fun `composes only feature requested data sources`() {
        val text =
            PerfettoConfigComposer.compose(
                PerfettoCaptureDocument(
                    durationMillis = 2_000,
                    bufferSizeKb = 4_096,
                    dataSources =
                        listOf(
                            PerfettoDataSource("linux.ftrace", "ftrace_config {\n  ftrace_events: \"sched/sched_switch\"\n}"),
                        ),
                ),
            )

        assertContains(text, "duration_ms: 2000")
        assertContains(text, "name: \"linux.ftrace\"")
        assertFailsWith<IllegalArgumentException> {
            PerfettoCaptureDocument(1, 1, emptyList())
        }
    }

    @Test
    public fun `default fill policy is ring buffer`() {
        val text =
            PerfettoConfigComposer.compose(
                PerfettoCaptureDocument(
                    durationMillis = 2_000,
                    bufferSizeKb = 4_096,
                    dataSources = listOf(PerfettoDataSource("linux.ftrace")),
                ),
            )
        assertContains(text, "fill_policy: RING_BUFFER")
    }

    @Test
    public fun `discard fill policy is emitted when specified`() {
        val text =
            PerfettoConfigComposer.compose(
                PerfettoCaptureDocument(
                    durationMillis = 2_000,
                    bufferSizeKb = 4_096,
                    dataSources = listOf(PerfettoDataSource("linux.ftrace")),
                    fillPolicy = "DISCARD",
                ),
            )
        assertContains(text, "fill_policy: DISCARD")
    }

    @Test
    public fun `unsupported fill policy is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PerfettoCaptureDocument(
                durationMillis = 2_000,
                bufferSizeKb = 4_096,
                dataSources = listOf(PerfettoDataSource("linux.ftrace")),
                fillPolicy = "BOGUS",
            )
        }
    }
}
