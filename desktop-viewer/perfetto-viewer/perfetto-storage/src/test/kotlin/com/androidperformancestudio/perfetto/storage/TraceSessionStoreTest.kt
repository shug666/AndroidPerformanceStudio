package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraceSessionStoreTest {
    @Test
    fun `round trips paths and text containing json delimiters`() {
        val root = Files.createTempDirectory("perfetto-session-store-test")
        val trace = root.resolve("trace,with{json}\"quotes.pftrace")
        Files.writeString(trace, "trace")
        val session =
            TraceSession(
                id = "session-1",
                traceFile = trace,
                captureConfig =
                    PerfettoCaptureConfig(
                        template = PerfettoTraceTemplate.APP_PERFORMANCE,
                        targetPackage = "com.example.app,debug",
                        durationSeconds = 15,
                        bufferSizeKb = 4096,
                        additionalCategories = listOf("gfx", "sched/sched_process_exit"),
                        customConfigText = "buffers {\n size_kb: 4096\n}",
                    ),
                deviceSerial = "emulator-5554",
                deviceModel = "Pixel {test}",
                androidSdk = 35,
                capturedAt = Instant.parse("2026-07-25T01:02:03Z"),
                durationNanos = 15_000_000_000,
                fileSizeBytes = 42,
                notes = "binder, \"jank\"",
                isProtected = true,
                artifact = PerfettoArtifactFactory(ByteArray(32) { 1 }).imported("artifact-1", trace, Instant.EPOCH),
            )
        val store = TraceSessionStore(root.resolve("index"))

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.save(session))
        val listed = assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent()).value
        val stored = listed.single()
        assertEquals(session.id, stored.id)
        assertEquals(session.captureConfig, stored.captureConfig)
        assertEquals("", stored.deviceSerial)
        assertEquals(session.deviceModel, stored.deviceModel)
        assertEquals(session.capturedAt, stored.capturedAt)
        assertEquals(session.notes, stored.notes)
        assertEquals(session.isProtected, stored.isProtected)
        assertEquals(Files.readAllBytes(trace).toList(), Files.readAllBytes(stored.traceFile).toList())
        assertEquals(stored.traceFile.toString(), stored.artifact?.location?.value)
        assertEquals(false, Files.readString(root.resolve("index/sessions.json")).contains("emulator-5554"))

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.delete(session.id))
        assertEquals(
            emptyList(),
            assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent()).value,
        )
    }

    @Test
    fun `saving a session preserves trace bytes in managed storage`() {
        val root = Files.createTempDirectory("perfetto-session-store-durable-test")
        val transientDirectory = Files.createDirectory(root.resolve("transient"))
        val transientTrace = Files.write(transientDirectory.resolve("capture.pftrace"), byteArrayOf(1, 2, 3, 4))
        val storageDirectory = root.resolve("index")
        val session = session("session-durable", transientTrace)
        val store = TraceSessionStore(storageDirectory)

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.save(session))
        Files.delete(transientTrace)

        val stored =
            assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent())
                .value
                .single()
        assertEquals(storageDirectory.resolve("traces").toAbsolutePath().normalize(), stored.traceFile.parent)
        assertEquals(listOf<Byte>(1, 2, 3, 4), Files.readAllBytes(stored.traceFile).toList())
        val storedTraceLocation =
            stored.traceFile
                .toAbsolutePath()
                .normalize()
                .toString()
        assertEquals(storedTraceLocation, stored.artifact?.location?.value)
    }

    @Test
    fun `resaving and deleting a managed session does not leak trace copies`() {
        val root = Files.createTempDirectory("perfetto-session-store-delete-test")
        val source = Files.write(root.resolve("capture.pftrace"), byteArrayOf(5, 6, 7))
        val storageDirectory = root.resolve("index")
        val store = TraceSessionStore(storageDirectory)
        val session = session("session-delete", source)

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.save(session))
        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.save(session))
        val stored =
            assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent())
                .value
                .single()
        assertEquals(1, Files.list(storageDirectory.resolve("traces")).use { it.count() })

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.delete(session.id))
        assertEquals(false, Files.exists(stored.traceFile))
    }

    private fun session(
        id: String,
        trace: Path,
    ): TraceSession =
        TraceSession(
            id = id,
            traceFile = trace,
            captureConfig = PerfettoCaptureConfig(PerfettoTraceTemplate.SYSTEM_OVERVIEW),
            deviceSerial = "emulator-5554",
            deviceModel = "Pixel",
            androidSdk = 35,
            capturedAt = Instant.EPOCH,
            durationNanos = 1_000,
            fileSizeBytes = Files.size(trace),
            artifact = PerfettoArtifactFactory(ByteArray(32) { 1 }).imported("artifact-$id", trace, Instant.EPOCH),
        )
}
