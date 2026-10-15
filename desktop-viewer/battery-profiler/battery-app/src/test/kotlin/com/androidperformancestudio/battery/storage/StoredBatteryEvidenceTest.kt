@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.battery.storage

import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.BatteryPerformanceWindow
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.BatteryThermalEvidence
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.EnergyEvidenceKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.parser.BatteryStatsParser
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredBatteryEvidenceTest {
    @Test
    fun `roundtrips saved deltas and restores raw snapshot evidence without inventing capture configuration`() {
        val file = database()
        val run = run("run", 1)
        val delta = delta("run", 1)
        SqliteBatterySessionStore.open(file).use { it.save(session(), listOf(run), listOf(delta)) }

        SqliteBatterySessionStore.open(file).use { store ->
            val loaded = assertNotNull(store.loadEvidence("session"))
            assertEquals("pkg", loaded.session.packageName)
            assertEquals(BatterySessionStatus.COMPLETED, loaded.session.status)
            assertEquals(delta, loaded.analysis.runs.single())
            assertEquals(delta.network.totalBytes.toDouble(), loaded.analysis.networkBytes.median)
            assertEquals(1.25, loaded.analysis.energyMah.median)
            assertEquals(42.5, loaded.analysis.peakTemperatureCelsius.median)
            assertEquals(run, loaded.runs.single())
            assertNull(store.loadEvidence("missing"))
        }
    }

    @Test
    fun `restores interrupted and empty sessions and orders runs deterministically`() {
        val file = database()
        SqliteBatterySessionStore.open(file).use { store ->
            store.begin(session())
            val empty = assertNotNull(store.loadEvidence("session"))
            assertTrue(empty.runs.isEmpty())
            assertTrue(empty.analysis.runs.isEmpty())
            assertNull(empty.analysis.networkBytes.median)
            listOf("run-b" to 2, "run-z" to 1, "run-a" to 1).forEach { (id, iteration) ->
                store.saveRun(session(), run(id, iteration), delta(id, iteration))
            }
            assertEquals(1, store.interruptRunningSessions())
            val loaded = assertNotNull(store.loadEvidence("session"))
            assertEquals(BatterySessionStatus.INTERRUPTED, loaded.session.status)
            assertEquals(listOf("run-a", "run-z", "run-b"), loaded.runs.map { it.id })
            assertEquals(loaded.runs.map { it.id }, loaded.analysis.runs.map { it.runId })
        }
    }

    @Test
    fun `legacy total network bytes are not assigned to a fabricated transport`() {
        val file = database()
        SqliteBatterySessionStore.open(file).use { store -> store.save(session(), listOf(run("run", 1)), listOf(delta("run", 1))) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE battery_delta_network")
                statement.execute("DROP TABLE battery_delta_history")
                statement.execute("UPDATE battery_snapshots SET snapshot_index=NULL")
                statement.execute("UPDATE battery_deltas SET history_persisted=0")
            }
        }
        SqliteBatterySessionStore.open(file).use { store ->
            val loaded = assertNotNull(store.loadEvidence("session"))
            assertEquals(delta("run", 1).network.totalBytes.toDouble(), loaded.analysis.networkBytes.median)
            assertEquals(
                NetworkUsage(),
                loaded.analysis.runs
                    .single()
                    .network,
            )
            assertTrue(loaded.analysis.warnings.any { "Transport counters are unavailable" in it })
            assertEquals(
                run("run", 1).finalSnapshot.history,
                loaded.analysis.runs
                    .single()
                    .history,
            )
            assertEquals(
                listOf("run-baseline", "run-sample", "run-final"),
                loaded.runs.single().let {
                    listOf(it.baseline.id, it.samples.single().id, it.finalSnapshot.id)
                },
            )
        }
    }

    @Test
    fun `missing snapshots retain delta evidence with explicit restoration warning`() {
        val file = database()
        SqliteBatterySessionStore.open(file).use { it.save(session(), listOf(run("run", 1)), listOf(delta("run", 1))) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().execute("DELETE FROM battery_snapshots WHERE id != 'run-baseline'")
        }
        SqliteBatterySessionStore.open(file).use { store ->
            val loaded = assertNotNull(store.loadEvidence("session"))
            assertTrue(loaded.runs.isEmpty())
            assertEquals(delta("run", 1), loaded.analysis.runs.single())
            assertTrue(loaded.analysis.warnings.any { "missing baseline or final snapshot" in it })
        }
    }

    @Test
    fun `replacing a saved run does not duplicate network history resources or windows`() {
        SqliteBatterySessionStore.open(database()).use { store ->
            repeat(2) { store.saveRun(session(), run("run", 1), delta("run", 1)) }
            assertEquals(delta("run", 1), assertNotNull(store.loadEvidence("session")).analysis.runs.single())
        }
    }

    @Test
    fun `unavailable network components retain total and independently persisted history`() {
        val expected = delta("run", 1).copy(network = NetworkUsage(), networkTotalBytes = 789, networkCountersAvailable = false)
        SqliteBatterySessionStore.open(database()).use { store ->
            store.save(session(), listOf(run("run", 1)), listOf(expected))
            val loaded = assertNotNull(store.loadEvidence("session"))
            val restored = loaded.analysis.runs.single()
            assertEquals(789L, restored.networkTotalBytes)
            assertEquals(false, restored.networkCountersAvailable)
            assertEquals(expected.history, restored.history)
            assertEquals(789.0, loaded.analysis.networkBytes.median)
        }
    }

    @Test
    fun `explicit snapshot ordering preserves roles when times and sequences tie`() {
        val original = run("run", 1)
        val tied =
            original.copy(
                baseline = original.baseline.copy(sequence = 0, capturedAt = Instant.EPOCH),
                samples = original.samples.map { it.copy(sequence = 0, capturedAt = Instant.EPOCH) },
                finalSnapshot = original.finalSnapshot.copy(sequence = 0, capturedAt = Instant.EPOCH),
            )
        SqliteBatterySessionStore.open(database()).use { store ->
            store.save(session(), listOf(tied), listOf(delta("run", 1)))
            assertEquals(tied, assertNotNull(store.loadEvidence("session")).runs.single())
        }
    }

    private fun database(): Path = Files.createTempDirectory("battery-evidence-test").resolve("battery.db")

    private fun session(): BatterySession =
        BatterySession(
            "session",
            "serial-never-restored",
            "pkg",
            10123,
            AttributionScope.UID,
            BatteryExperimentConfig(),
            BatteryCapabilities(BatteryCapabilityLevel.RESOURCE_BASIC, true, true, false, false, true),
            BatteryEnvironment(BatteryDeviceState()),
            Instant.EPOCH,
        )

    private fun run(
        id: String,
        iteration: Int,
    ): BatteryRun =
        BatteryRun(id, "session", iteration, snapshot("$id-baseline", 0), listOf(snapshot("$id-sample", 1)), snapshot("$id-final", 2))

    private fun snapshot(
        id: String,
        sequence: Int,
    ): BatterySnapshot {
        val raw =
            BatteryRawEvidence(
                "9,10123,l,wl,lock,5000000,2,0,0,0\n9,10123,l,nt,1,2,3,4,5,6,7,8,9,10,11000",
                "Uid u0a123: 1.25 ( cpu=0.50 wifi=0.75 )",
                "level: 87\ntemperature: 321\nvoltage: 4200\nUSB powered: true\nstatus: 2",
                "9,0,l,h,1234,+wake_lock=lock,uid=10123",
                thermal = "mStatus=3",
            )
        val parser = BatteryStatsParser()
        val parsed = parser.parse(raw.checkin, raw.report, raw.battery, 10123)
        return BatterySnapshot(
            id,
            "session",
            sequence,
            Instant.EPOCH.plusSeconds(sequence.toLong()),
            "original-period",
            "original-boot",
            parsed.uidStats,
            parser.parseDeviceState(raw.battery),
            parser.parseHistory(requireNotNull(raw.history)),
            listOf("original warning"),
            raw,
            mapOf("screen" to "on", "key" to "a=b"),
            BatteryThermalEvidence(3, true, mapOf("CPU" to 42.5), raw.thermal),
        )
    }

    private fun delta(
        id: String,
        iteration: Int,
    ): BatteryRunDelta =
        BatteryRunDelta(
            id,
            "session",
            iteration,
            2000,
            listOf(ResourceTimer("lock", 1000, 2)),
            listOf(ResourceTimer("alarm", 0, 4, EvidenceConfidence.INFERRED)),
            listOf(ResourceTimer("job", 700, 3)),
            listOf(ResourceTimer("sensor", 500, 1)),
            NetworkUsage(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            listOf(
                EnergyEstimate("cpu", 1.25, null, EnergyEvidenceKind.SYSTEM_MODEL, AttributionScope.UID, EvidenceConfidence.MODELED),
                EnergyEstimate("rail", null, 999, EnergyEvidenceKind.HARDWARE_COUNTER, AttributionScope.DEVICE, EvidenceConfidence.EXACT),
            ),
            listOf(
                BatteryHistoryEvent(
                    1234,
                    BatteryHistoryEventKind.WAKELOCK,
                    true,
                    "lock",
                    10123,
                    "original event",
                    EvidenceConfidence.EXACT,
                ),
                BatteryHistoryEvent(null, BatteryHistoryEventKind.UNKNOWN, null, null, null, "unknown", EvidenceConfidence.INFERRED),
            ),
            listOf("counter warning"),
            42.5,
            3,
            1,
            listOf(BatteryPerformanceWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(2), 2000, 42.5, 3, true)),
        )
}
