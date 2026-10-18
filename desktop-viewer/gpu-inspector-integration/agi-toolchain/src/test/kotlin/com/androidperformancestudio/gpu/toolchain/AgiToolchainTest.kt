package com.androidperformancestudio.gpu.toolchain

import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgiToolchainTest {
    @Test
    fun `uses configured executable and detects gui only`() {
        val executable = executable()
        val runner = RecordingRunner()
        val locator = AgiLocator(runner, emptyMap(), "Linux")
        val capability = locator.locate(executable)

        assertEquals(executable, capability.executable)
        assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
        assertTrue(capability.launchSupported)
        assertFalse(capability.artifactOpenSupported)
        assertNull(capability.artifactOpenArgument)
        locator.launch(capability, listOf("--capture"))
        assertEquals(listOf(executable.toString()), runner.launchedArguments)
        val artifact = executable.parent.resolve("capture.gfxtrace").also { it.writeText("trace") }
        assertFailsWith<IllegalArgumentException> { locator.launchArtifact(capability, artifact) }
    }

    @Test
    fun `absent executable is unsupported and is not probed`() {
        val directory = createTempDirectory()
        val runner = RecordingRunner()
        val capability =
            AgiLocator(
                runner,
                mapOf("LOCALAPPDATA" to directory.toString(), "ProgramFiles" to directory.toString()),
                "Windows",
            ).locate(directory.resolve("missing.exe"))

        assertNull(capability.executable)
        assertEquals(AgiLaunchMode.UNSUPPORTED, capability.launchMode)
        assertFalse(capability.launchSupported)
        assertFalse(capability.artifactOpenSupported)
        assertTrue(capability.supportedArguments.isEmpty())
        assertEquals(0, runner.probeCount)
    }

    @Test
    fun `opens artifact only when executable advertises an artifact argument`() {
        val directory = createTempDirectory()
        val executable = directory.resolve("agi").also { it.writeText("binary") }
        val artifact = directory.resolve("capture.gfxtrace").also { it.writeText("trace") }
        executable.toFile().setExecutable(true)
        listOf("--artifact", "--open-artifact").forEach { argument ->
            val runner = RecordingRunner(help = ProcessResult(0, "usage\n  $argument <file>", "", false))
            val locator = AgiLocator(runner, emptyMap(), "Linux")
            val capability = locator.locate(executable)

            assertTrue(capability.artifactOpenSupported)
            assertEquals(argument, capability.artifactOpenArgument)
            locator.launchArtifact(capability, artifact)
            assertEquals(listOf(executable.toString(), argument, artifact.toAbsolutePath().toString()), runner.launchedArguments)
        }
    }

    @Test
    fun `successful help advertises only exact automation arguments`() {
        val executable = executable()
        val runner = RecordingRunner(help = ProcessResult(0, "  --device <serial>\n  --captures <directory>", "  --capture", false))
        val locator = AgiLocator(runner, emptyMap(), "Linux")
        val capability = locator.locate(executable)

        assertEquals(AgiLaunchMode.VERIFIED_CLI, capability.launchMode)
        assertEquals(setOf("--device", "--capture"), capability.supportedArguments)
        locator.launch(capability, listOf("--device=device-1", "--capture", "--package=example.app", "unverified"))
        assertEquals(listOf(executable.toString(), "--device=device-1", "--capture"), runner.launchedArguments)
    }

    @Test
    fun `failed or timed out help never verifies advertised arguments`() {
        val executable = executable()
        val advertised = "  --capture\n  --open-artifact <file>"
        listOf(ProcessResult(1, advertised, "failure", false), ProcessResult(0, advertised, "", true)).forEach { help ->
            val runner = RecordingRunner(help = help)
            val capability = AgiLocator(runner, emptyMap(), "Linux").locate(executable)

            assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
            assertTrue(capability.launchSupported)
            assertTrue(capability.supportedArguments.isEmpty())
            assertFalse(capability.artifactOpenSupported)
            assertNull(capability.artifactOpenArgument)
            assertTrue(capability.warnings.any { it.contains("help probe") && it.contains("not verified") })
        }
    }

    @Test
    fun `prose and longer option names do not advertise capabilities`() {
        val runner = RecordingRunner(help = ProcessResult(0, "Do not use --capture or --artifact.\n  --artifact-directory <path>", "", false))
        val capability = AgiLocator(runner, emptyMap(), "Linux").locate(executable())

        assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
        assertFalse(capability.artifactOpenSupported)
    }

    @Test
    fun `failed version output is not reported as a detected version`() {
        listOf(ProcessResult(1, "failure text", "", false), ProcessResult(0, "partial version", "", true)).forEach { version ->
            val runner = RecordingRunner(version = version)
            val capability = AgiLocator(runner, emptyMap(), "Linux").locate(executable())

            assertNull(capability.version)
            assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
            assertTrue(capability.warnings.any { it.contains("version") })
        }
    }

    @Test
    fun `persists the configured AGI executable`() {
        val directory = createTempDirectory()
        val preferenceFile = directory.resolve("preferences").resolve("agi-executable.txt")
        val executable = directory.resolve("Android GPU Inspector ").also { it.writeText("binary") }
        val store = AgiExecutablePreferenceStore(preferenceFile)

        assertNull(store.load())
        store.save(executable)
        assertEquals(executable.toAbsolutePath().normalize(), AgiExecutablePreferenceStore(preferenceFile).load())
        assertEquals(executable.toAbsolutePath().normalize().toString(), preferenceFile.readText())
        val replacement = directory.resolve("gapic").also { it.writeText("binary") }
        store.save(replacement)
        assertEquals(replacement.toAbsolutePath().normalize(), AgiExecutablePreferenceStore(preferenceFile).load())
    }

    @Test
    fun `empty or invalid executable preferences do not fabricate a path`() {
        val preferenceFile = createTempDirectory().resolve("agi-executable.txt")
        val store = AgiExecutablePreferenceStore(preferenceFile)
        listOf("", "  \n", "invalid\u0000path").forEach { contents ->
            preferenceFile.writeText(contents)
            assertNull(store.load())
        }
    }

    private fun executable(): Path =
        createTempDirectory().resolve("agi").also {
            it.writeText("binary")
            assertTrue(it.toFile().setExecutable(true))
        }

    private class RecordingRunner(
        private val help: ProcessResult = ProcessResult(0, "usage", "", false),
        private val version: ProcessResult = ProcessResult(0, "AGI 4.0", "", false),
    ) : HostProcessRunner {
        var probeCount: Int = 0
        var launchedArguments: List<String> = emptyList()

        override fun run(arguments: List<String>, timeout: Duration): ProcessResult {
            probeCount++
            return if (arguments.last() == "--version") version else help
        }

        override fun launch(arguments: List<String>): RunningHostProcess {
            launchedArguments = arguments
            return object : RunningHostProcess {
                override val pid: Long = -1
                override val isAlive: Boolean = false

                override fun terminate() = Unit
            }
        }
    }
}
