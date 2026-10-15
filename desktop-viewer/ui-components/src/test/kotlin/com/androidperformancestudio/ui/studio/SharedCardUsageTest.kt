package com.androidperformancestudio.ui.studio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SharedCardUsageTest {
    @Test
    fun `desktop production cards use the shared surface instead of Material cards`() {
        val materialCardImport = Regex("(?m)^import androidx\\.compose\\.material3\\.(?:Card|ElevatedCard|OutlinedCard)(?: as \\w+)?$")
        val materialCardCall = Regex("\\b(?:androidx\\.compose\\.material3\\.)?(?:Card|ElevatedCard|OutlinedCard)\\s*\\(")
        val violations =
            Files.walk(Path.of("..")).use { paths ->
                paths
                    .filter { path ->
                        path.toString().endsWith(".kt") &&
                            "/src/main/" in path.toString() &&
                            "/build/" !in path.toString()
                    }.filter { path ->
                        val source = Files.readString(path)
                        materialCardImport.containsMatchIn(source) || materialCardCall.containsMatchIn(source)
                    }.toList()
            }

        assertTrue(violations.isEmpty(), "Migrate these card surfaces to StudioCard: $violations")
    }

    @Test
    fun `semantic panel and metric controls delegate to the shared card surface`() {
        val root = Path.of("src/main/kotlin/com/androidperformancestudio/ui")
        val panels = Files.readString(root.resolve("studio/StudioPanel.kt"))
        val panelBody = panels.substringAfter("public fun StudioPanel(").substringBefore("public fun StudioPanelHeader(")
        val metricBody = panels.substringAfter("public fun StudioMetricCard(").substringBefore("public fun StudioDataTable(")
        val profilerMetric = Files.readString(root.resolve("ProfilerMetricCard.kt"))

        assertTrue(panelBody.contains("StudioCard("))
        assertTrue(metricBody.contains("StudioCard("))
        assertTrue(profilerMetric.contains("StudioCard("))
    }
}
