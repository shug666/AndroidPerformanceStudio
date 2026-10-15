package com.androidperformancestudio.battery.presentation

import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.UidBatteryStats
import com.androidperformancestudio.battery.storage.StoredBatterySession
import java.time.Instant

/** Deterministic evidence only for presentation tests, never injected in production. */
@Suppress("LongMethod")
internal fun batteryVisualRun(): BatteryRun {
    val history =
        BatteryHistoryEventKind.entries.mapIndexed { index, kind ->
            BatteryHistoryEvent(
                elapsedMs = 2_000_000L + index * 15_000L,
                kind = kind,
                active = index % 2 == 0,
                name = "com.example.sync/${kind.name.lowercase()}",
                uid = 10234,
                raw = "raw-evidence +${kind.name.lowercase()} uid=10234",
                confidence =
                    if (kind == BatteryHistoryEventKind.UNKNOWN) {
                        EvidenceConfidence.INFERRED
                    } else {
                        EvidenceConfidence.EXACT
                    },
            )
        }
    val start = Instant.parse("2026-10-15T10:00:00Z")
    val snapshots =
        (0..7).map { index ->
            BatterySnapshot(
                id = "snapshot-$index",
                sessionId = "visual-session",
                sequence = index,
                capturedAt = start.plusSeconds(index * 60L),
                statsPeriodId = "period",
                bootId = "boot",
                uidStats =
                    UidBatteryStats(
                        uid = 10234,
                        wakelocks =
                            mapOf(
                                "sync" to ResourceTimer("*job*/com.example.sync", index * 9_000L, index * 3L),
                                "location" to
                                    ResourceTimer(
                                        "NetworkLocationLocator",
                                        index * 4_000L,
                                        index * 2L,
                                        EvidenceConfidence.INFERRED,
                                    ),
                                "audio" to ResourceTimer("AudioMix", index * 2_000L, index.toLong()),
                            ),
                        alarms = mapOf("refresh" to ResourceTimer("com.example.REFRESH", 0, index * 4L)),
                        jobs =
                            mapOf("upload" to ResourceTimer("com.example.UploadJob", index * 3_500L, index.toLong())),
                        sensors =
                            mapOf("accelerometer" to ResourceTimer("Accelerometer", index * 2_000L, index.toLong())),
                        network = NetworkUsage(wifiRxBytes = index * 1_200_000L, wifiTxBytes = index * 280_000L),
                    ),
                deviceState =
                    BatteryDeviceState(
                        levelPercent = 89 - index * 2,
                        powered = index == 0,
                        temperatureTenthsCelsius = 310 + index,
                        voltageMillivolts = 4000,
                    ),
                history = history,
                warnings =
                    if (index == 7) {
                        listOf("Imported evidence: no request-level clock mapping.")
                    } else {
                        emptyList()
                    },
                rawEvidence = BatteryRawEvidence("checkin", "report", "battery"),
            )
        }
    return BatteryRun(
        "visual-run",
        "visual-session",
        1,
        snapshots.first(),
        snapshots.drop(1).dropLast(1),
        snapshots.last(),
    )
}

internal fun batteryVisualDelta(): BatteryRunDelta = BatteryAnalyzer().diff(batteryVisualRun())

internal fun batteryVisualState(): BatteryProfilerState {
    val run = batteryVisualRun()
    return BatteryProfilerState(
        sessions =
            (0..4).map { index ->
                StoredBatterySession(
                    id = if (index == 0) "visual-session" else "stored-$index",
                    packageName = if (index == 0) "com.example.sync" else "com.example.experiment$index",
                    uid = 10234,
                    createdAt = "2026-10-${15 - index} 10:00:00",
                    runCount = 1,
                    status = BatterySessionStatus.COMPLETED,
                )
            },
        viewedStoredSessionId = "visual-session",
        analysis = BatteryAnalyzer().analyze(listOf(run)),
        storedRuns = listOf(run),
        selectedRunId = run.id,
    )
}
