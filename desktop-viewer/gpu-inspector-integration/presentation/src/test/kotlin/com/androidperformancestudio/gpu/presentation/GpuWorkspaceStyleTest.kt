package com.androidperformancestudio.gpu.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuWorkspaceStyleTest {
    private val hub = source("GpuAnalysisHubScreen.kt")
    private val artifacts = source("GpuAnalysisHubArtifacts.kt")

    @Test
    fun `hub uses one localized shared workspace heading`() {
        assertEquals(1, Regex("StudioWorkspaceHeader\\(").findAll(hub).count())
        val heading = hub.substringAfter("private fun HubHeading(").substringBefore("private fun DeviceGraphicsSection(")
        assertTrue(heading.contains("Res.string.gpu_analysis_hub, language"))
        assertTrue(heading.contains("Res.string.tool_responsibility_hint, language"))
        assertTrue(heading.contains("supportingText = localizedStringResource(Res.string.artifact_count, language, artifactCount)"))
    }

    @Test
    fun `device capability and artifacts use compact peer panels without nested framing`() {
        assertTrue(hub.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(hub.contains(".background(LocalViewerColors.current.workspace)"))
        assertEquals(2, Regex("StudioPanel\\(").findAll(hub).count())
        assertEquals(2, Regex("StudioPanelHeader\\(").findAll(hub).count())
        assertEquals(1, Regex("StudioPanel\\(").findAll(artifacts).count())
        assertTrue(hub.contains("Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp))"))
        assertTrue(artifacts.contains("StudioPanelHeader("))
        assertTrue(artifacts.contains("items(state.artifacts, key = GpuArtifact::id)"))
        assertTrue(artifacts.contains(".heightIn(max = 190.dp)"))
        assertFalse(hub.contains("CompositionLocalProvider("))
        assertFalse(hub.contains("ViewerTheme("))
    }

    @Test
    fun `device and artifact actions keep their actual evidence identities and guards`() {
        assertTrue(hub.contains("onItemSelected = { it.serial?.let(actions.onSelectDevice) }"))
        assertTrue(hub.contains("onClick = actions.onRefreshDevices, enabled = !state.isRefreshingDevices"))
        listOf("onSelectArtifact", "onOpenArtifact", "onVerifyArtifact", "onRevealArtifact", "onRelocateArtifact").forEach { action ->
            assertTrue(artifacts.contains("actions.$action(artifact.id)") || artifacts.contains("actions.$action(artifact)"))
        }
        assertTrue(artifacts.contains("onClick = actions.onOpenTraceAnalyzer"))
        assertTrue(artifacts.contains("enabled = !state.isBusy && availability?.canOpen == true"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/gpu/presentation", file))
}
