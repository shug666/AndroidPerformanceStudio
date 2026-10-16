package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.CpuArchitecture
import com.androidperformancestudio.platform.toolchain.HostOperatingSystem
import com.androidperformancestudio.platform.toolchain.HostPlatform
import com.androidperformancestudio.platform.toolchain.HostPlatformDetector
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

class TraceProcessorToolResolverTest {
    @Test
    fun `pinned manifest covers every supported desktop host`() {
        val expected =
            setOf(
                HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.X64),
                HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64),
                HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64),
                HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.ARM64),
                HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
            )

        assertEquals(expected, TraceProcessorManifest.pinnedV57_2().checksums.keys)
    }

    @Test
    fun `resolves only a checksum verified packaged binary for the pinned schema`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-perfetto")
            val binary = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(binary.parent)
            Files.writeString(binary, "pinned binary")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64)
            val expectedHash = ArtifactFileEvidence.sha256(binary).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(binary, tool.path)
            assertEquals(expectedHash, tool.sha256)
        }

    @Test
    fun `rejects an explicit override that does not report the pinned version`() =
        runBlocking {
            val binary = Files.createTempFile("override-trace-processor", "")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = null,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = binary,
                    processRunner = VersionRunner("Trace Processor v56.0"),
                ).resolve()

            assertEquals(
                "TRACE_PROCESSOR_INCOMPATIBLE",
                assertIs<StudioResult.Failure>(result).error.code,
            )
        }

    @Test
    fun `rejects a packaged checksum mismatch without selecting an arbitrary PATH tool`() =
        runBlocking {
            val resources = Files.createTempDirectory("bad-packaged-perfetto")
            val binary = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(binary.parent)
            Files.writeString(binary, "not the pinned binary")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to "a".repeat(64))),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            assertEquals("TRACE_PROCESSOR_CHECKSUM_MISMATCH", assertIs<StudioResult.Failure>(result).error.code)
        }

    @Test
    fun `reports actionable not found instead of falling back to PATH`() =
        runBlocking {
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.ARM64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to "a".repeat(64))),
                    applicationResourcesPath = Files.createTempDirectory("no-packaged-tool"),
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            assertEquals("TRACE_PROCESSOR_NOT_FOUND", assertIs<StudioResult.Failure>(result).error.code)
            assertTrue(assertIs<StudioResult.Failure>(result).error.message.contains("explicitly"))
        }

    @Test
    fun `accepts an explicit compatible override and records its checksum`() =
        runBlocking {
            val binary = Files.createTempFile("override-trace-processor", "")
            Files.writeString(binary, "compatible override")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = null,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = binary,
                    processRunner = VersionRunner("Perfetto Trace Processor v57.2"),
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(ArtifactFileEvidence.sha256(binary).value, tool.sha256)
            assertEquals("v57.2", tool.version)
        }

    @Test
    fun `falls back to the installed copy when the packaged copy lost its executable bit`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-noexec")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            // deliberately leave packaged non-executable
            val installedDir = Files.createTempDirectory("installed-tool")
            val installed = installedDir.resolve("trace_processor_shell")
            Files.writeString(installed, "pinned binary")
            installed.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val expectedHash = ArtifactFileEvidence.sha256(installed).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = installedDir,
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(installed, tool.path)
            assertEquals(expectedHash, tool.sha256)
        }

    @Test
    fun `reports not executable when self-heal cannot write to the user cache`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-noexec-unwritable")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val expectedHash = ArtifactFileEvidence.sha256(packaged).value
            // Block self-heal: make the cache root a regular file so createDirectories fails.
            val blockedCache = Files.createTempFile("blocked-cache", "")

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = blockedCache,
                    configuredOverride = null,
                ).resolve()

            val error = assertIs<StudioResult.Failure>(result).error
            assertEquals("TRACE_PROCESSOR_NOT_EXECUTABLE", error.code)
            assertTrue(error.message.contains("PERFETTO_TRACE_PROCESSOR_PATH"))
            assertTrue(error.message.contains("install-trace-processor.sh"))
        }

    @Test
    fun `resolves the installed copy when nothing is packaged`() =
        runBlocking {
            val installedDir = Files.createTempDirectory("installed-only")
            val installed = installedDir.resolve("trace_processor_shell")
            Files.writeString(installed, "pinned binary")
            installed.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val expectedHash = ArtifactFileEvidence.sha256(installed).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = Files.createTempDirectory("no-packaged-tool"),
                    installedToolsPath = installedDir,
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(installed, tool.path)
        }

    @Test
    fun `an explicit override takes precedence over a non-executable packaged copy`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-noexec-override")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            val override = Files.createTempFile("override-trace-processor", "")
            Files.writeString(override, "compatible override")
            override.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = override,
                    processRunner = VersionRunner("Perfetto Trace Processor v57.2"),
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(override, tool.path)
            assertEquals("v57.2", tool.version)
        }

    @Test
    fun `a packaged checksum mismatch is surfaced even when the binary is not executable`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-badbytes-noexec")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "not the pinned binary")
            // deliberately leave non-executable
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to "a".repeat(64))),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            assertEquals("TRACE_PROCESSOR_CHECKSUM_MISMATCH", assertIs<StudioResult.Failure>(result).error.code)
        }

    @Test
    fun `self-heals by copying a non-executable packaged binary into the user cache`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-noexec-selfheal")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            // packaged is deliberately non-executable
            val cacheDir = Files.createTempDirectory("selfheal-cache")
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val expectedHash = ArtifactFileEvidence.sha256(packaged).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = cacheDir,
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            val healed = cacheDir.resolve("trace_processor_shell")
            assertEquals(healed, tool.path)
            assertEquals(expectedHash, tool.sha256)
            assertTrue(Files.isExecutable(healed))
        }

    @Test
    fun `self-heal overwrites a corrupt cache copy from the good packaged bytes`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-good-cache-corrupt")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            val cacheDir = Files.createTempDirectory("selfheal-overwrite")
            Files.createDirectories(cacheDir)
            val stale = cacheDir.resolve("trace_processor_shell")
            Files.writeString(stale, "corrupt stale copy")
            stale.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val expectedHash = ArtifactFileEvidence.sha256(packaged).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = cacheDir,
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(cacheDir.resolve("trace_processor_shell"), tool.path)
            assertEquals(expectedHash, tool.sha256)
            assertTrue(Files.isExecutable(tool.path))
        }

    @Test
    fun `self-heal yields to an explicit override`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-noexec-override-selfheal")
            val packaged = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(packaged.parent)
            Files.writeString(packaged, "pinned binary")
            val override = Files.createTempFile("override-trace-processor", "")
            Files.writeString(override, "compatible override")
            override.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("selfheal-cache"),
                    configuredOverride = override,
                    processRunner = VersionRunner("Perfetto Trace Processor v57.2"),
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(override, tool.path)
        }

    private class VersionRunner(
        private val version: String,
    ) : HostProcessRunner {
        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult =
            HostProcessTextResult(-1, 0, version, "", Duration.ZERO, false, false)

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
    }
}
