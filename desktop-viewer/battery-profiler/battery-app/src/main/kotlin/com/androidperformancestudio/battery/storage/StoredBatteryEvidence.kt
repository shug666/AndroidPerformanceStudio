@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:max-line-length")

package com.androidperformancestudio.battery.storage

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.BatteryPerformanceWindow
import com.androidperformancestudio.battery.model.BatteryRawEvidence
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.BatteryThermalEvidence
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.EnergyEvidenceKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.parser.BatteryStatsParser
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/** Restorable evidence only: the database does not retain a complete capture configuration or device identity. */
public data class StoredBatteryEvidence(
    val session: StoredBatterySession,
    val analysis: BatteryAnalysisResult,
    val runs: List<BatteryRun>,
)

internal fun createEvidenceSchema(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            "CREATE TABLE IF NOT EXISTS battery_delta_network (run_id TEXT PRIMARY KEY REFERENCES battery_runs(id) ON DELETE CASCADE, " +
                "mobile_rx_bytes INTEGER NOT NULL, mobile_tx_bytes INTEGER NOT NULL, wifi_rx_bytes INTEGER NOT NULL, wifi_tx_bytes INTEGER NOT NULL, " +
                "bluetooth_rx_bytes INTEGER NOT NULL, bluetooth_tx_bytes INTEGER NOT NULL, mobile_rx_packets INTEGER NOT NULL, mobile_tx_packets INTEGER NOT NULL, " +
                "wifi_rx_packets INTEGER NOT NULL, wifi_tx_packets INTEGER NOT NULL, mobile_radio_active_ms INTEGER NOT NULL)",
        )
        statement.execute(
            "CREATE TABLE IF NOT EXISTS battery_delta_history (run_id TEXT NOT NULL REFERENCES battery_runs(id) ON DELETE CASCADE, event_index INTEGER NOT NULL, " +
                "elapsed_ms INTEGER, kind TEXT NOT NULL, active INTEGER, name TEXT, uid INTEGER, raw TEXT NOT NULL, confidence TEXT NOT NULL, PRIMARY KEY(run_id, event_index))",
        )
        val hasIndex =
            statement.executeQuery("PRAGMA table_info(battery_snapshots)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == "snapshot_index" }
            }
        if (!hasIndex) statement.execute("ALTER TABLE battery_snapshots ADD COLUMN snapshot_index INTEGER")
        val hasHistoryMarker =
            statement.executeQuery("PRAGMA table_info(battery_deltas)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == "history_persisted" }
            }
        if (!hasHistoryMarker) statement.execute("ALTER TABLE battery_deltas ADD COLUMN history_persisted INTEGER NOT NULL DEFAULT 0")
    }
}

internal fun saveNetworkAndHistory(
    connection: Connection,
    delta: BatteryRunDelta,
) {
    connection.prepareStatement("DELETE FROM battery_delta_network WHERE run_id=?").use { statement ->
        statement.setString(1, delta.runId)
        statement.executeUpdate()
    }
    if (delta.networkCountersAvailable) {
        val insertNetworkSql = "INSERT OR REPLACE INTO battery_delta_network VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        connection.prepareStatement(insertNetworkSql).use { statement ->
            statement.setString(1, delta.runId)
            val network = delta.network
            listOf(
                network.mobileRxBytes,
                network.mobileTxBytes,
                network.wifiRxBytes,
                network.wifiTxBytes,
                network.bluetoothRxBytes,
                network.bluetoothTxBytes,
                network.mobileRxPackets,
                network.mobileTxPackets,
                network.wifiRxPackets,
                network.wifiTxPackets,
                network.mobileRadioActiveMs,
            ).forEachIndexed { index, value -> statement.setLong(index + 2, value) }
            statement.executeUpdate()
        }
    }
    connection.prepareStatement("DELETE FROM battery_delta_history WHERE run_id=?").use { statement ->
        statement.setString(1, delta.runId)
        statement.executeUpdate()
    }
    delta.history.forEachIndexed { index, event ->
        connection.prepareStatement("INSERT INTO battery_delta_history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, delta.runId)
            statement.setInt(2, index)
            statement.setObject(3, event.elapsedMs)
            statement.setString(4, event.kind.name)
            statement.setObject(5, event.active?.let { if (it) 1 else 0 })
            statement.setString(6, event.name)
            statement.setObject(7, event.uid)
            statement.setString(8, event.raw)
            statement.setString(9, event.confidence.name)
            statement.executeUpdate()
        }
    }
    connection.prepareStatement("UPDATE battery_deltas SET history_persisted=1 WHERE run_id=?").use { statement ->
        statement.setString(1, delta.runId)
        statement.executeUpdate()
    }
}

internal fun loadStoredBatteryEvidence(
    connection: Connection,
    sessionId: String,
): StoredBatteryEvidence? {
    val session =
        connection
            .query(
                "SELECT s.*, (SELECT COUNT(*) FROM battery_runs WHERE session_id=s.id) AS run_count FROM battery_sessions s WHERE s.id=?",
                sessionId,
            ) { rows ->
                StoredBatterySession(
                    rows.getString("id"),
                    rows.getString("package_name"),
                    rows.getInt("uid"),
                    rows.getString("created_at"),
                    rows.getInt("run_count"),
                    BatterySessionStatus.valueOf(rows.getString("status")),
                    AttributionScope.valueOf(rows.getString("attribution_scope")),
                )
            }.singleOrNull() ?: return null
    val warnings = mutableListOf<String>()
    val runs =
        connection
            .query("SELECT * FROM battery_runs WHERE session_id=? ORDER BY iteration, started_at, id", sessionId) { rows ->
                val runId = rows.getString("id")
                val snapshots = loadSnapshots(connection, runId, session)
                if (snapshots.size != rows.getInt("sample_count") + 2) {
                    warnings +=
                        "Stored run '$runId' is missing baseline or final snapshot evidence, or intermediate samples, and cannot be restored."
                    null
                } else {
                    BatteryRun(
                        runId,
                        sessionId,
                        rows.getInt("iteration"),
                        snapshots.first(),
                        snapshots.drop(1).dropLast(1),
                        snapshots.last(),
                    )
                }
            }.filterNotNull()
    val networkTotals = mutableListOf<Double?>()
    val deltas =
        connection.query(
            "SELECT d.*, r.iteration FROM battery_deltas d JOIN battery_runs r ON r.id=d.run_id WHERE r.session_id=? ORDER BY r.iteration, r.started_at, r.id",
            sessionId,
        ) { rows ->
            networkTotals += rows.getLong("total_network_bytes").toDouble()
            loadDelta(connection, rows, sessionId, runs)
        }
    if (deltas.size < session.runCount) warnings += "Some stored runs have no completed delta; only persisted analysis is shown."
    val analyzer = BatteryAnalyzer()
    val analysis =
        BatteryAnalysisResult(
            runs = deltas,
            wakelockDurationMs = analyzer.statistics(deltas.map { it.wakelocks.sumOf(ResourceTimer::durationMs).toDouble() }),
            wakeupAlarmCount = analyzer.statistics(deltas.map { it.alarms.sumOf(ResourceTimer::count).toDouble() }),
            jobDurationMs = analyzer.statistics(deltas.map { it.jobs.sumOf(ResourceTimer::durationMs).toDouble() }),
            sensorDurationMs = analyzer.statistics(deltas.map { it.sensors.sumOf(ResourceTimer::durationMs).toDouble() }),
            networkBytes = analyzer.statistics(networkTotals),
            energyMah = analyzer.statistics(deltas.map { run -> run.energy.sumOf { it.energyMah ?: 0.0 }.takeIf { it > 0 } }),
            warnings = (deltas.flatMap(BatteryRunDelta::warnings) + warnings).distinct(),
            peakTemperatureCelsius = analyzer.statistics(deltas.map { it.peakTemperatureCelsius }),
            thermalStatus = analyzer.statistics(deltas.map { it.maxThermalStatus?.toDouble() }),
        )
    return StoredBatteryEvidence(session, analysis, runs)
}

private fun loadSnapshots(
    connection: Connection,
    runId: String,
    session: StoredBatterySession,
): List<BatterySnapshot> {
    val parser = BatteryStatsParser()
    return connection.query(
        "SELECT * FROM battery_snapshots WHERE run_id=? ORDER BY COALESCE(snapshot_index, sequence), captured_at, rowid",
        runId,
    ) { rows ->
        val raw =
            BatteryRawEvidence(
                rows.getString("checkin"),
                rows.getString("report"),
                rows.getString("battery"),
                rows.getString("history"),
                thermal = rows.getString("thermal_raw"),
            )
        val parsed = parser.parse(raw.checkin, raw.report, raw.battery, session.uid)
        val state = parser.parseDeviceState(raw.battery)
        BatterySnapshot(
            id = rows.getString("id"),
            sessionId = session.id,
            sequence = rows.getInt("sequence"),
            capturedAt = Instant.parse(rows.getString("captured_at")),
            statsPeriodId = rows.getString("stats_period_id"),
            bootId = rows.getString("boot_id"),
            uidStats = parsed.uidStats,
            deviceState =
                state.copy(
                    levelPercent = rows.nullableInt("level"),
                    temperatureTenthsCelsius = rows.nullableInt("temperature"),
                    powered =
                        rows.nullableInt("powered")?.let {
                            it !=
                                0
                        },
                ),
            history = raw.history?.let(parser::parseHistory) ?: parsed.history,
            warnings = rows.getString("warnings").nonemptyLines(),
            rawEvidence = raw,
            conditions = rows.getString("conditions").storedMap(),
            thermalEvidence =
                BatteryThermalEvidence(
                    rows.nullableInt("thermal_status"),
                    rows.getInt("thermal_throttling") != 0,
                    rows
                        .getString("thermal_temperatures")
                        .storedMap()
                        .mapNotNull { (name, value) ->
                            value.toDoubleOrNull()?.let {
                                name to
                                    it
                            }
                        }.toMap(),
                    raw.thermal,
                ),
        )
    }
}

private fun loadDelta(
    connection: Connection,
    rows: ResultSet,
    sessionId: String,
    runs: List<BatteryRun>,
): BatteryRunDelta {
    val runId = rows.getString("run_id")
    val network =
        connection
            .query("SELECT * FROM battery_delta_network WHERE run_id=?", runId) { network ->
                NetworkUsage(
                    network.getLong("mobile_rx_bytes"),
                    network.getLong("mobile_tx_bytes"),
                    network.getLong("wifi_rx_bytes"),
                    network.getLong("wifi_tx_bytes"),
                    network.getLong("bluetooth_rx_bytes"),
                    network.getLong("bluetooth_tx_bytes"),
                    network.getLong("mobile_rx_packets"),
                    network.getLong("mobile_tx_packets"),
                    network.getLong("wifi_rx_packets"),
                    network.getLong("wifi_tx_packets"),
                    network.getLong("mobile_radio_active_ms"),
                )
            }.singleOrNull()
    val resources =
        connection
            .query("SELECT * FROM battery_resources WHERE run_id=? ORDER BY id", runId) { resource ->
                resource.getString("kind") to
                    ResourceTimer(
                        resource.getString("name"),
                        resource.getLong("duration_ms"),
                        resource.getLong("count"),
                        EvidenceConfidence.valueOf(resource.getString("confidence")),
                    )
            }.groupBy({ it.first }, { it.second })
    val energy =
        connection.query("SELECT * FROM battery_energy WHERE run_id=? ORDER BY id", runId) { energy ->
            EnergyEstimate(
                energy.getString("component"),
                energy.nullableDouble("energy_mah"),
                energy.nullableLong("energy_uws"),
                EnergyEvidenceKind.valueOf(energy.getString("source")),
                AttributionScope.valueOf(energy.getString("scope")),
                EvidenceConfidence.valueOf(energy.getString("confidence")),
            )
        }
    val history =
        if (rows.getInt("history_persisted") == 0) {
            val finalSnapshot = runs.firstOrNull { it.id == runId }?.finalSnapshot
            finalSnapshot?.history.orEmpty().filter { it.uid == null || it.uid == finalSnapshot?.uidStats?.uid }
        } else {
            connection.query("SELECT * FROM battery_delta_history WHERE run_id=? ORDER BY event_index", runId) { event ->
                BatteryHistoryEvent(
                    event.nullableLong("elapsed_ms"),
                    BatteryHistoryEventKind.valueOf(event.getString("kind")),
                    event.nullableInt("active")?.let {
                        it !=
                            0
                    },
                    event.getString("name"),
                    event.nullableInt("uid"),
                    event.getString("raw"),
                    EvidenceConfidence.valueOf(event.getString("confidence")),
                )
            }
        }
    val windows =
        connection.query("SELECT * FROM battery_performance_windows WHERE run_id=? ORDER BY window_index", runId) { window ->
            BatteryPerformanceWindow(
                Instant.parse(window.getString("started_at")),
                Instant.parse(window.getString("ended_at")),
                window.getLong("duration_ms"),
                window.nullableDouble("peak_temperature_c"),
                window.nullableInt("max_thermal_status"),
                window.getInt("throttled") != 0,
            )
        }
    val warnings =
        rows.getString("warnings").nonemptyLines() +
            if (network ==
                null
            ) {
                listOf(
                    "Stored run '$runId' has no persisted network component counters; only total network bytes are available. Transport counters are unavailable, not measured zero.",
                )
            } else {
                emptyList()
            }
    return BatteryRunDelta(
        runId,
        sessionId,
        rows.getInt("iteration"),
        rows.getLong("duration_ms"),
        resources["wakelock"].orEmpty(),
        resources["alarm"].orEmpty(),
        resources["job"].orEmpty(),
        resources["sensor"].orEmpty(),
        network ?: NetworkUsage(),
        energy,
        history,
        warnings,
        rows.nullableDouble("peak_temperature_c"),
        rows.nullableInt("max_thermal_status"),
        rows.getInt("throttled_samples"),
        windows,
        networkTotalBytes = rows.getLong("total_network_bytes"),
        networkCountersAvailable = network != null,
    )
}

private fun <T> Connection.query(
    sql: String,
    id: String,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

private fun ResultSet.nullableInt(name: String): Int? = getInt(name).let { if (wasNull()) null else it }

private fun ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }

private fun ResultSet.nullableDouble(name: String): Double? = getDouble(name).let { if (wasNull()) null else it }

private fun String.nonemptyLines(): List<String> = lineSequence().filter(String::isNotEmpty).toList()

private fun String.storedMap(): Map<String, String> =
    nonemptyLines()
        .mapNotNull { line ->
            line.indexOf('=').takeIf { it >= 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
        }.toMap()
