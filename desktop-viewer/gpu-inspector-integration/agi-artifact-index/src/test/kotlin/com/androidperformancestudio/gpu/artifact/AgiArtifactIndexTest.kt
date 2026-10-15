package com.androidperformancestudio.gpu.artifact

import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuCaptureContext
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.model.GraphicsImplementationContext
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgiArtifactIndexTest {
    @Test
    fun `indexes standard trace without parsing private payload`() {
        val file = createTempDirectory().resolve("sample.perfetto-trace")
        file.writeText("PERFETTO sample")
        val artifact = AgiArtifactIndexer().import(file)
        assertEquals(GpuArtifactKind.PERFETTO_TRACE, artifact.kind)
        assertEquals(ArtifactOpenRoute.PERFETTO, artifact.openRoute)
        assertTrue(AgiArtifactIndexer().verify(artifact))
    }

    @Test
    fun `routes imported artifact formats without claiming parsed payloads`() {
        val directory = createTempDirectory()
        val cases =
            listOf(
                Triple("capture.gfxtrace", GpuArtifactKind.AGI_FRAME_PROFILE, ArtifactOpenRoute.AGI),
                Triple("capture.agi", GpuArtifactKind.AGI_FRAME_PROFILE, ArtifactOpenRoute.AGI),
                Triple("capture.trace", GpuArtifactKind.AGI_SYSTEM_PROFILE, ArtifactOpenRoute.AGI),
                Triple("capture.pftrace", GpuArtifactKind.PERFETTO_TRACE, ArtifactOpenRoute.PERFETTO),
                Triple("capture.PNG", GpuArtifactKind.SCREENSHOT, ArtifactOpenRoute.DESKTOP),
                Triple("capture.jpeg", GpuArtifactKind.SCREENSHOT, ArtifactOpenRoute.DESKTOP),
                Triple("report.html", GpuArtifactKind.EXTERNAL_REPORT, ArtifactOpenRoute.DESKTOP),
                Triple("report.pdf", GpuArtifactKind.EXTERNAL_REPORT, ArtifactOpenRoute.DESKTOP),
                Triple("capture.bin", GpuArtifactKind.UNKNOWN, ArtifactOpenRoute.NONE),
            )

        cases.forEach { (name, kind, route) ->
            val artifact = AgiArtifactIndexer().import(directory.resolve(name).also { it.writeText("opaque") })
            assertEquals(kind, artifact.kind, name)
            assertEquals(route, artifact.openRoute, name)
            assertEquals(kind == GpuArtifactKind.UNKNOWN, artifact.warnings.isNotEmpty(), name)
        }
    }

    @Test
    fun `round trips complete artifact evidence`() {
        val dir = createTempDirectory()
        val file = dir.resolve("capture.gfxtrace")
        val alternate = dir.resolve("copy.gfxtrace")
        file.writeText("opaque")
        alternate.writeText("opaque")
        val context =
            GpuCaptureContext(
                device =
                    GpuDeviceContext(
                        serial = "device-1",
                        model = "Pixel",
                        apiLevel = 36,
                        gpuVendor = "Vendor",
                        gpuRenderer = "Renderer",
                        driverVersion = "1.2",
                        evidenceSources = mapOf("driverVersion" to "AGI metadata"),
                        displayName = "Pixel · device-1",
                        graphicsApis = setOf(GraphicsApi.OPENGL_ES, GraphicsApi.VULKAN),
                        graphicsImplementation =
                            GraphicsImplementationContext("ANGLE", "123", GraphicsApi.VULKAN, "device diagnostics"),
                    ),
                packageName = "example.app",
                graphicsApi = GraphicsApi.OPENGL_ES,
                graphicsImplementation =
                    GraphicsImplementationContext(
                        name = "ANGLE",
                        version = "123",
                        backendApi = GraphicsApi.VULKAN,
                        evidenceSource = "AGI metadata",
                    ),
                frameCapture = true,
            )
        val artifact =
            AgiArtifactIndexer()
                .import(file, context, agiVersion = "3.3.3", notes = "baseline")
                .copy(alternativePaths = listOf(alternate))
        val store = JsonAgiArtifactStore(dir.resolve("index.json"))

        store.save(listOf(artifact))

        assertEquals(artifact, store.load().single())
    }

    @Test
    fun `corrupt index is reported without being replaced`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText("{broken")

        assertFailsWith<IllegalStateException> { JsonAgiArtifactStore(index).load() }
        assertEquals("{broken", index.readText())
    }

    @Test
    fun `loads legacy array index`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText(
            """
            [{
              "id": "legacy",
              "kind": "PERFETTO_TRACE",
              "path": "/tmp/legacy.pftrace",
              "sha256": "abc",
              "sizeBytes": 3,
              "openCapability": "PERFETTO",
              "warnings": []
            }]
            """.trimIndent(),
        )

        val artifact = JsonAgiArtifactStore(index).load().single()

        assertEquals("legacy", artifact.id)
        assertEquals(ArtifactOpenRoute.PERFETTO, artifact.openRoute)
        assertNull(artifact.device)
        assertTrue(artifact.alternativePaths.isEmpty())
    }

    @Test
    fun `loads legacy device context without new optional fields`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText(
            """
            {
              "schemaVersion": 2,
              "artifacts": [{
                "id": "legacy-device",
                "kind": "AGI_FRAME_PROFILE",
                "path": "/tmp/legacy.gfxtrace",
                "sha256": "abc",
                "sizeBytes": 3,
                "device": {
                  "serial": "device-1",
                  "model": "Pixel",
                  "apiLevel": 36,
                  "gpuVendor": "Vendor",
                  "gpuRenderer": "Renderer",
                  "driverVersion": "1.2",
                  "evidenceSources": {"driverVersion": "AGI metadata"}
                },
                "openRoute": "AGI"
              }]
            }
            """.trimIndent(),
        )

        val artifact = JsonAgiArtifactStore(index).load().single()
        val device = requireNotNull(artifact.device)

        assertEquals("device-1", device.serial)
        assertEquals("Pixel", device.model)
        assertEquals(36, device.apiLevel)
        assertEquals(mapOf("driverVersion" to "AGI metadata"), device.evidenceSources)
        assertNull(device.displayName)
        assertTrue(device.graphicsApis.isEmpty())
        assertNull(device.graphicsImplementation)
    }

    @Test
    fun `loads legacy angle api as opengl with explicit implementation evidence`() {
        val index = createTempDirectory().resolve("index.json")
        index.writeText(
            """
            [{
              "id": "legacy-angle",
              "kind": "AGI_FRAME_PROFILE",
              "path": "/tmp/legacy.gfxtrace",
              "sha256": "abc",
              "sizeBytes": 3,
              "graphicsApi": "OPENGL_ON_ANGLE",
              "openCapability": "AGI"
            }]
            """.trimIndent(),
        )

        val artifact = JsonAgiArtifactStore(index).load().single()

        assertEquals(GraphicsApi.OPENGL_ES, artifact.graphicsApi)
        assertEquals(GraphicsImplementationContext("ANGLE", null, GraphicsApi.VULKAN, "legacy graphicsApi"), artifact.graphicsImplementation)
    }

    @Test
    fun `duplicate content retains every imported location`() {
        val dir = createTempDirectory()
        val firstFile = dir.resolve("first.gfxtrace").also { it.writeText("same") }
        val secondFile = dir.resolve("second.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val first = indexer.import(firstFile)

        val merged = indexer.mergeLocation(listOf(first), indexer.import(secondFile)).single()

        assertEquals(secondFile.toAbsolutePath(), merged.path)
        assertEquals(listOf(firstFile.toAbsolutePath()), merged.alternativePaths)
        assertEquals(ArtifactLocationStatus.AVAILABLE, indexer.resolveLocation(merged).status)
        assertEquals(first.id, merged.id)
        secondFile.deleteExisting()
        assertEquals(firstFile.toAbsolutePath(), indexer.resolveLocation(merged).path)
        assertTrue(indexer.verify(merged))
    }

    @Test
    fun `location status and relocation preserve content identity`() {
        val dir = createTempDirectory()
        val original = dir.resolve("capture.gfxtrace").also { it.writeText("same") }
        val replacement = dir.resolve("replacement.gfxtrace").also { it.writeText("same") }
        val wrong = dir.resolve("wrong.gfxtrace").also { it.writeText("different") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(original)
        original.deleteExisting()

        assertEquals(ArtifactLocationStatus.MISSING, indexer.resolveLocation(artifact).status)
        assertFalse(indexer.verify(artifact))
        assertFailsWith<IllegalArgumentException> { indexer.relocate(artifact, dir.resolve("missing.gfxtrace")) }
        assertFailsWith<IllegalArgumentException> { indexer.relocate(artifact, wrong) }
        val relocated = indexer.relocate(artifact, replacement)
        assertEquals(replacement.toAbsolutePath(), relocated.path)
        assertEquals(artifact.id, relocated.id)
        assertEquals(artifact.sha256, relocated.sha256)
        assertEquals(listOf(original.toAbsolutePath()), relocated.alternativePaths)
        assertTrue(indexer.verify(relocated))
    }

    @Test
    fun `same size replacement must still match indexed hash`() {
        val directory = createTempDirectory()
        val original = directory.resolve("capture.gfxtrace").also { it.writeText("same") }
        val wrong = directory.resolve("wrong.gfxtrace").also { it.writeText("else") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(original)
        original.writeText("else")

        assertEquals(ArtifactLocationStatus.CONTENT_CHANGED, indexer.resolveLocation(artifact).status)
        assertNull(indexer.resolveLocation(artifact).path)
        assertFalse(indexer.verify(artifact))
        assertFailsWith<IllegalArgumentException> { indexer.relocate(artifact, wrong) }
    }

    @Test
    fun `changed primary content falls back to a hash verified alternative`() {
        val directory = createTempDirectory()
        val primary = directory.resolve("capture.gfxtrace").also { it.writeText("same") }
        val alternative = directory.resolve("copy.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(primary).copy(alternativePaths = listOf(alternative))
        primary.writeText("else")

        val resolution = indexer.resolveLocation(artifact)

        assertEquals(ArtifactLocationStatus.AVAILABLE, resolution.status)
        assertEquals(alternative, resolution.path)
        assertTrue(indexer.verify(artifact))
    }

    @Test
    fun `all changed locations remain unavailable even when their sizes match`() {
        val directory = createTempDirectory()
        val primary = directory.resolve("capture.gfxtrace").also { it.writeText("same") }
        val alternative = directory.resolve("copy.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(primary).copy(alternativePaths = listOf(alternative))
        primary.writeText("else")
        alternative.writeText("else")

        assertEquals(ArtifactLocationStatus.CONTENT_CHANGED, indexer.resolveLocation(artifact).status)
        assertNull(indexer.resolveLocation(artifact).path)
        assertFalse(indexer.verify(artifact))
    }

    @Test
    fun `unreadable location fails closed and does not hide a valid alternative`() {
        val directory = createTempDirectory()
        val primary = directory.resolve("capture.gfxtrace").also { it.writeText("same") }
        val alternative = directory.resolve("copy.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(primary)
        primary.deleteExisting()
        Files.createSymbolicLink(primary, primary.fileName)

        assertEquals(ArtifactLocationStatus.UNREADABLE, indexer.resolveLocation(artifact).status)
        assertNull(indexer.resolveLocation(artifact).path)
        assertFalse(indexer.verify(artifact))
        val withAlternative = artifact.copy(alternativePaths = listOf(alternative))
        assertEquals(alternative, indexer.resolveLocation(withAlternative).path)
        assertTrue(indexer.verify(withAlternative))
    }

    @Test
    fun `changed file size is distinguished from a missing artifact`() {
        val file = createTempDirectory().resolve("capture.gfxtrace").also { it.writeText("same") }
        val indexer = AgiArtifactIndexer()
        val artifact = indexer.import(file)
        file.writeText("changed size")

        assertEquals(ArtifactLocationStatus.SIZE_CHANGED, indexer.resolveLocation(artifact).status)
        assertNull(indexer.resolveLocation(artifact).path)
        assertFalse(indexer.verify(artifact))
    }
}
