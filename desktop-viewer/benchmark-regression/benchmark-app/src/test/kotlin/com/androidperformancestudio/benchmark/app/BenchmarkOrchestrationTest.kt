package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.cli.MacrobenchmarkCompilationMode
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkScenario
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkStartupMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkOrchestrationTest {
    @Test
    fun `an empty form is rejected before a plan is constructed`() {
        assertEquals(
            BenchmarkPlanProblem.PROJECT_REQUIRED,
            (BenchmarkRunPlanForm().validate() as BenchmarkPlanValidation.Rejected).problem,
        )
        assertEquals(
            BenchmarkPlanProblem.PROJECT_NOT_A_DIRECTORY,
            (BenchmarkRunPlanForm(projectRoot = "/does/not/exist").validate() as BenchmarkPlanValidation.Rejected).problem,
        )
    }

    @Test
    fun `numeric fields are validated against the plan limits`() {
        val root = createTempDirectory("orchestration-plan").toString()
        assertEquals(
            BenchmarkPlanProblem.WARMUPS_INVALID,
            (BenchmarkRunPlanForm(projectRoot = root, warmups = "101").validate() as BenchmarkPlanValidation.Rejected).problem,
        )
        assertEquals(
            BenchmarkPlanProblem.ITERATIONS_INVALID,
            (BenchmarkRunPlanForm(projectRoot = root, iterations = "0").validate() as BenchmarkPlanValidation.Rejected).problem,
        )
        assertEquals(
            BenchmarkPlanProblem.TIMEOUT_INVALID,
            (BenchmarkRunPlanForm(projectRoot = root, timeoutSeconds = "0").validate() as BenchmarkPlanValidation.Rejected).problem,
        )
    }

    @Test
    fun `a valid form becomes the same plan the CLI executes`() {
        val root = createTempDirectory("orchestration-ready")
        val form =
            BenchmarkRunPlanForm(
                projectRoot = root.toString(),
                gradleTask = " ",
                benchmarkFilter = " com.example.StartupBenchmark#startup ",
                compilationMode = MacrobenchmarkCompilationMode.SPEED_PROFILE,
                startupMode = MacrobenchmarkStartupMode.WARM,
                scenario = MacrobenchmarkScenario.SCROLL,
                warmups = "2",
                iterations = "5",
                traceDirectory = "/tmp/traces",
                resultJson = " /tmp/benchmarkData.json ",
                timeoutSeconds = "600",
            )

        val ready = form.validate() as BenchmarkPlanValidation.Ready

        assertEquals(DEFAULT_MACROBENCHMARK_TASK, ready.plan.gradleTask)
        assertEquals("com.example.StartupBenchmark#startup", ready.plan.benchmarkFilter)
        assertEquals(MacrobenchmarkCompilationMode.SPEED_PROFILE, ready.plan.compilationMode)
        assertEquals(MacrobenchmarkStartupMode.WARM, ready.plan.startupMode)
        assertEquals(MacrobenchmarkScenario.SCROLL, ready.plan.scenario)
        assertEquals(2, ready.plan.warmups)
        assertEquals(5, ready.plan.iterations)
        assertEquals(Path.of("/tmp/traces"), ready.plan.traceDirectory)
        assertEquals("/tmp/benchmarkData.json", ready.resultJson)
        assertEquals(600L, ready.timeoutSeconds)
    }

    @Test
    fun `an explicit result path must exist and otherwise wins over discovery`() {
        val root = createTempDirectory("orchestration-result")
        val discovered = root.resolve("module/build/outputs/benchmarkData.json")
        discovered.parent.createDirectories()
        discovered.writeText("{}")

        assertNull(BenchmarkResultLocator.locate(root, "/tmp/missing-result.json"))
        assertEquals(discovered, BenchmarkResultLocator.locate(root, discovered.toString()))
    }

    @Test
    fun `discovery picks the newest benchmark json and skips unrelated files`() {
        val root = createTempDirectory("orchestration-discovery")
        val older = root.resolve("macrobenchmark/build/outputs/connected/benchmarkData.json")
        older.parent.createDirectories()
        older.writeText("{}")
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))

        val newer = root.resolve("app/build/outputs/connected_android_test_additional_output/benchmarkData.json")
        newer.parent.createDirectories()
        newer.writeText("{}")
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-02-01T00:00:00Z")))

        // A same-named file inside a skipped directory must never be chosen.
        val inGit = root.resolve(".git/benchmarkData.json")
        inGit.parent.createDirectories()
        inGit.writeText("{}")
        Files.setLastModifiedTime(inGit, FileTime.from(Instant.parse("2026-03-01T00:00:00Z")))

        // Unrelated JSON is ignored even when it is newer.
        val unrelated = root.resolve("app/build/reports/report.json")
        unrelated.parent.createDirectories()
        unrelated.writeText("{}")
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.parse("2026-04-01T00:00:00Z")))

        assertEquals(newer, BenchmarkResultLocator.locate(root, null))
    }

    @Test
    fun `discovery returns nothing when no benchmark output exists`() {
        val root = createTempDirectory("orchestration-empty")
        assertNull(BenchmarkResultLocator.locate(root, null))
        assertTrue(Files.isDirectory(root))
    }
}
