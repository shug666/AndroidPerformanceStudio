package com.androidperformancestudio.benchmark.storage

import com.androidperformancestudio.benchmark.model.BaselineProfileStatus
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

public data class StoredBenchmarkRun(
    val id: String,
    val sourcePath: String,
    val importedAt: String,
    val deviceModel: String?,
    val caseCount: Int,
    val traceArtifactCount: Int = 0,
    val baselineProfileCaseCount: Int = 0,
    val versionName: String? = null,
    val gitCommit: String? = null,
    val apiLevel: Int? = null,
)

/** One case/metric pair that has stored history, used to drive the trend selector. */
public data class StoredBenchmarkMetric(
    val caseIdentity: String,
    val metricName: String,
    val unit: String,
    val pointCount: Int,
)

/** One metric value per run, ordered chronologically by the caller. */
public data class StoredMetricPoint(
    val runId: String,
    val importedAt: String,
    val buildLabel: String?,
    val gitCommit: String?,
    val deviceModel: String?,
    val apiLevel: Int?,
    val value: Double,
    val sampleCount: Int,
)

public class SqliteBenchmarkStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_run(id TEXT PRIMARY KEY, source_path TEXT NOT NULL, imported_at TEXT NOT NULL, device_model TEXT, case_count INTEGER NOT NULL, version_name TEXT, git_commit TEXT, api_level INTEGER)")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_metric(run_id TEXT NOT NULL, case_identity TEXT NOT NULL, metric_name TEXT NOT NULL, unit TEXT NOT NULL, median REAL, samples INTEGER NOT NULL, PRIMARY KEY(run_id, case_identity, metric_name), FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_case(run_id TEXT NOT NULL, case_identity TEXT NOT NULL, scenario TEXT NOT NULL, startup_mode TEXT, compilation_mode TEXT, baseline_profile_status TEXT, baseline_profile_source TEXT, baseline_profile_artifact TEXT, baseline_profile_hash TEXT, baseline_profile_verified_at TEXT, PRIMARY KEY(run_id, case_identity), FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE)")
            statement.execute("CREATE TABLE IF NOT EXISTS benchmark_trace(run_id TEXT NOT NULL, case_identity TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(run_id, case_identity, path), FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE)")
        }
        ensureColumns(
            "benchmark_run",
            mapOf(
                "version_name" to "TEXT",
                "git_commit" to "TEXT",
                "api_level" to "INTEGER",
            ),
        )
        ensureColumns(
            "benchmark_case",
            mapOf(
                "baseline_profile_source" to "TEXT",
                "baseline_profile_artifact" to "TEXT",
                "baseline_profile_hash" to "TEXT",
                "baseline_profile_verified_at" to "TEXT",
            ),
        )
    }

    public fun save(run: BenchmarkRun) {
        connection.autoCommit = false
        try {
            upsertRun(run)
            deleteEvidence(run.id)
            insertCases(run)
            insertTraces(run)
            insertMetrics(run)
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun upsertRun(run: BenchmarkRun) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO benchmark_run(id,source_path,imported_at,device_model,case_count," +
                "version_name,git_commit,api_level) VALUES(?,?,?,?,?,?,?,?)",
        ).use { statement ->
            statement.setString(1, run.id)
            statement.setString(2, run.sourceFile.toString())
            statement.setString(3, run.importedAt.toString())
            statement.setString(4, run.device.model)
            statement.setInt(5, run.cases.size)
            statement.setString(6, run.build.versionName)
            statement.setString(7, run.build.gitCommit)
            run.device.apiLevel?.let { statement.setInt(8, it) } ?: statement.setNull(8, java.sql.Types.INTEGER)
            statement.executeUpdate()
        }
    }

    private fun deleteEvidence(runId: String) {
        listOf("benchmark_metric", "benchmark_case", "benchmark_trace").forEach { table ->
            connection.prepareStatement("DELETE FROM $table WHERE run_id=?").use { statement ->
                statement.setString(1, runId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertCases(run: BenchmarkRun) {
        connection.prepareStatement(
            "INSERT INTO benchmark_case(run_id,case_identity,scenario,startup_mode,compilation_mode," +
                "baseline_profile_status,baseline_profile_source,baseline_profile_artifact,baseline_profile_hash," +
                "baseline_profile_verified_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        ).use { statement ->
            run.cases.forEach { case ->
                statement.setString(1, run.id)
                statement.setString(2, case.identity)
                statement.setString(3, case.scenario.name)
                statement.setString(4, case.startupMode)
                statement.setString(5, case.compilationMode)
                statement.setString(6, case.baselineProfile?.status?.name ?: BaselineProfileStatus.NOT_REQUESTED.name)
                statement.setString(7, case.baselineProfile?.source)
                statement.setString(8, case.baselineProfile?.artifact?.toString())
                statement.setString(9, case.baselineProfile?.profileHash)
                statement.setString(10, case.baselineProfile?.verifiedAt?.toString())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertTraces(run: BenchmarkRun) {
        connection.prepareStatement("INSERT INTO benchmark_trace(run_id,case_identity,path) VALUES(?,?,?)").use { statement ->
            run.cases.forEach { case ->
                case.traceArtifacts.forEach { trace ->
                    statement.setString(1, run.id)
                    statement.setString(2, case.identity)
                    statement.setString(3, trace.toString())
                    statement.addBatch()
                }
            }
            statement.executeBatch()
        }
    }

    private fun insertMetrics(run: BenchmarkRun) {
        connection.prepareStatement(
            "INSERT INTO benchmark_metric(run_id,case_identity,metric_name,unit,median,samples) VALUES(?,?,?,?,?,?)",
        ).use { statement ->
            run.cases.forEach { case ->
                case.metrics.forEach { metric ->
                    statement.setString(1, run.id)
                    statement.setString(2, case.identity)
                    statement.setString(3, metric.name)
                    statement.setString(4, metric.unit)
                    metric.representativeValue()?.let { statement.setDouble(5, it) }
                        ?: statement.setNull(5, java.sql.Types.REAL)
                    statement.setInt(6, metric.samples.size)
                    statement.addBatch()
                }
            }
            statement.executeBatch()
        }
    }

    public fun listRecent(limit: Int = 50): List<StoredBenchmarkRun> =
        connection.prepareStatement(
            "SELECT id,source_path,imported_at,device_model,case_count,version_name,git_commit,api_level " +
                "FROM benchmark_run ORDER BY imported_at DESC LIMIT ?",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val id = result.getString(1)
                        val traceCount = count("SELECT COUNT(*) FROM benchmark_trace WHERE run_id = ?", id)
                        val profileCount = count("SELECT COUNT(*) FROM benchmark_case WHERE run_id = ? AND baseline_profile_status IN ('INSTALLED', 'VERIFIED')", id)
                        add(
                            StoredBenchmarkRun(
                                id = id,
                                sourcePath = result.getString(2),
                                importedAt = result.getString(3),
                                deviceModel = result.getString(4),
                                caseCount = result.getInt(5),
                                traceArtifactCount = traceCount,
                                baselineProfileCaseCount = profileCount,
                                versionName = result.getString(6),
                                gitCommit = result.getString(7),
                                apiLevel = result.getInt(8).takeIf { !result.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    /** Case/metric pairs with at least one stored value, most recently measured first. */
    public fun listMetricKeys(limit: Int = 200): List<StoredBenchmarkMetric> =
        connection.prepareStatement(
            "SELECT m.case_identity,m.metric_name,m.unit,COUNT(*),MAX(r.imported_at) AS last_seen " +
                "FROM benchmark_metric m JOIN benchmark_run r ON r.id = m.run_id " +
                "WHERE m.median IS NOT NULL GROUP BY m.case_identity,m.metric_name,m.unit " +
                "ORDER BY last_seen DESC LIMIT ?",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(StoredBenchmarkMetric(result.getString(1), result.getString(2), result.getString(3), result.getInt(4)))
                    }
                }
            }
        }

    /**
     * Chronological values for one case/metric across the most recent [limit] runs. Runs are kept
     * newest-last so a trend reads left to right; the caller decides how to present environment
     * changes because this query deliberately does not filter them out.
     */
    public fun metricHistory(
        caseIdentity: String,
        metricName: String,
        limit: Int = 10,
    ): List<StoredMetricPoint> {
        val points =
            connection.prepareStatement(
                "SELECT r.id,r.imported_at,r.version_name,r.git_commit,r.device_model,r.api_level,m.median,m.samples " +
                    "FROM benchmark_metric m JOIN benchmark_run r ON r.id = m.run_id " +
                    "WHERE m.case_identity = ? AND m.metric_name = ? AND m.median IS NOT NULL " +
                    "ORDER BY r.imported_at DESC LIMIT ?",
            ).use { statement ->
                statement.setString(1, caseIdentity)
                statement.setString(2, metricName)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                StoredMetricPoint(
                                    runId = result.getString(1),
                                    importedAt = result.getString(2),
                                    buildLabel = result.getString(3),
                                    gitCommit = result.getString(4),
                                    deviceModel = result.getString(5),
                                    apiLevel = result.getInt(6).takeIf { !result.wasNull() },
                                    value = result.getDouble(7),
                                    sampleCount = result.getInt(8),
                                ),
                            )
                        }
                    }
                }
            }
        return points.reversed()
    }

    private fun count(sql: String, id: String): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
        }

    private fun ensureColumns(
        table: String,
        definitions: Map<String, String>,
    ) {
        val existing =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    buildSet { while (result.next()) add(result.getString("name")) }
                }
            }
        definitions.filterKeys { it !in existing }.forEach { (name, definition) ->
            connection.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $name $definition") }
        }
    }

    override fun close() {
        connection.close()
    }

    public companion object {
        public fun open(path: Path): SqliteBenchmarkStore {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            return SqliteBenchmarkStore(DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}"))
        }
    }
}
