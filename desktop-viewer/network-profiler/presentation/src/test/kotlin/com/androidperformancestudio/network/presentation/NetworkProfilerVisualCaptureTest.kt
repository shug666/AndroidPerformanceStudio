@file:Suppress("MagicNumber", "LongMethod")

package com.androidperformancestudio.network.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkCoverage
import com.androidperformancestudio.network.model.NetworkEvidenceCompleteness
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.model.NetworkPhase
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.model.NetworkSession
import com.androidperformancestudio.network.model.NetworkSessionStatus
import com.androidperformancestudio.network.model.NetworkTimeDomain
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NetworkProfilerVisualCaptureTest {
    @Test
    fun `HAR request workspace exposes documented columns and inferred phase treatment`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().testTag("network-profiler-visual-capture")) {
                        NetworkProfilerScreen(
                            state = networkVisualState(),
                            actions = NetworkProfilerActions(selectCall = {}),
                            language = UiLanguage.SIMPLIFIED_CHINESE,
                        )
                    }
                }
            }

            onNodeWithText("脱敏 URL").assertTextEquals("脱敏 URL")
            onNodeWithText("传输字节").assertTextEquals("传输字节")
            onNodeWithText("证据完整性").assertTextEquals("证据完整性")
            onNodeWithTag("network-request-row-har-1")
                .assertTextContains("HAR 导入")
                .assertTextContains("https://api.example.test/<redacted-path>?page=<redacted>")
            onNodeWithTag("network-phase-dns")
                .assertContentDescriptionEquals("DNS · 18.0 ms · 推断")
            onNodeWithText("authorization: <redacted>").assertTextEquals("authorization: <redacted>")
            onNodeWithTag("network-request-body")
                .assertContentDescriptionEquals("请求正文 · 此分析器不捕获正文内容")

            waitForIdle()
            val screenshot = onNodeWithTag("network-profiler-visual-capture").captureToImage()
            assertEquals(1440, screenshot.width)
            assertEquals(860, screenshot.height)
            val pixels = screenshot.toPixelMap()
            assertTrue(
                listOf(
                    pixels[8, 8],
                    pixels[screenshot.width / 3, 48],
                    pixels[screenshot.width / 2, screenshot.height / 2],
                    pixels[screenshot.width - 32, screenshot.height / 2],
                ).distinct().size > 1,
                "Network Profiler workspace should render visible panels",
            )
            System.getenv("APS_NETWORK_PROFILER_SCREENSHOT")?.let { output ->
                ImageIO.write(screenshot.toBufferedImage(), "png", Path.of(output).toFile())
            }
        }
}

/** Deterministic imported evidence used only for UI verification and screenshot capture. */
internal fun networkVisualState(): NetworkProfilerState {
    val calls =
        listOf(
            harCall(
                id = "har-1",
                method = "GET",
                url = "https://api.example.test/<redacted-path>?page=<redacted>",
                status = 200,
                durationMs = 284,
                responseBytes = 84_200,
                outcome = CallOutcome.COMPLETED,
            ),
            harCall(
                id = "har-2",
                method = "POST",
                url = "https://upload.example.test/<redacted-path>?token=<redacted>",
                status = 503,
                durationMs = 1_420,
                responseBytes = 1_980,
                outcome = CallOutcome.FAILED,
            ),
            harCall(
                id = "har-3",
                method = "GET",
                url = "https://cdn.example.test/<redacted-path>",
                status = 304,
                durationMs = 92,
                responseBytes = 640,
                outcome = CallOutcome.COMPLETED,
            ),
        )
    val coverage =
        NetworkCoverage(
            processIds = emptySet(),
            observedLibraries = setOf("Chrome HAR 1.2"),
            observedInstrumentationIds = emptySet(),
            instrumentationMode = InstrumentationMode.HAR_IMPORT,
            supportedEventKinds = setOf("request", "response", "timings"),
            knownLimitations = setOf("HAR does not expose request and response header phase boundaries."),
            windowStartedNs = 0,
            windowEndedNs = 1_600_000_000,
        )
    val session =
        NetworkSession(
            id = "visual-har",
            deviceSerial = null,
            packageName = "com.example.gallery",
            startedAt = Instant.parse("2026-10-14T10:00:00Z"),
            endedAt = Instant.parse("2026-10-14T10:00:02Z"),
            coverage = coverage,
            completeness =
                NetworkEvidenceCompleteness(
                    status = EvidenceCompleteness.PARTIAL,
                    droppedEvents = 0,
                    sequenceGaps = 0,
                    unpairedEvents = 0,
                    skippedRecords = 0,
                ),
            sourceTimeDomain = NetworkTimeDomain.HAR_WALL_CLOCK,
            sourceTimeOriginNs = 0,
            clockMapping = null,
            status = NetworkSessionStatus.COMPLETE,
            redactionPolicyVersion = 1,
            sourceFormatVersion = "1.2",
            sourceProducer = "Chrome DevTools",
        )
    val result = NetworkCaptureResult(session, calls)
    return NetworkProfilerState(
        result = result,
        summary = NetworkAnalyzer().summarize(calls),
        selectedCallId = "har-1",
    )
}

private fun harCall(
    id: String,
    method: String,
    url: String,
    status: Int,
    durationMs: Long,
    responseBytes: Long,
    outcome: CallOutcome,
): HttpCall {
    val phases =
        listOf(
            NetworkPhase(NetworkPhaseKind.DISPATCHER_QUEUE, null, null, NetworkConfidence.INFERRED, 8_000_000),
            NetworkPhase(NetworkPhaseKind.DNS, null, null, NetworkConfidence.INFERRED, 18_000_000),
            NetworkPhase(NetworkPhaseKind.CONNECT, null, null, NetworkConfidence.INFERRED, 34_000_000),
            NetworkPhase(NetworkPhaseKind.TLS, null, null, NetworkConfidence.APPROXIMATED, 28_000_000),
            NetworkPhase(NetworkPhaseKind.REQUEST_BODY, null, null, NetworkConfidence.INFERRED, 11_000_000),
            NetworkPhase(NetworkPhaseKind.SERVER_WAIT, null, null, NetworkConfidence.INFERRED, 126_000_000),
            NetworkPhase(NetworkPhaseKind.RESPONSE_BODY, null, null, NetworkConfidence.INFERRED, 59_000_000),
            NetworkPhase(NetworkPhaseKind.TOTAL, null, null, NetworkConfidence.INFERRED, durationMs * 1_000_000),
        )
    return HttpCall(
        callId = id,
        instrumentationId = null,
        method = method,
        redactedUrl = url,
        startedNs = 0,
        endedNs = durationMs * 1_000_000,
        exchanges =
            listOf(
                HttpExchange(
                    exchangeIndex = 0,
                    connectionId = null,
                    connectionUse = ConnectionUse.UNKNOWN,
                    protocol = "h2",
                    statusCode = status,
                    requestBytes = 1_240,
                    responseBytes = responseBytes,
                    decodedResponseBytes = responseBytes,
                    phases = phases,
                    cacheDisposition = CacheDisposition.UNKNOWN,
                    failure = null,
                    requestHeaders = mapOf("authorization" to "<redacted>", "content-type" to "application/json"),
                    responseHeaders = mapOf("set-cookie" to "<redacted>", "content-type" to "application/json"),
                ),
            ),
        outcome = outcome,
        source = NetworkEvidenceSource.HAR_IMPORT,
    )
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[x, y]
                image.setRGB(
                    x,
                    y,
                    ((color.alpha * 255).toInt() shl 24) or
                        ((color.red * 255).toInt() shl 16) or
                        ((color.green * 255).toInt() shl 8) or
                        (color.blue * 255).toInt(),
                )
            }
        }
    }
}
