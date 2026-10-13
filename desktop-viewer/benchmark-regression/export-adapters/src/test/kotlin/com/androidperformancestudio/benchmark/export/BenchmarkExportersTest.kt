package com.androidperformancestudio.benchmark.export

import com.androidperformancestudio.benchmark.model.*
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkExportersTest {
    @Test
    fun `exports junit and sarif regressions`() {
        val report =
            RegressionReport(
                "b",
                "c",
                Instant.now(),
                listOf(
                    MetricComparison(
                        "Bench#test",
                        "timeMs",
                        "ms",
                        1.0,
                        2.0,
                        1.0,
                        100.0,
                        RegressionClassification.REGRESSED,
                        EvidenceConfidence.EXACT,
                        emptyList(),
                    ),
                ),
                emptyList(),
            )
        val dir = createTempDirectory()
        val junit = dir.resolve("out.xml")
        val sarif = dir.resolve("out.sarif")
        BenchmarkReportExporter().apply {
            writeJunit(report, junit)
            writeSarif(report, sarif)
        }
        assertTrue(junit.readText().contains("failure"))
        assertTrue(sarif.readText().contains("benchmark-regression"))
    }

    @Test
    fun `release gate policy version and compatibility travel with the release exports`() {
        val report = report()
        val gate = gate()
        val dir = createTempDirectory()
        val csv = dir.resolve("release.csv")
        val json = dir.resolve("release.json")
        BenchmarkReportExporter().apply {
            writeCsv(report, gate, csv)
            writeJson(report, gate, json)
        }

        val csvText = csv.readText()
        assertTrue(csvText.contains("blocking,policy_version"))
        assertTrue(csvText.contains("2026.1"))
        // This gate result has no blocking comparison, so the row is exported as not blocking.
        assertTrue(csvText.contains("false"))

        val jsonText = json.readText()
        assertTrue(jsonText.contains("releaseGate"))
        assertTrue(jsonText.contains("INCONCLUSIVE"))
        assertTrue(jsonText.contains("compatibilityIssues"))
        assertTrue(jsonText.contains("device.apiLevel"))
        assertTrue(jsonText.contains("2026.1"))
    }

    @Test
    fun `the PDF export is a real PDF that carries the gate decision`() {
        val report = report()
        val gate = gate()
        val output = createTempDirectory().resolve("release.pdf")

        BenchmarkPdfExporter().write(report, gate, output)

        val text = output.toFile().readText(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        // Parentheses are escaped inside the PDF text operator, so match the unescaped fragments.
        assertTrue(text.contains("Gate decision:  INCONCLUSIVE"))
        assertTrue(text.contains("policy v2026.1"))
        assertTrue(text.contains("Bench#test"))
        assertTrue(text.contains("%%EOF"))

        val lines = BenchmarkPdfExporter().renderReport(report, gate)
        assertTrue(lines.any { it.contains("Gate decision:  INCONCLUSIVE") })
        assertTrue(lines.any { it.contains("device.apiLevel") })
    }

    @Test
    fun `the report index catalogues exported artifacts without owning them`() {
        val dir = createTempDirectory()
        val artifact = dir.resolve("release.pdf")
        artifact.toFile().writeText("pdf")
        val store = BenchmarkReportIndexStore(dir.resolve("index.json"))

        val entries = store.record(artifact, "v2 vs v1", "PDF")
        assertEquals(1, entries.size)
        assertEquals(artifact.toAbsolutePath().normalize().toString(), entries.single().path)
        assertTrue(entries.single().sizeBytes > 0)
        assertTrue(artifact.toFile().exists())

        // Re-recording the same path replaces the entry instead of duplicating it.
        assertEquals(1, store.record(artifact, "v2 vs v1", "PDF").size)
        assertEquals(1, BenchmarkReportIndexStore(dir.resolve("index.json")).load().size)
    }

    private fun report(): RegressionReport =
        RegressionReport(
            "b",
            "c",
            Instant.now(),
            listOf(
                MetricComparison(
                    "Bench#test",
                    "timeMs",
                    "ms",
                    1.0,
                    2.0,
                    1.0,
                    100.0,
                    RegressionClassification.REGRESSED,
                    EvidenceConfidence.EXACT,
                    listOf("Run environments are incompatible: device.apiLevel"),
                ),
            ),
            listOf(CompatibilityIssue("device.apiLevel", "34", "35", hard = true)),
        )

    private fun gate(): ReleaseGateResult =
        ReleaseGateResult(
            decision = GateDecision.INCONCLUSIVE,
            policyVersion = "2026.1",
            blockingComparisons = emptyList(),
            reasons = listOf(GateReason.INCOMPATIBLE_EVIDENCE),
        )
}
