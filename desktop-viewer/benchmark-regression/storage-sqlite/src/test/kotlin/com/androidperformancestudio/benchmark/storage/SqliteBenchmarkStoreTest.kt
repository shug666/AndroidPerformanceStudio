@file:Suppress("LongMethod")

package com.androidperformancestudio.benchmark.storage

import com.androidperformancestudio.benchmark.model.*
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBenchmarkStoreTest {
    @Test
    fun `persists run summary idempotently`() {
        val database = createTempDirectory().resolve("db.sqlite")
        val trace = Path.of("trace.perfetto-trace")
        val profile = Path.of("baseline-prof.txt")
        val run =
            BenchmarkRun(
                sourceFile = Path.of("result.json"),
                benchmarkDataVersion = 1,
                benchmarkLibraryVersion = null,
                device = BenchmarkDevice("Pixel", null, 35, null, "arm64", null, null, true),
                build = BenchmarkBuild(null, null, null, null, null, null),
                cases =
                    listOf(
                        BenchmarkCase(
                            "Bench",
                            "test",
                            null,
                            "speed-profile",
                            null,
                            1,
                            listOf(
                                BenchmarkMetric(
                                    "timeMs",
                                    "ms",
                                    MetricDirection.LOWER_IS_BETTER,
                                    listOf(1.0),
                                    1.0,
                                    1.0,
                                    1.0,
                                    EvidenceConfidence.EXACT,
                                ),
                            ),
                            listOf(trace),
                            scenario = BenchmarkScenario.SCROLL,
                            baselineProfile =
                                BaselineProfileEvidence(
                                    status = BaselineProfileStatus.VERIFIED,
                                    source = "baseline-profile-plugin",
                                    artifact = profile,
                                    profileHash = "abc",
                                    verifiedAt = Instant.EPOCH,
                                ),
                        ),
                    ),
            )
        SqliteBenchmarkStore.open(database).use { store ->
            store.save(run)
            store.save(run)
            val saved = store.listRecent().single()
            assertEquals(1, saved.traceArtifactCount)
            assertEquals(1, saved.baselineProfileCaseCount)
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT baseline_profile_source, baseline_profile_artifact, baseline_profile_hash, baseline_profile_verified_at FROM benchmark_case").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("baseline-profile-plugin", rows.getString(1))
                    assertEquals(profile.toString(), rows.getString(2))
                    assertEquals("abc", rows.getString(3))
                    assertEquals(Instant.EPOCH.toString(), rows.getString(4))
                }
            }
        }
    }

    @Test
    fun `metric history keeps chronological order and run metadata`() {
        val database = createTempDirectory().resolve("history.sqlite")
        SqliteBenchmarkStore.open(database).use { store ->
            store.save(run("r1", "v1.0.0", "aaa1111", Instant.parse("2026-01-01T00:00:00Z"), 10.0))
            store.save(run("r2", "v2.0.0", "bbb2222", Instant.parse("2026-02-01T00:00:00Z"), 20.0))

            val history = store.metricHistory("Bench#test", "timeMs")
            assertEquals(listOf("r1", "r2"), history.map { it.runId })
            assertEquals(listOf("v1.0.0", "v2.0.0"), history.map { it.buildLabel })
            assertEquals(listOf("aaa1111", "bbb2222"), history.map { it.gitCommit })
            assertEquals(listOf(10.0, 20.0), history.map { it.value })
            assertEquals(listOf(1, 1), history.map { it.sampleCount })

            val metric = store.listMetricKeys().single()
            assertEquals("Bench#test", metric.caseIdentity)
            assertEquals("timeMs", metric.metricName)
            assertEquals(2, metric.pointCount)

            val newest = store.listRecent().first()
            assertEquals("v2.0.0", newest.versionName)
            assertEquals("bbb2222", newest.gitCommit)
            assertEquals(35, newest.apiLevel)
        }
    }

    private fun run(
        id: String,
        versionName: String,
        gitCommit: String,
        importedAt: Instant,
        value: Double,
    ): BenchmarkRun =
        BenchmarkRun(
            id = id,
            sourceFile = Path.of(id + ".json"),
            benchmarkDataVersion = 1,
            benchmarkLibraryVersion = null,
            device = BenchmarkDevice("Pixel", null, 35, null, "arm64", null, null, true),
            build = BenchmarkBuild(null, versionName, null, null, gitCommit, null),
            importedAt = importedAt,
            cases =
                listOf(
                    BenchmarkCase(
                        "Bench",
                        "test",
                        null,
                        "speed-profile",
                        null,
                        1,
                        listOf(
                            BenchmarkMetric(
                                "timeMs",
                                "ms",
                                MetricDirection.LOWER_IS_BETTER,
                                listOf(value),
                                value,
                                value,
                                value,
                                EvidenceConfidence.EXACT,
                            ),
                        ),
                        emptyList(),
                        scenario = BenchmarkScenario.SCROLL,
                    ),
                ),
        )
}
