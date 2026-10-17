package com.androidperformancestudio.perfetto.uiserver

import com.androidperformancestudio.model.StudioResult
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PerfettoUiServerTest {
    @Test
    fun `starting again reuses the running server`() {
        val uiServer = PerfettoUiServer(preferredPort = 0)
        try {
            uiServer.start(null)
            val firstServer = uiServer.runningServer()

            uiServer.start(null)

            assertSame(firstServer, uiServer.runningServer())
        } finally {
            uiServer.stop()
        }
    }

    @Test
    fun `start succeeds and exposes the bound ephemeral port`() {
        val uiServer = PerfettoUiServer(preferredPort = 0)
        try {
            val started = uiServer.start(null, PerfettoUiServer.ServeMode.TRACE_ONLY)

            assertTrue(started is StudioResult.Success)
            val boundPort = uiServer.port
            assertTrue(boundPort != null && boundPort > 0)
        } finally {
            uiServer.stop()
        }
    }

    @Test
    fun `falls back to an ephemeral port when the preferred port is occupied`() {
        ServerSocket(0).use { blocker ->
            val occupiedPort = blocker.localPort
            val uiServer = PerfettoUiServer(preferredPort = occupiedPort)
            try {
                val started = uiServer.start(null, PerfettoUiServer.ServeMode.TRACE_ONLY)

                assertTrue(started is StudioResult.Success)
                val boundPort = uiServer.port
                assertTrue(boundPort != null && boundPort != occupiedPort)
            } finally {
                uiServer.stop()
            }
        }
    }

    @Test
    fun `port is null while the server is stopped`() {
        val uiServer = PerfettoUiServer(preferredPort = 0)

        assertNull(uiServer.port)
    }

    @Test
    fun `finds Perfetto UI inside packaged Compose resources`() {
        val resources = Files.createTempDirectory("perfetto-packaged-resources")
        val ui = Files.createDirectories(resources.resolve("perfetto-ui"))
        Files.writeString(ui.resolve("index.html"), "<!doctype html>")

        val located =
            PerfettoUiServer.tryFindUiAssetsDir(
                repoRoot = Files.createTempDirectory("unrelated-working-directory"),
                configuredPath = null,
                environmentPath = null,
                applicationResourcesPath = resources.toString(),
            )

        assertEquals(ui, located)
    }

    @Test
    fun `configured UI path takes precedence over repository discovery`() {
        val configured = Files.createTempDirectory("perfetto-configured-ui")
        Files.writeString(configured.resolve("index.html"), "<!doctype html>")

        val located =
            PerfettoUiServer.tryFindUiAssetsDir(
                repoRoot = Files.createTempDirectory("unrelated-working-directory"),
                configuredPath = configured.toString(),
                environmentPath = null,
                applicationResourcesPath = null,
            )

        assertEquals(configured, located)
    }
}

private fun PerfettoUiServer.runningServer(): Any? =
    javaClass.getDeclaredField("server").run {
        isAccessible = true
        get(this@runningServer)
    }
