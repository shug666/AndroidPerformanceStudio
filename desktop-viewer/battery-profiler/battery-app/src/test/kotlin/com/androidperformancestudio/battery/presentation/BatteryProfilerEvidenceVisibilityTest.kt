package com.androidperformancestudio.battery.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BatteryProfilerEvidenceVisibilityTest {
    @Test
    fun `modeled energy KPI is rendered only when evidence exists`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt"),
            )
        val energyKpi = source.substringAfter("analysis.networkBytes,")
            .substringBefore("analysis.peakTemperatureCelsius,")

        assertTrue(energyKpi.contains("if (analysis.energyMah.count > 0)"))
        assertTrue(energyKpi.contains("Res.string.modeled_energy"))
    }
}
