package com.androidperformancestudio.battery.presentation

import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryWorkspaceEvidenceTest {
    @Test
    fun `snapshot chart keeps real ordered samples without duplicating final sample`() {
        val run = batteryVisualRun()
        val samples = batterySnapshotSamples(run.copy(samples = run.samples.reversed() + run.finalSnapshot))

        assertEquals(8, samples.size)
        assertEquals(samples.sortedBy { it.capturedAt }, samples)
        assertEquals(run.baseline.deviceState, samples.first().deviceState)
        assertEquals(run.finalSnapshot.deviceState, samples.last().deviceState)
        assertTrue(batterySnapshotSamples(null).isEmpty())
    }

    @Test
    fun `all history kinds and untimed raw evidence retain their original identity`() {
        val events =
            BatteryHistoryEventKind.entries.mapIndexed { index, kind ->
                BatteryHistoryEvent(
                    if (index ==
                        0
                    ) {
                        null
                    } else {
                        index.toLong()
                    },
                    kind,
                    null,
                    null,
                    null,
                    "raw-$index",
                    EvidenceConfidence.INFERRED,
                )
            }
        val tracks = batteryHistoryTracks(events)
        assertEquals(BatteryHistoryEventKind.entries.toSet(), tracks.keys)
        assertEquals(
            events,
            tracks.values
                .flatten()
                .sortedBy { it.index }
                .map { it.value },
        )
        val rows = batteryWorkspaceItems(batteryVisualDelta().copy(history = events), BatteryResourceTab.HISTORY)
        assertEquals(events.size, rows.size)
        assertEquals(events.first(), (rows.first() as BatteryWorkspaceItem.History).event)
    }

    @Test
    fun `resource tabs do not silently truncate and search includes raw evidence`() {
        val timers = (0..120).map { ResourceTimer("lock-$it", it.toLong(), 1) }
        val rows = batteryWorkspaceItems(batteryVisualDelta().copy(wakelocks = timers), BatteryResourceTab.WAKELOCKS)
        assertEquals(121, rows.size)
        assertTrue(rows.last().matches("LOCK-120", ""))
        assertFalse(rows.last().matches("missing", ""))
        val event = BatteryWorkspaceItem.History("history", batteryVisualDelta().history.first())
        assertTrue(event.matches("raw-evidence", ""))
        assertTrue(event.matches("", ""))
    }

    @Test
    fun `network table uses exact supplied aggregates and preserves unavailable packet fields`() {
        val usage = NetworkUsage(mobileRxBytes = 11, wifiTxBytes = 25, bluetoothRxBytes = 7, mobileRadioActiveMs = 900)
        val rows =
            batteryWorkspaceItems(batteryVisualDelta().copy(network = usage), BatteryResourceTab.NETWORK)
                .map { it as BatteryWorkspaceItem.Counter }
        assertEquals(7, rows.size)
        assertEquals(11, rows.single { it.counter == BatteryNetworkCounter.MOBILE_RX }.value)
        assertEquals(900, rows.single { it.counter == BatteryNetworkCounter.MOBILE_RADIO }.value)
        assertEquals(null, rows.single { it.counter == BatteryNetworkCounter.BLUETOOTH_RX }.packets)
    }

    @Test
    fun `absent energy and warnings remain absent instead of becoming inferred evidence`() {
        val run = batteryVisualDelta().copy(energy = emptyList(), warnings = emptyList())
        assertTrue(batteryWorkspaceItems(run, BatteryResourceTab.ENERGY).isEmpty())
        assertTrue(batteryWorkspaceItems(run, BatteryResourceTab.WARNINGS).isEmpty())
    }

    @Test
    fun `aggregate-only imports never acquire invented wifi counters`() {
        val run =
            batteryVisualDelta().copy(
                networkCountersAvailable = false,
                networkTotalBytes = 5_000,
                network = NetworkUsage(wifiRxBytes = 5_000),
            )
        val rows =
            batteryWorkspaceItems(run, BatteryResourceTab.NETWORK)
                .map { it as BatteryWorkspaceItem.Counter }
        assertEquals(1, rows.size)
        assertEquals(BatteryNetworkCounter.TOTAL_BYTES, rows.single().counter)
        assertEquals(5_000, rows.single().value)
        assertEquals(null, rows.single().packets)
    }
}
