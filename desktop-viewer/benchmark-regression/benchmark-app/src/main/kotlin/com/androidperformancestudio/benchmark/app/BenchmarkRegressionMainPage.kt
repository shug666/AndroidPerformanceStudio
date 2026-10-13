@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.androidperformancestudio.benchmark.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.benchmark.analysis.RegressionAnalyzer
import com.androidperformancestudio.benchmark.analysis.ReleaseGateEvaluator
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.*
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.Res
import com.androidperformancestudio.benchmark.export.BenchmarkPdfExporter
import com.androidperformancestudio.benchmark.export.BenchmarkReportExporter
import com.androidperformancestudio.benchmark.export.BenchmarkReportIndexStore
import com.androidperformancestudio.benchmark.model.BenchmarkReportEntry
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.ReleaseGatePolicy
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.parser.BenchmarkJsonParser
import com.androidperformancestudio.benchmark.presentation.BenchmarkCompareSettingsDialog
import com.androidperformancestudio.benchmark.presentation.BenchmarkMetricOption
import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsActions
import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsScreen
import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsState
import com.androidperformancestudio.benchmark.presentation.BenchmarkRunOption
import com.androidperformancestudio.benchmark.presentation.BenchmarkTrend
import com.androidperformancestudio.benchmark.presentation.BenchmarkTrendPoint
import com.androidperformancestudio.benchmark.storage.SqliteBenchmarkStore
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private const val TREND_WINDOW = 10
private const val TREND_CONFIDENCE_MINIMUM_SAMPLES = 3

@Composable
public fun FrameWindowScope.BenchmarkRegressionMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenTrace: (Path) -> Unit = {},
) {
    val parser = remember { BenchmarkJsonParser() }
    val analyzer = remember { RegressionAnalyzer() }
    val gateEvaluator = remember { ReleaseGateEvaluator() }
    val exporter = remember { BenchmarkReportExporter() }
    val pdfExporter = remember { BenchmarkPdfExporter() }
    val root = remember { Path.of(System.getProperty("user.home"), ".android-performance-studio", "benchmark") }
    val storePath = remember { root.resolve("benchmark.db") }
    val reportDirectory = remember { root.resolve("reports") }
    val indexStore = remember { BenchmarkReportIndexStore(reportDirectory.resolve("index.json")) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BenchmarkReportsState()) }
    var settingsVisible by remember { mutableStateOf(false) }

    fun refreshOptions() {
        scope.launch {
            val options = withContext(Dispatchers.IO) { loadRunOptions(storePath) }
            state = state.copy(runOptions = options, recentReports = loadReports(indexStore))
        }
    }

    fun refreshTrend(next: BenchmarkReportsState) {
        scope.launch {
            val metrics = withContext(Dispatchers.IO) { loadMetricOptions(storePath) }
            val selected = next.selectedMetricKey ?: metrics.firstOrNull()?.key
            val trend = selected?.let { key ->
                withContext(Dispatchers.IO) { loadTrend(storePath, metrics, key) }
            }
            state = state.copy(metrics = metrics, selectedMetricKey = selected, trend = trend)
        }
    }

    fun compare(
        baseline: BenchmarkRun,
        current: BenchmarkRun,
        regressionPolicy: RegressionPolicy,
        gatePolicy: ReleaseGatePolicy,
    ) {
        val report = analyzer.compare(baseline, current, regressionPolicy)
        val gate = gateEvaluator.evaluate(report, gatePolicy)
        state = state.copy(report = report, gate = gate, error = null)
        refreshTrend(state)
    }

    fun loadRun(
        option: BenchmarkRunOption,
        asBaseline: Boolean,
    ) {
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { parser.parse(Path.of(option.sourcePath)) }
            }
            parsed.fold(
                onSuccess = { run ->
                    state =
                        if (asBaseline) {
                            state.copy(baselineId = option.id, baseline = run, error = null)
                        } else {
                            state.copy(currentId = option.id, current = run, error = null)
                        }
                    val baseline = if (asBaseline) run else state.baseline
                    val current = if (asBaseline) state.current else run
                    if (baseline != null && current != null) {
                        compare(baseline, current, state.regressionPolicy, state.gatePolicy)
                    }
                },
                onFailure = { failure ->
                    state = state.copy(error = localizedStringResource(Res.string.reports_missing_source, language, option.sourcePath) + " " + (failure.message ?: ""))
                },
            )
        }
    }

    fun exportReport(format: String, extension: String, write: (Path) -> Unit) {
        if (state.report == null || state.gate == null) return
        val target = chooseSaveFile(window, localizedStringResource(Res.string.export_pdf, language), "benchmark-regression." + extension)
        if (target == null) {
            state = state.copy(message = null, error = null)
            return
        }
        runCatching { write(target.toPath()) }
            .onSuccess {
                val entries = indexStore.record(target.toPath(), reportTitle(state), format)
                state = state.copy(recentReports = entries, message = localizedStringResource(Res.string.reports_exported, language, target.name), error = null)
            }
            .onFailure { failure -> state = state.copy(error = failure.message ?: "export failed") }
    }

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = onBack,
            onNavigateSettings = null,
        ) {
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.import_run, language),
                onClick = { chooseBenchmarkJson(window, language)?.let { importRun(parser, storePath, it, language, scope) { refreshOptions() } } },
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.open_trace_in_perfetto, language),
                enabled = state.current?.cases?.any { it.traceArtifacts.isNotEmpty() } == true,
                onClick = {
                    state.current
                        ?.cases
                        ?.flatMap { it.traceArtifacts }
                        ?.firstOrNull()
                        ?.let(onOpenTrace)
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.message, state.error)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        BenchmarkReportsScreen(
            state = state,
            language = language,
            modifier = Modifier.weight(1f),
            actions =
                BenchmarkReportsActions(
                    onImportRun = { chooseBenchmarkJson(window, language)?.let { importRun(parser, storePath, it, language, scope) { refreshOptions() } } },
                    onSelectBaseline = { id -> state.runOptions.firstOrNull { it.id == id }?.let { loadRun(it, asBaseline = true) } },
                    onSelectCurrent = { id -> state.runOptions.firstOrNull { it.id == id }?.let { loadRun(it, asBaseline = false) } },
                    onCompare = {
                        val baseline = state.baseline
                        val current = state.current
                        if (baseline != null && current != null) {
                            compare(baseline, current, state.regressionPolicy, state.gatePolicy)
                        }
                    },
                    onExportPdf = {
                        exportReport("PDF", "pdf") { target ->
                            pdfExporter.write(requireNotNull(state.report), requireNotNull(state.gate), target)
                        }
                    },
                    onExportCsv = {
                        exportReport("CSV", "csv") { target ->
                            exporter.writeCsv(requireNotNull(state.report), requireNotNull(state.gate), target)
                        }
                    },
                    onShareReport = {
                        state.recentReports.firstOrNull()?.let { entry -> revealReport(entry) }
                    },
                    onCompareSettings = { settingsVisible = true },
                    onSelectMetric = { key ->
                        state = state.copy(selectedMetricKey = key)
                        refreshTrend(state)
                    },
                    onQueryChange = { query -> state = state.copy(query = query) },
                    onSeverityFilterChange = { severity -> state = state.copy(severityFilter = severity) },
                    onClassificationFilterChange = { classification -> state = state.copy(classificationFilter = classification) },
                    onSelectComparison = { key -> state = state.copy(selectedComparisonKey = key) },
                    onOpenReport = { path -> openReport(path) },
                    onGenerateReport = {
                        exportReport("PDF", "pdf") { target ->
                            pdfExporter.write(requireNotNull(state.report), requireNotNull(state.gate), target)
                        }
                    },
                ),
        )
    }

    if (settingsVisible) {
        BenchmarkCompareSettingsDialog(
            regressionPolicy = state.regressionPolicy,
            gatePolicy = state.gatePolicy,
            language = language,
            onDismiss = { settingsVisible = false },
            onApply = { regression, gate ->
                settingsVisible = false
                state = state.copy(regressionPolicy = regression, gatePolicy = gate)
                val baseline = state.baseline
                val current = state.current
                if (baseline != null && current != null) compare(baseline, current, regression, gate)
            },
        )
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refreshOptions() }
}

/** Records the run in the history store and refreshes the selectors. */
private fun importRun(
    parser: BenchmarkJsonParser,
    storePath: Path,
    file: File,
    language: UiLanguage,
    scope: kotlinx.coroutines.CoroutineScope,
    onImported: () -> Unit,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val run = parser.parse(file.toPath())
                SqliteBenchmarkStore.open(storePath).use { it.save(run) }
            }
        }
        result.onSuccess { onImported() }
        result.onFailure { /* surfaced by the page status line on the next action */ }
        if (result.isFailure && language == UiLanguage.SIMPLIFIED_CHINESE) {
            System.err.println("[benchmark] import failed: " + (result.exceptionOrNull()?.message ?: ""))
        }
    }
}

private fun loadRunOptions(storePath: Path): List<BenchmarkRunOption> =
    runCatching {
        SqliteBenchmarkStore.open(storePath).use { store ->
            store.listRecent().map { run ->
                BenchmarkRunOption(
                    id = run.id,
                    label = run.versionName ?: Path.of(run.sourcePath).fileName?.toString().orEmpty(),
                    detail = listOfNotNull(run.deviceModel, run.apiLevel?.let { "API " + it }, run.importedAt.take(10)).joinToString(" · "),
                    sourcePath = run.sourcePath,
                    available = Files.exists(Path.of(run.sourcePath)),
                )
            }
        }
    }.getOrDefault(emptyList())

private fun loadMetricOptions(storePath: Path): List<BenchmarkMetricOption> =
    runCatching {
        SqliteBenchmarkStore.open(storePath).use { store ->
            store.listMetricKeys().map { metric ->
                BenchmarkMetricOption(
                    key = metric.caseIdentity + "#" + metric.metricName,
                    caseIdentity = metric.caseIdentity,
                    metricName = metric.metricName,
                    unit = metric.unit,
                )
            }
        }
    }.getOrDefault(emptyList())

private fun loadTrend(
    storePath: Path,
    metrics: List<BenchmarkMetricOption>,
    key: String,
): BenchmarkTrend? {
    val option = metrics.firstOrNull { it.key == key } ?: return null
    val points =
        runCatching {
            SqliteBenchmarkStore.open(storePath).use { store ->
                store.metricHistory(option.caseIdentity, option.metricName, TREND_WINDOW)
            }
        }.getOrDefault(emptyList())
    if (points.isEmpty()) return null
    val newest = points.last()
    return BenchmarkTrend(
        metricKey = option.key,
        metricLabel = option.caseIdentity + " · " + option.metricName,
        unit = option.unit,
        points =
            points.map { point ->
                BenchmarkTrendPoint(
                    label = point.buildLabel ?: point.gitCommit?.take(7) ?: point.importedAt.take(10),
                    value = point.value,
                    sampleCount = point.sampleCount,
                    comparable = point.deviceModel == newest.deviceModel && point.apiLevel == newest.apiLevel,
                )
            },
        environmentVaries = points.map { it.deviceModel to it.apiLevel }.distinct().size > 1,
        minimumSampleCount = TREND_CONFIDENCE_MINIMUM_SAMPLES,
        hasLowSamplePoints = points.any { it.sampleCount < TREND_CONFIDENCE_MINIMUM_SAMPLES },
    )
}

private fun loadReports(indexStore: BenchmarkReportIndexStore): List<BenchmarkReportEntry> =
    runCatching { indexStore.load() }.getOrDefault(emptyList())

private fun reportTitle(state: BenchmarkReportsState): String {
    val current = state.current?.build?.versionName ?: state.current?.sourceFile?.fileName?.toString().orEmpty()
    val baseline = state.baseline?.build?.versionName ?: state.baseline?.sourceFile?.fileName?.toString().orEmpty()
    return current + " vs " + baseline
}

private fun openReport(path: String) {
    runCatching { Desktop.getDesktop().open(File(path)) }
}

private fun revealReport(entry: BenchmarkReportEntry) {
    runCatching {
        val parent = File(entry.path).parentFile
        Desktop.getDesktop().open(parent)
    }
}

private fun chooseBenchmarkJson(parent: java.awt.Component, language: UiLanguage): File? =
    chooseOpenFile(
        parent,
        localizedStringResource(Res.string.import_androidx_benchmark_json, language),
        localizedStringResource(Res.string.benchmark_json, language),
        "json",
    )
