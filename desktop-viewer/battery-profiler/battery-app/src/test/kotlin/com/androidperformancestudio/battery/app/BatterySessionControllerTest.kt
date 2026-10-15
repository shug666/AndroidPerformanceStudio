package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.UidBatteryStats
import com.androidperformancestudio.battery.storage.SqliteBatterySessionStore
import com.androidperformancestudio.battery.storage.StoredBatterySession
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatterySessionControllerTest {
    @Test
    fun `opening saved evidence restores analysis without retargeting adb and new clears only the view`() =
        runBlocking {
            val file = createTempDirectory("battery-session-controller").resolve("battery.db")
            seedSession(file)
            val controller = BatteryProfilerController(databaseFile = file)
            controller.refreshSessions()
            assertEquals(
                "pkg.test",
                controller.state.value.sessions
                    .single()
                    .packageName,
            )

            controller.openSession("saved")
            val state = controller.state.value
            assertEquals("saved", state.viewedStoredSessionId)
            assertEquals(17.0, state.analysis?.networkBytes?.median)
            assertEquals(2, state.storedRuns.flatMap { listOf(it.baseline, it.finalSnapshot) }.size)
            assertNull(state.selectedDeviceSerial)
            assertNull(state.selectedPackageName)
            assertNull(state.experiment, "Stored metadata must not invent capture configuration")
            assertEquals(AttributionScope.SHARED_UID, state.viewedAttributionScope)
            val csv = file.parent.resolve("restored.csv")
            controller.exportCsv(csv)
            assertTrue(
                java.nio.file.Files
                    .readString(csv)
                    .contains(",SHARED_UID,"),
            )
            assertNull(state.artifact, "Do not retain a prior artifact's provenance")

            controller.openSession("missing")
            assertNotNull(controller.state.value.sessionError)
            assertEquals("saved", controller.state.value.viewedStoredSessionId)
            assertEquals(
                17.0,
                controller.state.value.analysis
                    ?.networkBytes
                    ?.median,
            )

            controller.newExperiment()
            assertNull(controller.state.value.sessionError)
            assertNull(controller.state.value.analysis)
            assertEquals(emptyList(), controller.state.value.storedRuns)
            assertEquals(1, controller.state.value.sessions.size)
            SqliteBatterySessionStore.open(file).use { assertNotNull(it.loadEvidence("saved")) }
        }

    @Test
    fun `session search uses only local saved metadata case insensitively`() {
        val one =
            StoredBatterySession(
                "id-1",
                "pkg.Example",
                1,
                "2026-10-15T00:00:00Z",
                1,
                BatterySessionStatus.COMPLETED,
            )
        val two = one.copy(id = "id-2", packageName = "other", createdAt = "2026-10-14T00:00:00Z")
        assertEquals(listOf(one), filterBatterySessions(listOf(one, two), " example "))
        assertEquals(listOf(two), filterBatterySessions(listOf(one, two), "ID-2"))
        assertEquals(listOf(one), filterBatterySessions(listOf(one, two), "10-15"))
        assertEquals(listOf(one, two), filterBatterySessions(listOf(one, two), ""))
    }

    @Test
    fun `empty saved session is viewable and missing evidence does not crash`() =
        runBlocking {
            val file = createTempDirectory("battery-empty-controller").resolve("battery.db")
            seedSession(file, empty = true)
            val controller = BatteryProfilerController(databaseFile = file)
            controller.openSession("saved")
            assertEquals(
                emptyList(),
                controller.state.value.analysis
                    ?.runs,
            )
            assertEquals(false, controller.state.value.isLoadingSession)
            assertNull(controller.state.value.sessionError)
        }

    private fun seedSession(
        file: Path,
        empty: Boolean = false,
    ) {
        val session =
            BatterySession(
                "saved",
                "serial",
                "pkg.test",
                10001,
                AttributionScope.SHARED_UID,
                BatteryExperimentConfig(),
                BatteryCapabilities(BatteryCapabilityLevel.RESOURCE_BASIC, true, false, false, false, false),
                BatteryEnvironment(BatteryDeviceState()),
                Instant.EPOCH,
            )
        val baseline =
            BatterySnapshot(
                "before",
                "saved",
                0,
                Instant.EPOCH,
                null,
                null,
                UidBatteryStats(10001),
                BatteryDeviceState(levelPercent = 80),
                rawEvidence = BatteryRawEvidence("", "", "level: 80"),
            )
        val run =
            BatteryRun(
                "run",
                "saved",
                1,
                baseline,
                emptyList(),
                baseline.copy(
                    id = "after",
                    sequence = 1,
                    capturedAt = Instant.EPOCH.plusSeconds(10),
                    uidStats =
                        UidBatteryStats(
                            10001,
                            wakelocks =
                                mapOf(
                                    "lock" to
                                        com.androidperformancestudio.battery.model
                                            .ResourceTimer("lock", 5, 1),
                                ),
                            network = NetworkUsage(wifiRxBytes = 17),
                        ),
                ),
            )
        SqliteBatterySessionStore.open(file).use {
            if (empty) it.begin(session) else it.save(session, listOf(run), listOf(BatteryAnalyzer().diff(run)))
        }
    }
}
