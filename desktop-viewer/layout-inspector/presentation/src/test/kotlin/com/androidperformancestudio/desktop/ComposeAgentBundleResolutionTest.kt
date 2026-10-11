package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Resolution is what makes a development run find the bundle produced by
 * :layout-inspector:compose-agent-build without any launch argument, so the candidate order and the
 * explicit override both have to stay pinned:
 */
class ComposeAgentBundleResolutionTest {
    @Test
    fun `an explicit configuration resolves against the working directory`() {
        val workingDirectory = tempDirectory()

        val resolved = resolveComposeAgentBundle(
            configured = "custom-bundle",
            workingDirectory = workingDirectory,
            isDirectory = { false },
        )

        assertEquals(workingDirectory.resolve("custom-bundle"), resolved)
    }

    @Test
    fun `an absolute configuration is used as it is`() {
        val absolute = tempDirectory().resolve("bundle")

        val resolved = resolveComposeAgentBundle(
            configured = absolute.toString(),
            workingDirectory = tempDirectory(),
            isDirectory = { false },
        )

        assertEquals(absolute, resolved)
    }

    @Test
    fun `a blank configuration falls through to the candidates`() {
        val workingDirectory = tempDirectory()
        Files.createDirectories(workingDirectory.resolve("compose-agent-bundle"))

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(configured = "   ", workingDirectory = workingDirectory),
        )
    }

    @Test
    fun `a bundle next to the application wins`() {
        val workingDirectory = tempDirectory()
        Files.createDirectories(workingDirectory.resolve("compose-agent-bundle"))
        Files.createDirectories(developmentBundle(workingDirectory))

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(configured = null, workingDirectory = workingDirectory),
        )
    }

    @Test
    fun `the development layout is found when nothing is shipped`() {
        val workingDirectory = tempDirectory()
        val development = developmentBundle(workingDirectory)
        Files.createDirectories(development)

        assertEquals(
            development,
            resolveComposeAgentBundle(configured = null, workingDirectory = workingDirectory),
        )
    }

    @Test
    fun `a missing bundle still resolves to the default location`() {
        val workingDirectory = tempDirectory()

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(configured = null, workingDirectory = workingDirectory),
        )
    }

    private fun developmentBundle(workingDirectory: Path): Path =
        workingDirectory
            .resolve("../layout-inspector/compose-agent-build/build/compose-agent-bundle")
            .normalize()

    private fun tempDirectory(): Path =
        Files.createTempDirectory("aps-bundle").resolve("desktop-app").also(Files::createDirectories)
}
