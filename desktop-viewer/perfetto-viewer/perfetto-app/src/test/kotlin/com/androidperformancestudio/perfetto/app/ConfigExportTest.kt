package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.capture.PerfettoConfigTextBuilder
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigExportTest {
    @Test
    fun `exports the generated textproto to a file`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                androidSdk = 33,
            )
        val destination = Files.createTempFile("config-export", ".txtpb")
        try {
            val result = exportConfigText(config, destination)
            assertEquals(StudioResult.Success(destination), result)
            val written = Files.readString(destination)
            assertEquals(PerfettoConfigTextBuilder.build(config), written)
            assertTrue(written.contains("name: \"linux.ftrace\""))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun `custom template exports the user textproto verbatim`() {
        val custom = "buffers { size_kb: 1024 }\nduration_ms: 1000\n"
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.CUSTOM,
                customConfigText = custom,
            )
        val destination = Files.createTempFile("config-export-custom", ".txtpb")
        try {
            exportConfigText(config, destination)
            assertEquals(custom, Files.readString(destination))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun `default filename derives from the template`() {
        val config = PerfettoCaptureConfig(template = PerfettoTraceTemplate.SYSTEM_OVERVIEW)
        assertEquals("system-overview-config.txtpb", defaultConfigFileName(config))
    }

    @Test
    fun `androidSdk 33 export contains disable_generic_events`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                androidSdk = 33,
            )
        val text = PerfettoConfigTextBuilder.build(config)
        assertTrue(text.contains("disable_generic_events: true"))
    }

    @Test
    fun `unknown sdk export omits disable_generic_events`() {
        val config = PerfettoCaptureConfig(template = PerfettoTraceTemplate.SYSTEM_OVERVIEW)
        val text = PerfettoConfigTextBuilder.build(config)
        assertTrue(!text.contains("disable_generic_events"))
    }
}
