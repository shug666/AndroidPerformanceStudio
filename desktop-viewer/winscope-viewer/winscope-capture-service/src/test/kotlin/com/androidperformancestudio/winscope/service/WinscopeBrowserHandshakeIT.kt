@file:Suppress("MaxLineLength", "ReturnCount")

package com.androidperformancestudio.winscope.service

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.defaultAdbCommandExecutable
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration

/**
 * Optional end-to-end handshake check against the REAL bundled closure:
 *
 *     ./gradlew :winscope-capture-service:test \
 *         -Dwinscope.it.enabled=true -Dwinscope.it.marker=/tmp/winscope-it/done
 *
 * Starts the production [WinscopeCaptureService] (proxy on the fixed 5544 origin the
 * viewer hardcodes, viewer host on 8100), smoke-checks its own endpoints, then waits
 * for a driver script to run a headless browser against it and drop a marker file.
 */
@EnabledIfSystemProperty(named = "winscope.it.enabled", matches = "true")
class WinscopeBrowserHandshakeIT {
    private class NoDeviceAdb : AdbClient {
        override suspend fun listDevices(): List<AdbDevice> = emptyList()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun execOut(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbBinaryResult = AdbBinaryResult(0, ByteArray(0), ByteArray(0), Duration.ZERO)

        override suspend fun push(
            serial: String,
            localPath: Path,
            remotePath: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun pull(
            serial: String,
            remotePath: String,
            localPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun forward(
            serial: String,
            local: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun removeForward(
            serial: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun reverse(
            serial: String,
            remote: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun removeReverse(
            serial: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun bugreport(
            serial: String,
            outputPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)
    }

    @Test
    fun `bundled viewer completes handshake against the real implementation`() {
        val resources = moduleRoot()
        val closure = resources.resolve("winscope-viewer")
        assertTrue(Files.isRegularFile(closure.resolve("index.html")))
        val manifest = loadManifest(resources.resolve("winscope-viewer-manifest.json"))
        assertTrue(manifest.isNotEmpty(), "the generated closure manifest must be present")

        val adbClient: AdbClient =
            if ("true" == System.getProperty("winscope.it.device")) {
                DefaultAdbClient(defaultAdbCommandExecutable(AdbConfiguration(androidSdkPath = null)))
            } else {
                NoDeviceAdb()
            }
        val service =
            WinscopeCaptureService(
                adbClient = adbClient,
                viewerResourceRoot = closure,
                closureManifest = manifest,
                proxyPort = 5544,
                preferredViewerPort = 8100,
                browserLauncher = {},
            )
        service.start()
        try {
            // Handshake info for the driver script (token in URL is the designed flow).
            val handshakeFile = System.getProperty("winscope.it.handshake")
            if (handshakeFile != null) {
                Files.writeString(
                    Path.of(handshakeFile),
                    "viewer=http://127.0.0.1:${service.state.value.viewerPort}/index.html\n" +
                        "token=${service.sessionToken()}\n",
                )
            }
            // Loopback smoke checks of our own endpoints before the browser runs.
            val viewerPort = service.state.value.viewerPort ?: error("viewer port missing")
            val index = fetch("http://127.0.0.1:$viewerPort/index.html")
            assertTrue(index.first == 200 && index.second.contains("<html"), "viewer host must serve the bundle index.html")

            val proxy = fetch("http://127.0.0.1:5544/devices/")
            assertEquals(403, proxy.first, "the token gate must be live on the fixed proxy origin")

            val marker = System.getProperty("winscope.it.marker")
            if (marker != null) {
                val markerPath = Path.of(marker)
                val deadline = System.currentTimeMillis() + 120_000
                while (!Files.exists(markerPath) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
                assertTrue(Files.exists(markerPath), "driver script did not finish: $marker")
            }
            assertTrue(service.isRunning)
        } finally {
            service.stop()
        }
    }

    private fun moduleRoot(): Path {
        var dir = Paths.get(System.getProperty("user.dir"))
        repeat(6) {
            if (dir.resolve("winscope-resources/winscope-viewer/index.html").toFile().exists()) {
                return dir.resolve("winscope-resources")
            }
            dir = dir.parent ?: dir
        }
        error("winscope-resources directory not found from ${System.getProperty("user.dir")}")
    }

    private fun loadManifest(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val json = Json.parseToJsonElement(Files.readString(file))
        val files = json.jsonObject["files"]?.jsonObject ?: return emptyMap()
        return files.map { (path, checksum) -> path to checksum.jsonPrimitive.content }.toMap()
    }

    private fun fetch(url: String): Pair<Int, String> {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        val status = connection.responseCode
        val body = (connection.errorStream ?: connection.inputStream).bufferedReader().readText()
        return status to body
    }
}
