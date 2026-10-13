package com.androidperformancestudio.benchmark.export

import com.androidperformancestudio.benchmark.model.ReleaseGateResult
import com.androidperformancestudio.benchmark.model.RegressionReport
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dependency-free PDF writer for the release report.
 *
 * The workspace must be able to hand a release engineer a portable artifact without adding a PDF
 * toolchain to the build, so this writes a minimal PDF 1.4 document (Helvetica, A4) directly. The
 * base-14 fonts only carry WinAnsi, so characters outside ASCII are replaced with '?': the PDF is
 * meant for the evidence table, and the JSON/CSV exports keep the exact text.
 */
public class BenchmarkPdfExporter {
    public fun write(
        report: RegressionReport,
        gate: ReleaseGateResult,
        output: Path,
    ) {
        writeLines(renderReport(report, gate), output)
    }

    /** Report body as plain lines; exposed so tests can assert the content without parsing a PDF. */
    public fun renderReport(
        report: RegressionReport,
        gate: ReleaseGateResult,
    ): List<String> =
        buildList {
            add("Android Performance Studio - Benchmark Regression Report")
            add("")
            add("Baseline run:   " + report.baselineRunId)
            add("Current run:    " + report.currentRunId)
            add("Created at:     " + CREATED_AT_FORMAT.format(report.createdAt))
            add("Gate decision:  " + gate.decision.name + "   (policy v" + gate.policyVersion + ")")
            gate.reasons.forEach { reason -> add("Reason:         " + reason.name) }
            add("Comparisons:    " + report.comparisons.size + "   Regressions: " + report.regressionCount +
                "   Blocking: " + gate.blockingComparisons.size)
            add("")
            add("Compatibility issues")
            if (report.compatibilityIssues.isEmpty()) {
                add("  none")
            } else {
                report.compatibilityIssues.forEach { issue ->
                    add(
                        "  " + issue.field + ": " + (issue.baseline ?: "-") + " -> " + (issue.current ?: "-") +
                            (if (issue.hard) "  [hard]" else ""),
                    )
                }
            }
            add("")
            add(
                pad("case", CASE_WIDTH) + pad("metric", METRIC_WIDTH) + pad("baseline", VALUE_WIDTH) +
                    pad("current", VALUE_WIDTH) + pad("delta %", DELTA_WIDTH) + pad("classification", CLASSIFICATION_WIDTH) +
                    "confidence",
            )
            add("-".repeat(CASE_WIDTH + METRIC_WIDTH + VALUE_WIDTH * 2 + DELTA_WIDTH + CLASSIFICATION_WIDTH + CONFIDENCE_WIDTH))
            report.comparisons.forEach { comparison ->
                add(
                    pad(comparison.caseIdentity, CASE_WIDTH) +
                        pad(comparison.metricName, METRIC_WIDTH) +
                        pad(formatValue(comparison.baselineValue), VALUE_WIDTH) +
                        pad(formatValue(comparison.currentValue), VALUE_WIDTH) +
                        pad(formatPercent(comparison.relativeDeltaPercent), DELTA_WIDTH) +
                        pad(comparison.classification.name, CLASSIFICATION_WIDTH) +
                        comparison.confidence.name,
                )
            }
        }

    private fun writeLines(
        lines: List<String>,
        output: Path,
    ) {
        val pages = lines.chunked(LINES_PER_PAGE).ifEmpty { listOf(emptyList()) }
        val objects = mutableListOf<String>()
        val pageObjectIds = pages.indices.map { PAGE_FIRST_OBJECT_ID + it }
        val fontObjectId = PAGE_FIRST_OBJECT_ID + pages.size
        val contentObjectIds = pages.indices.map { fontObjectId + 1 + it }

        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects +=
            "<< /Type /Pages /Kids [" +
            pageObjectIds.joinToString(" ") { id -> id.toString() + " 0 R" } +
            "] /Count " + pages.size + " >>"
        pages.forEachIndexed { index, _ ->
            objects +=
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT +
                "] /Resources << /Font << /F1 " + fontObjectId + " 0 R >> >> /Contents " + contentObjectIds[index] + " 0 R >>"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        pages.forEach { pageLines ->
            val stream = contentStream(pageLines)
            objects += "<< /Length " + stream.toByteArray(Charsets.ISO_8859_1).size + " >>\nstream\n" + stream + "\nendstream"
        }

        val builder = StringBuilder()
        builder.append("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += builder.length
            builder.append(index + 1).append(" 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefOffset = builder.length
        builder.append("xref\n0 ").append(objects.size + 1).append("\n")
        builder.append("0000000000 65535 f \n")
        offsets.forEach { offset -> builder.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset)) }
        builder.append("trailer\n<< /Size ").append(objects.size + 1).append(" /Root 1 0 R >>\n")
        builder.append("startxref\n").append(xrefOffset).append("\n%%EOF\n")

        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.write(output, builder.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun contentStream(lines: List<String>): String =
        buildString {
            append("BT\n/F1 ").append(FONT_SIZE).append(" Tf\n").append(LINE_HEIGHT).append(" TL\n")
            append("1 0 0 1 ").append(MARGIN).append(' ').append(PAGE_HEIGHT - MARGIN).append(" Tm\n")
            lines.forEach { line ->
                append('(').append(escape(line)).append(") Tj T*\n")
            }
            append("ET")
        }

    private fun escape(value: String): String =
        buildString {
            value.forEach { character ->
                when {
                    character == '\\' -> append("\\\\")
                    character == '(' -> append("\\(")
                    character == ')' -> append("\\)")
                    character.code in 32..126 -> append(character)
                    else -> append('?')
                }
            }
        }

    private fun formatValue(value: Double?): String =
        if (value == null) "-" else String.format(Locale.ROOT, "%.4g", value)

    private fun formatPercent(value: Double?): String =
        if (value == null) "-" else String.format(Locale.ROOT, "%+.2f", value)

    private fun pad(
        value: String,
        width: Int,
    ): String = if (value.length >= width) value.substring(0, width - 1) + " " else value.padEnd(width)

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40
        const val FONT_SIZE = 9
        const val LINE_HEIGHT = 12
        const val LINES_PER_PAGE = 60
        const val PAGE_FIRST_OBJECT_ID = 3
        const val CASE_WIDTH = 34
        const val METRIC_WIDTH = 20
        const val VALUE_WIDTH = 11
        const val DELTA_WIDTH = 9
        const val CLASSIFICATION_WIDTH = 14
        const val CONFIDENCE_WIDTH = 10

        val CREATED_AT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
