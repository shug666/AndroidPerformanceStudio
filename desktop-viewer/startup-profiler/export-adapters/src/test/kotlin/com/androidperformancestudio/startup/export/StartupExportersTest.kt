@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.startup.export

import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.analysis.StartupComparisonStatus
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupEnvironmentEvidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPerfettoPhaseAttribution
import com.androidperformancestudio.startup.model.StartupPerfettoRootCauseEvidence
import com.androidperformancestudio.startup.model.StartupPerfettoSlice
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupRunContext
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupTraceEvidence
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StartupExportersTest {
    @Test
    fun `exports versioned json and flat csv`() {
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    StartupRun(
                        "run",
                        "session",
                        1,
                        StartupType.COLD,
                        StartupType.COLD,
                        PlatformLaunchMetrics(totalTimeMs = 123),
                        rawEvidence = StartupRawEvidence("raw"),
                    ),
                ),
            )
        val directory = Files.createTempDirectory("startup-export")
        val json = directory.resolve("report.json")
        val csv = directory.resolve("report.csv")

        StartupJsonExporter().export(analysis, json)
        StartupCsvExporter().export(analysis, csv)

        assertContains(Files.readString(json), "\"schemaVersion\": 1")
        assertContains(Files.readString(csv), "123")
    }

    @Test
    fun `imports an exported startup json report`() {
        val original = startupAnalysisFixture()
        val report = Files.createTempFile("startup-import", ".json")
        StartupJsonExporter().export(original, report)
        val exported = Files.readString(report)

        assertContains(exported, "\"deviceLocalId\"")
        assertFalse(exported.contains("fixture-device-serial"))
        assertFalse(exported.contains("\"deviceSerial\""))

        val imported = StartupJsonImporter().import(report)

        assertEquals(original.totalTime, imported.totalTime)
        assertEquals(original.firstFrame, imported.firstFrame)
        assertEquals(original.fullyDrawn, imported.fullyDrawn)
        assertEquals(original.warnings, imported.warnings)
        assertEquals(1, imported.runs.size)
        with(imported.runs.single()) {
            assertEquals("run-7", id)
            assertEquals(7, iteration)
            assertEquals(StartupType.COLD, requestedType)
            assertEquals(StartupType.WARM, observedType)
            assertEquals(123, platform.totalTimeMs)
            assertEquals(2, milestones.size)
            assertEquals("Warm launch observed", warnings.single())
            assertEquals("Status: ok", rawEvidence.amStartOutput)
            assertEquals("event", rawEvidence.eventLogOutput)
            assertEquals("compile", rawEvidence.compilationOutput)
            assertEquals(true, rawEvidence.agentAvailable)
            assertEquals(
                DeviceIdentityPseudonymizer().localId("fixture-device-serial").value,
                context?.deviceSerial,
            )
            assertEquals("dev.sample", context?.packageName)
            assertEquals("dev.sample/.MainActivity", context?.componentName)
            assertEquals(StartupProfileSource.BASELINE_PROFILE_PLUGIN, compilationEvidence?.profileSource)
            assertEquals("baseline-prof.txt#abc", compilationEvidence?.baselineProfileArtifact)
            assertEquals("Pixel", environmentEvidence?.deviceModel)
            assertEquals(true, traceEvidence?.captured)
            val rootCause = requireNotNull(traceEvidence?.rootCause)
            assertEquals("sched", rootCause.schedulingSlices.single().name)
            assertEquals("concurrent copying GC", rootCause.gcSlices.single().name)
            assertEquals(30, rootCause.phaseAttributions.single().schedulingNs)
            assertEquals(10, rootCause.phaseAttributions.single().gcNs)
        }
    }

    @Test
    fun `rejects unsupported startup json schema`() {
        val report = Files.createTempFile("startup-import-unsupported", ".json")
        Files.writeString(report, """{"schemaVersion":2,"summary":{},"warnings":[],"runs":[]}""")

        val error =
            assertFailsWith<IllegalArgumentException> {
                StartupJsonImporter().import(report)
            }

        assertContains(error.message.orEmpty(), "Unsupported Startup Profiler schema version")
    }

    @Test
    fun `imports legacy schema one report without appended evidence`() {
        val report = Files.createTempFile("startup-import-v1", ".json")
        Files.writeString(
            report,
            """{"schemaVersion":1,"warnings":[],"runs":[{"iteration":1,"runId":"old","requestedType":"COLD","observedType":"COLD","displayedTimeMs":120,"rawEvidence":{"amStartOutput":"raw"}}]}""",
        )

        val run = StartupJsonImporter().import(report).runs.single()

        assertEquals(StartupSource.EVENT_LOG, run.ttidEvidence.source)
        assertEquals(null, run.context)
        assertEquals(null, run.compilationEvidence)
    }

    @Test
    fun `imports legacy raw device identity as a local pseudonym and preserves it on reexport`() {
        val report = Files.createTempFile("startup-import-legacy-identity", ".json")
        Files.writeString(
            report,
            """{"schemaVersion":1,"runs":[{"iteration":1,"runId":"legacy","requestedType":"COLD","observedType":"COLD","rawEvidence":{"amStartOutput":"raw"},"context":{"deviceSerial":"legacy-device-serial","packageName":"dev.sample","componentName":"dev.sample/.MainActivity"}}]}""",
        )

        val imported = StartupJsonImporter().import(report)
        val expectedId = DeviceIdentityPseudonymizer().localId("legacy-device-serial").value
        assertEquals(
            expectedId,
            imported.runs
                .single()
                .context
                ?.deviceSerial,
        )

        StartupJsonExporter().export(imported, report)

        val reexported = Files.readString(report)
        assertContains(reexported, expectedId)
        assertFalse(reexported.contains("legacy-device-serial"))
        assertFalse(reexported.contains("\"deviceSerial\""))
        assertEquals(
            expectedId,
            StartupJsonImporter()
                .import(report)
                .runs
                .single()
                .context
                ?.deviceSerial,
        )
    }

    @Test
    fun `current device local identity takes precedence over a legacy raw serial`() {
        val localId = DeviceIdentityPseudonymizer().localId("current-device").value
        val report = Files.createTempFile("startup-import-current-identity", ".json")
        Files.writeString(
            report,
            """{"schemaVersion":1,"runs":[{"iteration":1,"runId":"current","requestedType":"COLD","observedType":"COLD","rawEvidence":{"amStartOutput":"raw"},"context":{"deviceLocalId":"$localId","deviceSerial":"legacy-device-serial","packageName":"dev.sample","componentName":"dev.sample/.MainActivity"}}]}""",
        )

        assertEquals(
            localId,
            StartupJsonImporter()
                .import(report)
                .runs
                .single()
                .context
                ?.deviceSerial,
        )
    }

    @Test
    fun `rejects malformed current device identity instead of falling back to a raw serial`() {
        val report = Files.createTempFile("startup-import-invalid-identity", ".json")
        Files.writeString(
            report,
            """{"schemaVersion":1,"runs":[{"iteration":1,"runId":"invalid","requestedType":"COLD","observedType":"COLD","rawEvidence":{"amStartOutput":"raw"},"context":{"deviceLocalId":"not-a-pseudonym","deviceSerial":"legacy-device-serial","packageName":"dev.sample","componentName":"dev.sample/.MainActivity"}}]}""",
        )

        assertFailsWith<IllegalArgumentException> { StartupJsonImporter().import(report) }
    }

    @Test
    fun `rejects a context whose device identity is missing`() {
        val report = Files.createTempFile("startup-import-missing-identity", ".json")
        Files.writeString(
            report,
            """{"schemaVersion":1,"runs":[{"iteration":1,"runId":"missing","requestedType":"COLD","observedType":"COLD","rawEvidence":{"amStartOutput":"raw"},"context":{"packageName":"dev.sample","componentName":"dev.sample/.MainActivity"}}]}""",
        )

        val error = assertFailsWith<IllegalArgumentException> { StartupJsonImporter().import(report) }
        assertContains(error.message.orEmpty(), "Startup context device identity is missing")
    }

    @Test
    fun `live startup compares with its own exported and imported cohort`() {
        val analyzer = StartupAnalyzer()
        val fixture = startupAnalysisFixture().runs.single()
        val live =
            analyzer.analyze(
                List(3) { index ->
                    fixture.copy(
                        id = "comparable-$index",
                        iteration = index + 1,
                        observedType = StartupType.COLD,
                        platform = PlatformLaunchMetrics(totalTimeMs = 100L + index, displayedTimeMs = 100L + index),
                        ttidEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
                        environmentEvidence = StartupEnvironmentEvidence("Pixel", 35, false, 80, true, 0, Instant.EPOCH),
                    )
                },
            )
        val report = Files.createTempFile("startup-live-import-comparison", ".json")
        StartupJsonExporter().export(live, report)

        val imported = StartupJsonImporter().import(report)

        assertEquals(StartupComparisonStatus.NO_CHANGE, analyzer.compare(live, imported).status)
        assertEquals(StartupComparisonStatus.NO_CHANGE, analyzer.compare(imported, live).status)
        assertEquals(
            "fixture-device-serial",
            live.runs
                .first()
                .context
                ?.deviceSerial,
        )
    }
}

private fun startupAnalysisFixture() =
    StartupAnalyzer().analyze(
        listOf(
            StartupRun(
                id = "run-7",
                sessionId = "session",
                iteration = 7,
                requestedType = StartupType.COLD,
                observedType = StartupType.WARM,
                platform =
                    PlatformLaunchMetrics(
                        thisTimeMs = 101,
                        totalTimeMs = 123,
                        waitTimeMs = 130,
                        displayedTimeMs = 115,
                        fullyDrawnTimeMs = 220,
                    ),
                milestones =
                    listOf(
                        StartupMilestone(
                            kind = StartupMilestoneKind.PROCESS_START,
                            elapsedRealtimeNs = 1_000_000,
                            source = StartupSource.EVENT_LOG,
                            confidence = EvidenceConfidence.EXACT,
                            activityName = "MainActivity",
                        ),
                        StartupMilestone(
                            kind = StartupMilestoneKind.FIRST_FRAME,
                            elapsedRealtimeNs = 116_000_000,
                            durationMs = 115,
                            source = StartupSource.AM_START,
                            confidence = EvidenceConfidence.ESTIMATED,
                            activityName = "MainActivity",
                        ),
                    ),
                warnings = listOf("Warm launch observed"),
                context = StartupRunContext("fixture-device-serial", "dev.sample", "dev.sample/.MainActivity"),
                rawEvidence =
                    StartupRawEvidence(
                        amStartOutput = "Status: ok",
                        eventLogOutput = "event",
                        compilationOutput = "compile",
                        agentAvailable = true,
                    ),
                compilationEvidence =
                    StartupCompilationEvidence(
                        CompilationMode.SPEED_PROFILE,
                        compilerFilterAfter = "speed-profile",
                        verified = true,
                        profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
                        profileSourceDeclared = true,
                        baselineProfileArtifact = "baseline-prof.txt#abc",
                    ),
                environmentEvidence = StartupEnvironmentEvidence(deviceModel = "Pixel"),
                traceEvidence =
                    StartupTraceEvidence(
                        file = "run.perfetto-trace",
                        captured = true,
                        rootCause =
                            StartupPerfettoRootCauseEvidence(
                                schedulingSlices = listOf(StartupPerfettoSlice(100, 30, "sched", "main")),
                                gcSlices = listOf(StartupPerfettoSlice(130, 10, "concurrent copying GC", "HeapTaskDaemon")),
                                phaseAttributions =
                                    listOf(
                                        StartupPerfettoPhaseAttribution(
                                            phaseName = "PROCESS_START → FIRST_FRAME",
                                            startNs = 100,
                                            endNs = 200,
                                            schedulingNs = 30,
                                            gcNs = 10,
                                        ),
                                    ),
                                correlated = true,
                                correlationErrorBoundNs = 1_000,
                            ),
                    ),
            ),
        ),
    )
