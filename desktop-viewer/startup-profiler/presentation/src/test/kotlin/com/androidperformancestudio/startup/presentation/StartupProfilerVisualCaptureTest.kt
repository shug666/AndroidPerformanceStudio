@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupEnvironmentEvidence
import com.androidperformancestudio.startup.model.StartupExperimentConfig
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPerfettoRootCauseEvidence
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupRunContext
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupTarget
import com.androidperformancestudio.startup.model.StartupTraceEvidence
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deterministic synthetic evidence, never injected into the production workspace. */
internal fun startupVisualState(): StartupProfilerState {
    val runs =
        (1..5).map { index ->
            val times = listOf(0L, 118L, 232L, 310L, 418L, 480L, 620L, 706L, 812L)
            val kinds =
                listOf(
                    StartupMilestoneKind.PROCESS_START,
                    StartupMilestoneKind.INITIALIZER_ENTER,
                    StartupMilestoneKind.AGENT_READY,
                    StartupMilestoneKind.ACTIVITY_PRE_CREATE,
                    StartupMilestoneKind.ACTIVITY_CREATED,
                    StartupMilestoneKind.ACTIVITY_STARTED,
                    StartupMilestoneKind.ACTIVITY_RESUMED,
                    StartupMilestoneKind.FIRST_FRAME,
                    StartupMilestoneKind.FULLY_DRAWN,
                )
            StartupRun(
                id = "run-$index",
                sessionId = "synthetic-visual",
                iteration = index,
                requestedType = StartupType.COLD,
                observedType = StartupType.COLD,
                platform =
                    PlatformLaunchMetrics(
                        totalTimeMs = 680L + index * 7,
                        displayedTimeMs = 472L + index * 5,
                        fullyDrawnTimeMs = 800L + index * 4,
                        complete = true,
                    ),
                milestones =
                    kinds.zip(times).map { (kind, time) ->
                        StartupMilestone(
                            kind,
                            elapsedRealtimeNs = (time + if (time > 0) index * 2 else 0) * 1_000_000,
                            source = StartupSource.AGENT,
                            confidence = EvidenceConfidence.EXACT,
                        )
                    },
                rawEvidence =
                    StartupRawEvidence(
                        "Status: ok\nLaunchState: COLD\nTotalTime: ${680 + index * 7}\nComplete",
                        agentAvailable = true,
                    ),
                context = StartupRunContext("visual-device", "com.example.app", "com.example.app/.MainActivity"),
                ttidEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
                ttfdEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
                agentFirstFrameEvidence = StartupMetricEvidence(StartupSource.AGENT, EvidenceConfidence.EXACT),
                compilationEvidence =
                    StartupCompilationEvidence(
                        CompilationMode.SPEED_PROFILE,
                        "verify",
                        "speed-profile",
                        verified = true,
                        profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
                        profileSourceDeclared = true,
                        baselineProfileArtifact = "sha256:synthetic-visual-fixture",
                    ),
                environmentEvidence = StartupEnvironmentEvidence("Pixel 8 Pro", 34, false, 84, false, 0),
                traceEvidence =
                    StartupTraceEvidence(
                        file = "/synthetic/startup.perfetto-trace",
                        captured = true,
                        rootCause =
                            StartupPerfettoRootCauseEvidence(
                                correlated = true,
                                correlationErrorBoundNs = 250_000,
                                limitations = listOf("Synthetic screenshot fixture; not a device measurement."),
                            ),
                    ),
            )
        }
    val analysis = StartupAnalyzer().analyze(runs)
    return StartupProfilerState(
        devices = listOf(StartupDevice("visual-device", "Pixel 8 Pro", apiLevel = 34)),
        selectedDeviceSerial = "visual-device",
        targets = listOf(StartupTarget("com.example.app", "com.example.app/.MainActivity", true)),
        selectedComponentName = "com.example.app/.MainActivity",
        config =
            StartupExperimentConfig(
                compilationMode = CompilationMode.SPEED_PROFILE,
                profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
            ),
        analysis = analysis,
        selectedRunId = "run-1",
        completedRuns = 5,
        totalRuns = 5,
    )
}

@OptIn(ExperimentalTestApi::class)
class StartupProfilerVisualCaptureTest {
    @Test
    fun `capture startup workspace with deterministic evidence`() =
        capture(1336, 904, true, UiLanguage.SIMPLIFIED_CHINESE, "APS_STARTUP_SCREENSHOT")

    @Test
    fun `capture minimum desktop workspace`() = capture(1280, 720, true, UiLanguage.SIMPLIFIED_CHINESE, "APS_STARTUP_MIN_SCREENSHOT")

    @Test
    fun `capture light English workspace`() = capture(1336, 904, false, UiLanguage.ENGLISH, "APS_STARTUP_LIGHT_SCREENSHOT")

    private fun capture(
        width: Int,
        height: Int,
        dark: Boolean,
        language: UiLanguage,
        outputKey: String,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            ViewerTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().testTag("startup-capture")) {
                    StartupProfilerScreen(startupVisualState(), StartupProfilerActions(), language)
                }
            }
        }
        waitForIdle()
        val screenshot = onNodeWithTag("startup-capture").captureToImage()
        assertEquals(width, screenshot.width)
        assertEquals(height, screenshot.height)
        System.getenv(outputKey)?.let { output ->
            val pixels = screenshot.toPixelMap()
            val bitmap = BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until screenshot.height) {
                for (x in 0 until screenshot.width) {
                    val color = pixels[x, y]
                    bitmap.setRGB(
                        x,
                        y,
                        ((color.alpha * 255).toInt() shl 24) or ((color.red * 255).toInt() shl 16) or
                            ((color.green * 255).toInt() shl 8) or (color.blue * 255).toInt(),
                    )
                }
            }
            ImageIO.write(bitmap, "png", File(output))
        }
    }
}
