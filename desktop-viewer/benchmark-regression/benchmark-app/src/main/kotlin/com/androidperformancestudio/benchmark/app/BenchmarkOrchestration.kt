@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.cli.MacrobenchmarkCompilationMode
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkExperimentPlan
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkScenario
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkStartupMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

public const val DEFAULT_MACROBENCHMARK_TASK: String = ":macrobenchmark:connectedCheck"

/**
 * In-app form for the same plan the APS CLI executes through `macro-run`. Keeping the fields as text
 * lets the dialog validate before constructing [MacrobenchmarkExperimentPlan], whose init block would
 * otherwise throw out of the click handler.
 */
public data class BenchmarkRunPlanForm(
    val projectRoot: String = "",
    val gradleTask: String = DEFAULT_MACROBENCHMARK_TASK,
    val benchmarkFilter: String = "",
    val compilationMode: MacrobenchmarkCompilationMode = MacrobenchmarkCompilationMode.NONE,
    val startupMode: MacrobenchmarkStartupMode = MacrobenchmarkStartupMode.COLD,
    val scenario: MacrobenchmarkScenario = MacrobenchmarkScenario.STARTUP,
    val warmups: String = "2",
    val iterations: String = "5",
    val traceDirectory: String = "",
    val resultJson: String = "",
    val timeoutSeconds: String = "3600",
)

/** Why a form cannot start a run yet; presentations localize each value. */
public enum class BenchmarkPlanProblem {
    PROJECT_REQUIRED,
    PROJECT_NOT_A_DIRECTORY,
    WARMUPS_INVALID,
    ITERATIONS_INVALID,
    TIMEOUT_INVALID,
}

public sealed interface BenchmarkPlanValidation {
    /** [resultJson] stays a string because AndroidX decides where it writes the JSON. */
    public data class Ready(
        val plan: MacrobenchmarkExperimentPlan,
        val resultJson: String?,
        val timeoutSeconds: Long,
    ) : BenchmarkPlanValidation

    public data class Rejected(val problem: BenchmarkPlanProblem) : BenchmarkPlanValidation
}

public fun BenchmarkRunPlanForm.validate(): BenchmarkPlanValidation {
    val project = projectRoot.trim()
    if (project.isEmpty()) return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.PROJECT_REQUIRED)
    val projectPath = runCatching { Path.of(project) }.getOrNull()
        ?: return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.PROJECT_NOT_A_DIRECTORY)
    if (!Files.isDirectory(projectPath)) {
        return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.PROJECT_NOT_A_DIRECTORY)
    }
    val warmupCount = warmups.trim().toIntOrNull()?.takeIf { it in 0..MAX_WARMUPS }
        ?: return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.WARMUPS_INVALID)
    val iterationCount = iterations.trim().toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS }
        ?: return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.ITERATIONS_INVALID)
    val timeout = timeoutSeconds.trim().toLongOrNull()?.takeIf { it > 0 }
        ?: return BenchmarkPlanValidation.Rejected(BenchmarkPlanProblem.TIMEOUT_INVALID)
    return BenchmarkPlanValidation.Ready(
        plan =
            MacrobenchmarkExperimentPlan(
                projectRoot = projectPath.toAbsolutePath().normalize(),
                gradleTask = gradleTask.trim().ifBlank { DEFAULT_MACROBENCHMARK_TASK },
                benchmarkFilter = benchmarkFilter.trim().takeIf(String::isNotEmpty),
                compilationMode = compilationMode,
                startupMode = startupMode,
                scenario = scenario,
                warmups = warmupCount,
                iterations = iterationCount,
                traceDirectory =
                    traceDirectory.trim().takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() },
            ),
        resultJson = resultJson.trim().takeIf(String::isNotEmpty),
        timeoutSeconds = timeout,
    )
}

/**
 * Finds the benchmark JSON produced by a run.
 *
 * An explicit path always wins and is only returned when it exists, so a typo cannot silently import
 * an unrelated file. Without one, the newest `benchmarkData`/additional-output JSON under the project
 * is used: AndroidX writes the file where the target project configures it, so the UI has to look for
 * it instead of guessing a fixed path.
 */
public object BenchmarkResultLocator {
    public const val DISCOVERY_DEPTH: Int = 8

    private val SKIPPED_DIRECTORIES = setOf(".git", "node_modules", ".gradle", ".idea")

    public fun locate(
        projectRoot: Path,
        explicit: String?,
    ): Path? {
        val explicitPath = explicit?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() }
        if (explicitPath != null) {
            return explicitPath.toAbsolutePath().normalize().takeIf(Files::isRegularFile)
        }
        return newestBenchmarkJson(projectRoot)
    }

    private fun newestBenchmarkJson(projectRoot: Path): Path? =
        runCatching {
            Files.walk(projectRoot, DISCOVERY_DEPTH).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .filter { path -> path.none { component -> component.toString() in SKIPPED_DIRECTORIES } }
                    .filter { path -> path.fileName.toString().endsWith(".json", ignoreCase = true) }
                    .filter { path ->
                        val text = path.toString()
                        text.contains("benchmarkData") || text.contains("additional_output")
                    }
                    .max(
                        Comparator.comparingLong<Path> { path ->
                            Files.getLastModifiedTime(path).toMillis()
                        },
                    ).orElse(null)
            }
        }.getOrNull()
}

private const val MAX_WARMUPS = 100
private const val MAX_ITERATIONS = 100
