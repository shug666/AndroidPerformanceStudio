package com.androidperformancestudio.battery.presentation

import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.ResourceTimer

internal enum class BatteryResourceTab { WAKELOCKS, ALARMS, JOBS, SENSORS, ENERGY, NETWORK, HISTORY, WARNINGS }

internal enum class BatteryNetworkCounter {
    MOBILE_RX,
    MOBILE_TX,
    WIFI_RX,
    WIFI_TX,
    BLUETOOTH_RX,
    BLUETOOTH_TX,
    MOBILE_RADIO,
    TOTAL_BYTES,
}

/** Each row retains its domain evidence; counter aggregates are never presented as request events. */
internal sealed interface BatteryWorkspaceItem {
    val id: String

    data class Timer(
        override val id: String,
        val timer: ResourceTimer,
    ) : BatteryWorkspaceItem

    data class Energy(
        override val id: String,
        val energy: EnergyEstimate,
    ) : BatteryWorkspaceItem

    data class History(
        override val id: String,
        val event: BatteryHistoryEvent,
    ) : BatteryWorkspaceItem

    data class Counter(
        override val id: String,
        val counter: BatteryNetworkCounter,
        val value: Long,
        val packets: Long? = null,
    ) : BatteryWorkspaceItem

    data class Warning(
        override val id: String,
        val message: String,
    ) : BatteryWorkspaceItem
}

internal fun batterySnapshotSamples(run: BatteryRun?): List<BatterySnapshot> =
    run
        ?.let {
            (listOf(it.baseline) + it.samples + it.finalSnapshot)
                .distinctBy(BatterySnapshot::id)
                .sortedBy(BatterySnapshot::capturedAt)
        }.orEmpty()

internal fun batteryHistoryTracks(
    history: List<BatteryHistoryEvent>,
): Map<BatteryHistoryEventKind, List<IndexedValue<BatteryHistoryEvent>>> =
    history.withIndex().groupBy { it.value.kind }.toSortedMap(compareBy { it.ordinal })

internal fun batteryWorkspaceItems(
    run: BatteryRunDelta,
    tab: BatteryResourceTab,
): List<BatteryWorkspaceItem> =
    when (tab) {
        BatteryResourceTab.WAKELOCKS ->
            run.wakelocks.mapIndexed { index, timer ->
                BatteryWorkspaceItem.Timer("wakelock-$index", timer)
            }
        BatteryResourceTab.ALARMS ->
            run.alarms.mapIndexed { index, timer ->
                BatteryWorkspaceItem.Timer("alarm-$index", timer)
            }
        BatteryResourceTab.JOBS ->
            run.jobs.mapIndexed { index, timer ->
                BatteryWorkspaceItem.Timer("job-$index", timer)
            }
        BatteryResourceTab.SENSORS ->
            run.sensors.mapIndexed { index, timer ->
                BatteryWorkspaceItem.Timer("sensor-$index", timer)
            }
        BatteryResourceTab.ENERGY ->
            run.energy.mapIndexed { index, energy ->
                BatteryWorkspaceItem.Energy("energy-$index", energy)
            }
        BatteryResourceTab.HISTORY ->
            run.history.mapIndexed { index, event ->
                BatteryWorkspaceItem.History("history-$index", event)
            }
        BatteryResourceTab.WARNINGS ->
            run.warnings.mapIndexed { index, warning ->
                BatteryWorkspaceItem.Warning("warning-$index", warning)
            }
        BatteryResourceTab.NETWORK -> batteryNetworkItems(run)
    }

private fun batteryNetworkItems(run: BatteryRunDelta): List<BatteryWorkspaceItem> =
    if (!run.networkCountersAvailable) {
        listOf(
            BatteryWorkspaceItem.Counter(
                "network-total",
                BatteryNetworkCounter.TOTAL_BYTES,
                run.networkTotalBytes,
            ),
        )
    } else {
        with(run.network) {
            listOf(
                BatteryWorkspaceItem.Counter(
                    "mobile-rx",
                    BatteryNetworkCounter.MOBILE_RX,
                    mobileRxBytes,
                    mobileRxPackets,
                ),
                BatteryWorkspaceItem.Counter(
                    "mobile-tx",
                    BatteryNetworkCounter.MOBILE_TX,
                    mobileTxBytes,
                    mobileTxPackets,
                ),
                BatteryWorkspaceItem.Counter(
                    "wifi-rx",
                    BatteryNetworkCounter.WIFI_RX,
                    wifiRxBytes,
                    wifiRxPackets,
                ),
                BatteryWorkspaceItem.Counter(
                    "wifi-tx",
                    BatteryNetworkCounter.WIFI_TX,
                    wifiTxBytes,
                    wifiTxPackets,
                ),
                BatteryWorkspaceItem.Counter(
                    "bluetooth-rx",
                    BatteryNetworkCounter.BLUETOOTH_RX,
                    bluetoothRxBytes,
                ),
                BatteryWorkspaceItem.Counter(
                    "bluetooth-tx",
                    BatteryNetworkCounter.BLUETOOTH_TX,
                    bluetoothTxBytes,
                ),
                BatteryWorkspaceItem.Counter(
                    "mobile-radio",
                    BatteryNetworkCounter.MOBILE_RADIO,
                    mobileRadioActiveMs,
                ),
            )
        }
    }

internal fun BatteryWorkspaceItem.matches(
    query: String,
    displayName: String,
): Boolean {
    val evidence =
        when (this) {
            is BatteryWorkspaceItem.Timer -> "${timer.name} ${timer.durationMs} ${timer.count} ${timer.confidence}"
            is BatteryWorkspaceItem.Energy ->
                "${energy.component} ${energy.source} ${energy.attributionScope} ${energy.confidence}"
            is BatteryWorkspaceItem.History ->
                "${event.name.orEmpty()} ${event.kind} ${event.uid} ${event.raw} ${event.confidence}"
            is BatteryWorkspaceItem.Counter -> "${counter.name} $value ${packets ?: ""}"
            is BatteryWorkspaceItem.Warning -> message
        }
    return query.isBlank() || "$displayName $evidence".contains(query.trim(), ignoreCase = true)
}
