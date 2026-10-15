package com.androidperformancestudio.battery.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryProfilerLocalizationSourceTest {
    @Test
    fun `empty pane uses the selected application language`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt"),
            )
        val emptyPane =
            source.substring(
                source.indexOf("private fun EmptyPane("),
                source.indexOf("private fun ResultsPane("),
            )
        assertTrue(
            Regex(
                """localizedStringResource\(\s*Res\.string\.energy_evidence,\s*language,?\s*\)""",
            ).containsMatchIn(emptyPane),
        )
        assertTrue(emptyPane.contains("Res.string.no_delta_or_unavailable, language"))
        assertFalse(emptyPane.contains("Res.string.battery_energy_profiler"))
        assertFalse(emptyPane.contains("stringResource("))
    }

    @Test
    fun `chinese resources translate the empty evidence state`() {
        val resources =
            Files.readString(
                Path.of("src/main/composeResources/values-zh/strings.xml"),
            )

        assertTrue(resources.contains("""<string name="energy_evidence">能耗证据</string>"""))
        assertTrue(resources.contains("""<string name="no_delta_or_unavailable">无增量或设备未提供</string>"""))
    }
}
