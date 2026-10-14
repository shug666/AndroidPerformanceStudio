package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.PerfettoBufferPolicy
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoConfigTextBuilderTest {
    @Test
    fun `app template emits valid ftrace atrace and frame timeline sources`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.APP_PERFORMANCE,
                    targetPackage = "com.example.\"debug\\app",
                    durationSeconds = 15,
                    bufferSizeKb = 8192,
                    additionalCategories = listOf("dalvik", "sched/sched_process_exit"),
                ),
            )

        assertTrue(text.contains("duration_ms: 15000"))
        assertTrue(text.contains("size_kb: 8192"))
        assertTrue(text.contains("name: \"linux.ftrace\""))
        assertTrue(text.contains("atrace_categories: \"dalvik\""))
        assertTrue(text.contains("ftrace_events: \"sched/sched_process_exit\""))
        assertTrue(text.contains("atrace_apps: \"com.example.\\\"debug\\\\app\""))
        assertTrue(text.contains("name: \"android.surfaceflinger.frametimeline\""))
        assertTrue(text.contains("name: \"linux.process_stats\""))
        assertFalse(text.contains("android.atrace"))
        assertFalse(text.contains("android_atrace_config"))
    }

    @Test
    fun `memory template adds polled memory and targeted native heap sources`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.MEMORY_PROFILE,
                    targetPackage = "com.example.app",
                ),
            )

        assertTrue(text.contains("proc_stats_poll_ms: 1000"))
        assertTrue(text.contains("name: \"linux.sys_stats\""))
        assertTrue(text.contains("meminfo_period_ms: 1000"))
        assertTrue(text.contains("name: \"android.heapprofd\""))
        assertTrue(text.contains("process_cmdline: \"com.example.app\""))
    }

    @Test
    fun `input latency template uses ftrace atrace categories supported by production Perfetto`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(template = PerfettoTraceTemplate.INPUT_LATENCY),
            )

        assertTrue(text.contains("atrace_categories: \"input\""))
        assertTrue(text.contains("atrace_categories: \"view\""))
        assertTrue(text.contains("atrace_categories: \"wm\""))
        assertFalse(text.contains("android.input.inputevent"))
        assertFalse(text.contains("android_input_event_config"))
    }

    @Test
    fun `custom template is passed through unchanged`() {
        val custom = "buffers { size_kb: 1024 }\nduration_ms: 1000"

        assertEquals(
            custom,
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.CUSTOM,
                    customConfigText = custom,
                ),
            ),
        )
    }

    @Test
    fun `custom capture completion follows duration in custom config`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.CUSTOM,
                durationSeconds = 10,
                customConfigText = "buffers { size_kb: 1024 }\nduration_ms: 60000",
            )

        assertEquals(60_000L, automaticCompletionDelayMillis(config))
    }

    @Test
    fun `custom capture without duration is stopped explicitly by the user`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.CUSTOM,
                customConfigText = "buffers { size_kb: 1024 }",
            )

        assertEquals(null, automaticCompletionDelayMillis(config))
    }

    @Test
    fun `selected record page probes are composed into one trace config`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    targetPackage = "com.example.app",
                    enabledProbes =
                        setOf(
                            PerfettoProbe.SYSCALLS,
                            PerfettoProbe.FILESYSTEM_IO,
                            PerfettoProbe.PAGE_FAULTS,
                            PerfettoProbe.ART_RUNTIME,
                            PerfettoProbe.THERMAL,
                            PerfettoProbe.JAVA_HEAP_DUMP,
                            PerfettoProbe.POWER_RAILS,
                            PerfettoProbe.GPU_MEMORY,
                            PerfettoProbe.ANDROID_LOG,
                            PerfettoProbe.NETWORK_PACKETS,
                            PerfettoProbe.CALLSTACK_SAMPLING,
                            PerfettoProbe.TRACK_EVENTS,
                        ),
                ),
            )

        assertTrue(text.contains("raw_syscalls/sys_enter"))
        assertTrue(text.contains("block/block_rq_issue"))
        assertTrue(text.contains("exceptions/page_fault_user"))
        assertTrue(text.contains("thermal/thermal_temperature"))
        assertTrue(text.contains("name: \"android.java_hprof\""))
        assertTrue(text.contains("name: \"android.power\""))
        assertTrue(text.contains("name: \"android.gpu.memory\""))
        assertTrue(text.contains("name: \"android.log\""))
        assertTrue(text.contains("name: \"android.network_packets\""))
        assertTrue(text.contains("name: \"linux.perf\""))
        assertTrue(text.contains("name: \"track_event\""))
        assertEquals(1, Regex("name: \"linux.ftrace\"").findAll(text).count())
    }

    @Test
    fun `system overview default config aligns with official default preset`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    androidSdk = 33,
                ),
            )

        // android.log is on by default.
        assertTrue(text.contains("name: \"android.log\""))
        // sys_stats reports stat_counters.
        assertTrue(text.contains("stat_counters: STAT_CPU_TIMES"))
        assertTrue(text.contains("stat_counters: STAT_FORK_COUNT"))
        // atrace categories: all 24 official default categories.
        listOf(
            "aidl", "am", "binder_driver", "camera", "dalvik", "disk", "freq", "gfx",
            "hal", "idle", "input", "memory", "memreclaim", "network", "power", "res",
            "sched", "ss", "sync", "thermal", "view", "webview", "wm", "workq",
        ).forEach { category ->
            assertTrue(text.contains("atrace_categories: \"$category\""), "missing atrace category $category")
        }
        // No target package -> atrace_apps wildcard.
        assertTrue(text.contains("atrace_apps: \"*\""))
        // CPU_FREQUENCY power wildcard and psci events.
        assertTrue(text.contains("ftrace_events: \"power/*\""))
        assertTrue(text.contains("ftrace_events: \"power/psci_domain_idle_enter\""))
        assertTrue(text.contains("ftrace_events: \"power/psci_domain_idle_exit\""))
        // symbolize_ksyms unconditionally; disable_generic_events gated on T+.
        assertTrue(text.contains("symbolize_ksyms: true"))
        assertTrue(text.contains("disable_generic_events: true"))
        // flush_period_ms removed.
        assertFalse(text.contains("flush_period_ms"))
        // process_stats polling on by default (PROCESS_MEMORY_POLL in default probes).
        assertTrue(text.contains("proc_stats_poll_ms: 1000"))
        // Default fill policy is RING_BUFFER.
        assertTrue(text.contains("fill_policy: RING_BUFFER"))
    }

    @Test
    fun `system overview default config omits disable_generic_events on pre-T device`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    androidSdk = 31,
                ),
            )
        // symbolize_ksyms still unconditional.
        assertTrue(text.contains("symbolize_ksyms: true"))
        // disable_generic_events omitted on pre-T (would hard-fail strict --txt).
        assertFalse(text.contains("disable_generic_events"))
    }

    @Test
    fun `system overview default config omits disable_generic_events when sdk unknown`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(template = PerfettoTraceTemplate.SYSTEM_OVERVIEW),
            )
        assertFalse(text.contains("disable_generic_events"))
        assertTrue(text.contains("symbolize_ksyms: true"))
    }

    @Test
    fun `system overview with target package uses package not wildcard for atrace_apps`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    targetPackage = "com.example.app",
                    androidSdk = 33,
                ),
            )
        assertTrue(text.contains("atrace_apps: \"com.example.app\""))
        assertFalse(text.contains("atrace_apps: \"*\""))
    }

    @Test
    fun `process stats without poll probe omits proc_stats_poll_ms`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    enabledProbes = setOf(PerfettoProbe.PROCESS_STATS),
                ),
            )
        assertTrue(text.contains("name: \"linux.process_stats\""))
        assertTrue(text.contains("scan_all_processes_on_start: true"))
        assertTrue(text.contains("record_thread_names: true"))
        assertFalse(text.contains("proc_stats_poll_ms"))
    }

    @Test
    fun `process memory poll probe enables proc_stats_poll_ms`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    enabledProbes = setOf(PerfettoProbe.PROCESS_STATS, PerfettoProbe.PROCESS_MEMORY_POLL),
                ),
            )
        assertTrue(text.contains("proc_stats_poll_ms: 1000"))
    }

    @Test
    fun `discard buffer policy is emitted when selected`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    bufferPolicy = PerfettoBufferPolicy.DISCARD,
                ),
            )
        assertTrue(text.contains("fill_policy: DISCARD"))
    }

    @Test
    fun `other templates keep their existing atrace categories`() {
        // APP_PERFORMANCE must not gain the System Overview 24-category set.
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(template = PerfettoTraceTemplate.APP_PERFORMANCE),
            )
        assertTrue(text.contains("atrace_categories: \"am\""))
        // "freq"/"idle"/"memreclaim" are System Overview-only additions.
        assertFalse(text.contains("atrace_categories: \"freq\""))
        assertFalse(text.contains("atrace_categories: \"idle\""))
        assertFalse(text.contains("atrace_categories: \"memreclaim\""))
    }
}
