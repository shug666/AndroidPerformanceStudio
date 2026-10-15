package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuArtifactAvailabilityTest {
    @Test
    fun `uses route and runtime capability without changing evidence`() {
        val directory = createTempDirectory()
        val perfettoFile = directory.resolve("capture.perfetto-trace").also { it.writeText("trace") }
        val agiFile = directory.resolve("capture.gfxtrace").also { it.writeText("trace") }
        val indexer = AgiArtifactIndexer()
        val perfetto = indexer.import(perfettoFile)
        val agi = indexer.import(agiFile)
        val guiOnly =
            AgiCapability(
                executable = directory.resolve("agi"),
                version = "3.3.3",
                launchSupported = true,
                artifactOpenSupported = false,
                launchMode = AgiLaunchMode.GUI_ONLY,
                supportedArguments = emptySet(),
                warnings = emptyList(),
            )

        assertEquals(
            ArtifactPrimaryAction.OPEN_PERFETTO,
            artifactAvailability(perfetto, null, indexer).primaryAction,
        )
        assertEquals(
            ArtifactPrimaryAction.LAUNCH_AGI,
            artifactAvailability(agi, guiOnly, indexer).primaryAction,
        )

        agiFile.deleteExisting()
        assertEquals(ArtifactPrimaryAction.NONE, artifactAvailability(agi, guiOnly, indexer).primaryAction)
    }

    @Test
    fun `routing distinguishes verified AGI opening from GUI only launch`() {
        val directory = Files.createTempDirectory("gpu-availability-test")
        try {
            val indexer = AgiArtifactIndexer()
            val cases = listOf(
                Triple("capture.pftrace", null, ArtifactPrimaryAction.OPEN_PERFETTO),
                Triple("report.html", null, ArtifactPrimaryAction.OPEN_DESKTOP),
                Triple("capture.bin", null, ArtifactPrimaryAction.NONE),
                Triple("capture.gfxtrace", null, ArtifactPrimaryAction.NONE),
                Triple("capture.gfxtrace", capability(canOpenArtifact = false), ArtifactPrimaryAction.LAUNCH_AGI),
                Triple("capture.gfxtrace", capability(canOpenArtifact = true), ArtifactPrimaryAction.OPEN_AGI),
            )
            cases.forEach { (name, capability, expectedAction) ->
                val file = directory.resolve(name)
                Files.writeString(file, "trace")
                val artifact = indexer.import(file)

                val availability = artifactAvailability(artifact, capability, indexer)

                assertEquals(ArtifactLocationStatus.AVAILABLE, availability.locationStatus, name)
                assertEquals(file, availability.resolvedPath, name)
                assertEquals(expectedAction, availability.primaryAction, name)
                assertEquals(expectedAction != ArtifactPrimaryAction.NONE, availability.canOpen, name)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `content integrity overrides every available viewer route`() {
        val directory = Files.createTempDirectory("gpu-availability-test")
        try {
            val indexer = AgiArtifactIndexer()
            listOf("capture.pftrace", "capture.gfxtrace", "report.html").forEach { name ->
                val file = directory.resolve(name)
                Files.writeString(file, "trace")
                val artifact = indexer.import(file)
                val verified = artifactAvailability(artifact, capability(canOpenArtifact = true), indexer)
                assertTrue(verified.canOpen, name)
                Files.writeString(file, "other")

                val changed = artifactAvailability(artifact, capability(canOpenArtifact = true), indexer)

                assertEquals(ArtifactLocationStatus.CONTENT_CHANGED, changed.locationStatus, name)
                assertEquals(ArtifactPrimaryAction.NONE, changed.primaryAction, name)
                assertNull(changed.resolvedPath, name)
                assertFalse(changed.canOpen, name)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun capability(canOpenArtifact: Boolean) = AgiCapability(
        executable = Path.of("/tmp/agi"),
        version = "3.3.3",
        launchSupported = true,
        artifactOpenSupported = canOpenArtifact,
        launchMode = if (canOpenArtifact) AgiLaunchMode.VERIFIED_CLI else AgiLaunchMode.GUI_ONLY,
        supportedArguments = if (canOpenArtifact) setOf("--capture") else emptySet(),
        warnings = emptyList(),
        artifactOpenArgument = if (canOpenArtifact) "--capture" else null,
    )
}
