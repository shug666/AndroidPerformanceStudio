package com.androidperformancestudio.platform.perfetto

public data class PerfettoCaptureDocument(
    val durationMillis: Long,
    val bufferSizeKb: Int,
    val dataSources: List<PerfettoDataSource>,
    val flushPeriodMillis: Long? = null,
    val fillPolicy: String = DEFAULT_FILL_POLICY,
) {
    init {
        require(durationMillis > 0) { "durationMillis must be positive" }
        require(bufferSizeKb >= MINIMUM_BUFFER_SIZE_KB) { "bufferSizeKb must be at least $MINIMUM_BUFFER_SIZE_KB" }
        require(dataSources.isNotEmpty()) { "at least one feature-owned data source is required" }
        require(flushPeriodMillis == null || flushPeriodMillis > 0) { "flushPeriodMillis must be positive" }
        require(fillPolicy in SUPPORTED_FILL_POLICIES) {
            "fillPolicy must be one of $SUPPORTED_FILL_POLICIES, was '$fillPolicy'"
        }
    }

    public companion object {
        public const val DEFAULT_FILL_POLICY: String = "RING_BUFFER"
        private val SUPPORTED_FILL_POLICIES: Set<String> = setOf("RING_BUFFER", "DISCARD")
        public const val MINIMUM_BUFFER_SIZE_KB: Int = 1_024
    }
}

public data class PerfettoDataSource(
    val name: String,
    val config: String = "",
) {
    init {
        require(NAME.matches(name)) { "data source name must be a Perfetto identifier" }
    }

    private companion object {
        private val NAME: Regex = Regex("[A-Za-z0-9_.-]+")
    }
}

/** Serializes only the data sources requested by a feature adapter. */
public object PerfettoConfigComposer {
    public fun compose(document: PerfettoCaptureDocument): String =
        buildString {
            appendLine("buffers: {")
            appendLine("  size_kb: ${document.bufferSizeKb}")
            appendLine("  fill_policy: ${document.fillPolicy}")
            appendLine("}")
            appendLine("duration_ms: ${document.durationMillis}")
            document.flushPeriodMillis?.let { appendLine("flush_period_ms: $it") }
            document.dataSources.forEach { source -> appendDataSource(source) }
        }

    private fun StringBuilder.appendDataSource(source: PerfettoDataSource) {
        appendLine("data_sources: {")
        appendLine("  config {")
        appendLine("    name: \"${source.name}\"")
        source.config.lineSequence().forEach { line -> appendLine("    $line") }
        appendLine("  }")
        appendLine("}")
    }
}
