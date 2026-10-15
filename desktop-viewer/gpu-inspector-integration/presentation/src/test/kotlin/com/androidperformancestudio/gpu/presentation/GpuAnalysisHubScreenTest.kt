package com.androidperformancestudio.gpu.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GpuAnalysisHubScreenTest {
    @Test
    fun `selecting a connected device dispatches its serial`() = runDesktopComposeUiTest(width = 1200, height = 800) {
        var selectedSerial by mutableStateOf<String?>(null)
        val device = GpuDeviceContext(
            serial = "serial-1",
            model = "Test Phone",
            apiLevel = null,
            gpuVendor = null,
            gpuRenderer = null,
            driverVersion = null,
        )

        setContent {
            ViewerTheme(darkTheme = false) {
                GpuAnalysisHubScreen(
                    state = GpuIntegrationState(devices = listOf(device)),
                    actions =
                        GpuIntegrationActions(
                            onOpenArtifact = {},
                            onVerifyArtifact = {},
                            onRevealArtifact = {},
                            onRelocateArtifact = {},
                            onSelectDevice = { selectedSerial = it },
                        ),
                    language = UiLanguage.ENGLISH,
                )
            }
        }

        onNodeWithContentDescription("Device / Graphics Context").performClick()
        onNodeWithText("Test Phone · serial-1").performClick()

        assertEquals("serial-1", selectedSerial)
    }

    @Test
    fun `available artifacts expose the correct row and detail opening actions`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val artifact = artifact()
        var state by mutableStateOf(artifactState(artifact, ArtifactPrimaryAction.OPEN_PERFETTO))
        val opened = mutableListOf<GpuArtifact>()
        setContent {
            ViewerTheme(darkTheme = false) {
                GpuAnalysisHubScreen(
                    state,
                    GpuIntegrationActions(
                        onOpenArtifact = { opened += it },
                        onVerifyArtifact = {},
                        onRevealArtifact = {},
                        onRelocateArtifact = {},
                    ),
                    UiLanguage.ENGLISH,
                )
            }
        }

        listOf(
            ArtifactPrimaryAction.OPEN_PERFETTO to "Open in Perfetto",
            ArtifactPrimaryAction.LAUNCH_AGI to "Launch AGI",
            ArtifactPrimaryAction.OPEN_AGI to "Open in AGI",
            ArtifactPrimaryAction.OPEN_DESKTOP to "Open",
        ).forEach { (action, label) ->
            runOnIdle { state = artifactState(artifact, action) }
            onAllNodesWithText(label).assertCountEquals(2)
            onAllNodesWithText(label)[0].assertIsEnabled().performClick()
            onAllNodesWithText(label)[1].assertIsEnabled().performClick()
        }

        assertEquals(List(8) { artifact }, opened)
    }

    @Test
    fun `unverified locations disable opening while preserving recovery actions`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val artifact = artifact()
        var state by mutableStateOf(artifactState(artifact, ArtifactPrimaryAction.NONE, ArtifactLocationStatus.MISSING))
        val callbacks = mutableListOf<Pair<String, GpuArtifact>>()
        setContent {
            ViewerTheme(darkTheme = false) {
                GpuAnalysisHubScreen(
                    state,
                    GpuIntegrationActions(
                        onOpenArtifact = { callbacks += "open" to it },
                        onVerifyArtifact = { callbacks += "verify" to it },
                        onRevealArtifact = { callbacks += "reveal" to it },
                        onRelocateArtifact = { callbacks += "relocate" to it },
                    ),
                    UiLanguage.ENGLISH,
                )
            }
        }

        listOf(
            ArtifactLocationStatus.MISSING to "File missing",
            ArtifactLocationStatus.SIZE_CHANGED to "File size changed",
            ArtifactLocationStatus.CONTENT_CHANGED to "File content changed",
            ArtifactLocationStatus.UNREADABLE to "File cannot be verified",
        ).forEach { (status, label) ->
            runOnIdle { state = artifactState(artifact, ArtifactPrimaryAction.NONE, status) }
            onNodeWithText(label).assertExists()
            onAllNodesWithText("UNAVAILABLE").assertCountEquals(2)
            onAllNodesWithText("UNAVAILABLE")[0].assertIsNotEnabled()
            onAllNodesWithText("UNAVAILABLE")[1].assertIsNotEnabled()
            onNodeWithText("Verify").assertIsEnabled().performClick()
            onNodeWithText("Show in Files").assertIsEnabled().performClick()
            onNodeWithText("Relocate").assertIsEnabled().performClick()
        }

        assertEquals(List(4) { listOf("verify" to artifact, "reveal" to artifact, "relocate" to artifact) }.flatten(), callbacks)
        runOnIdle { state = state.copy(isBusy = true) }
        onNodeWithText("Verify").assertIsNotEnabled()
        onNodeWithText("Show in Files").assertIsNotEnabled()
        onNodeWithText("Relocate").assertIsNotEnabled()
    }

    @Test
    fun `device errors and refresh state leave artifact evidence available`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val artifact = artifact()
        var state by mutableStateOf(artifactState(artifact, ArtifactPrimaryAction.OPEN_PERFETTO).copy(deviceError = "ADB is unavailable"))
        var refreshed = 0
        var traceAnalyzerOpened = 0
        setContent {
            ViewerTheme(darkTheme = false) {
                GpuAnalysisHubScreen(
                    state,
                    GpuIntegrationActions(
                        onOpenArtifact = {},
                        onVerifyArtifact = {},
                        onRevealArtifact = {},
                        onRelocateArtifact = {},
                        onRefreshDevices = { refreshed++ },
                        onOpenTraceAnalyzer = { traceAnalyzerOpened++ },
                    ),
                    UiLanguage.ENGLISH,
                )
            }
        }

        onNodeWithText("ADB is unavailable").assertExists()
        onNodeWithText("No authorized Android devices are connected.").assertExists()
        onNodeWithContentDescription("Device / Graphics Context").assertIsNotEnabled()
        onNodeWithText("Refresh devices").assertIsEnabled().performClick()
        onNodeWithText("Open Trace Analyzer").performClick()
        assertEquals(1, refreshed)
        assertEquals(1, traceAnalyzerOpened)
        onAllNodesWithText("Open in Perfetto")[0].assertIsEnabled()

        runOnIdle { state = state.copy(isRefreshingDevices = true, deviceError = null) }
        onNodeWithText("Reading graphics properties…").assertExists()
        onNodeWithText("Refresh devices").assertIsNotEnabled()
        onNodeWithText("No authorized Android devices are connected.").assertDoesNotExist()
        onNodeWithText("ADB is unavailable").assertDoesNotExist()
        onAllNodesWithText("Open in Perfetto")[0].assertIsEnabled()

        runOnIdle { state = state.copy(isBusy = true) }
        onAllNodesWithText("Open in Perfetto")[0].assertIsNotEnabled()
        onAllNodesWithText("Open in Perfetto")[1].assertIsNotEnabled()
    }

    private fun artifact() = GpuArtifact(
        id = "capture",
        kind = GpuArtifactKind.PERFETTO_TRACE,
        path = Path.of("/tmp/capture.pftrace"),
        sha256 = "indexed-sha256",
        sizeBytes = 128,
        agiVersion = null,
        device = null,
        packageName = null,
        graphicsApi = null,
        capturedAt = null,
        notes = null,
        openRoute = ArtifactOpenRoute.PERFETTO,
    )

    private fun artifactState(
        artifact: GpuArtifact,
        action: ArtifactPrimaryAction,
        status: ArtifactLocationStatus = ArtifactLocationStatus.AVAILABLE,
    ) = GpuIntegrationState(
        capability = AgiCapability(
            executable = Path.of("/tmp/agi"),
            version = "3.3.3",
            launchSupported = true,
            artifactOpenSupported = action == ArtifactPrimaryAction.OPEN_AGI,
            launchMode = if (action == ArtifactPrimaryAction.OPEN_AGI) AgiLaunchMode.VERIFIED_CLI else AgiLaunchMode.GUI_ONLY,
            supportedArguments = emptySet(),
            warnings = emptyList(),
        ),
        artifacts = listOf(artifact),
        selectedArtifactId = artifact.id,
        availabilityByArtifactId = mapOf(
            artifact.id to GpuArtifactAvailability(status, artifact.path.takeIf { status == ArtifactLocationStatus.AVAILABLE }, action),
        ),
    )
}
