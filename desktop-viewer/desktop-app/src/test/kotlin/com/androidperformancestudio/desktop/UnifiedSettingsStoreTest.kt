package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedSettingsStoreTest {
    @Test
    fun `application settings save immediately under stable namespaced keys`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        val settings =
            ApplicationUiSettings(
                theme = ApplicationThemePreference.DARK,
                displayScale = ApplicationDisplayScale.PERCENT_125,
                themeColor = ApplicationThemeColor.ROYAL_PURPLE.color,
                language = ApplicationLanguagePreference.SIMPLIFIED_CHINESE,
                androidSdkPath = "D:/Android/Sdk",
            )

        assertTrue(store.save(settings))

        assertEquals("dark", values["application.theme"])
        assertEquals("125", values["application.displayScale"])
        assertEquals("royal_purple", values["application.themeColor"])
        assertEquals("simplified_chinese", values["application.language"])
        assertEquals("D:/Android/Sdk", values["application.androidSdkPath"])
        assertEquals("true", values["application.navigationRailCollapsed"])
        assertEquals(settings, store.load())
    }

    @Test
    fun `the navigation rail is collapsed by default and remembers an expanded choice`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)

        // A fresh profile has no stored value: the rail starts collapsed.
        assertTrue(store.load().navigationRailCollapsed)

        assertTrue(store.save(ApplicationUiSettings(navigationRailCollapsed = false)))
        assertEquals("false", values["application.navigationRailCollapsed"])
        assertFalse(store.load().navigationRailCollapsed)

        assertTrue(store.save(ApplicationUiSettings(navigationRailCollapsed = true)))
        assertTrue(store.load().navigationRailCollapsed)
    }

    @Test
    fun `display scale preferences preserve supported percentages and fall back safely`() {
        assertEquals(ApplicationDisplayScale.PERCENT_125, ApplicationDisplayScale.parse("125"))
        assertEquals(ApplicationDisplayScale.PERCENT_100, ApplicationDisplayScale.parse("unsupported"))
    }

    @Test
    fun `theme color preferences preserve the requested palette and fall back safely`() {
        assertEquals(Color(0xFFD4042D), ApplicationThemeColor.BANANA_RED.color)
        assertEquals(Color(0xFFDB7A0E), ApplicationThemeColor.WARM_SUN_ORANGE.color)
        assertEquals(Color(0xFF5A92E5), ApplicationThemeColor.CORNFLOWER_BLUE.color)
        assertEquals(Color(0xFF5E8034), ApplicationThemeColor.JADE_GREEN.color)
        assertEquals(Color(0xFFEB6D98), ApplicationThemeColor.MERLOT_PINK.color)
        assertEquals(Color(0xFF41B5C2), ApplicationThemeColor.AZURE.color)
        assertEquals(Color(0xFFFACA2E), ApplicationThemeColor.LEMON_YELLOW.color)
        assertEquals(Color(0xFF722169), ApplicationThemeColor.ROYAL_PURPLE.color)
        // Presets keep their legacy keys; custom colours parse from hex and fall back safely.
        assertEquals(ApplicationThemeColor.ROYAL_PURPLE.color, parseThemeColor("royal_purple"))
        assertEquals(DEFAULT_THEME_COLOR, parseThemeColor("missing"))
        assertEquals(Color(0xFF5A92E5), parseThemeColor("#5A92E5"))
        assertEquals(Color(0xFF5A92E5), parseThemeColor("5a92e5"))
        assertEquals(Color(0xFFFF0000), parseThemeColor("#F00"))
        assertEquals(Color(0x80FF0000), parseThemeColor("#80FF0000"))
        assertEquals("royal_purple", themeColorStorageValue(ApplicationThemeColor.ROYAL_PURPLE.color))
        assertEquals("#123456", themeColorStorageValue(Color(0xFF123456)))
    }

    @Test
    fun `custom theme colours round trip and legacy preset keys still load`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        val custom = Color(0xFF12AB34)

        assertTrue(store.save(ApplicationUiSettings(themeColor = custom)))
        assertEquals("#12AB34", values["application.themeColor"])
        assertEquals(custom, store.load().themeColor)

        // Older profiles stored the preset key instead of a hex value.
        values["application.themeColor"] = "royal_purple"
        assertEquals(ApplicationThemeColor.ROYAL_PURPLE.color, store.load().themeColor)
    }

    @Test
    fun `simpleperf preferences persist independently from common theme and language`() {
        val values = mutableMapOf<String, String>()
        val store = SimpleperfPreferencesStore(values::get, values::set)
        val settings =
            SimpleperfUiSettings(
                flameTooltipMode = FlameTooltipMode.FIXED,
                simpleperfEngine = SimpleperfEngine.FIREFOX_PROFILER_LOCAL,
            )

        assertTrue(store.save(settings))

        assertEquals("FIXED", values["simpleperf.tooltipMode"])
        assertEquals("FIREFOX_PROFILER_LOCAL", values["simpleperf.engine"])
        assertEquals(settings.flameTooltipMode, store.load().flameTooltipMode)
        assertEquals(settings.simpleperfEngine, store.load().simpleperfEngine)
    }

    @Test
    fun `write failures are reported instead of crashing or silently succeeding`() {
        val applicationStore =
            ApplicationUiSettingsStore(
                readValue = { null },
                writeValue = { _, _ -> error("disk") },
            )
        val simpleperfStore =
            SimpleperfPreferencesStore(
                readValue = { null },
                writeValue = { _, _ -> error("disk") },
            )

        assertFalse(applicationStore.save(ApplicationUiSettings()))
        assertFalse(simpleperfStore.save(SimpleperfUiSettings()))
    }

    @Test
    fun `settings requests retain the requested destination`() {
        val request = SettingsRequest(SettingsPage.SIMPLEPERF, requestId = 7L)

        assertTrue(shouldOpenSettingsForRequest(request))
        assertEquals(SettingsPage.SIMPLEPERF, request.page)
        assertFalse(shouldOpenSettingsForRequest(null))
    }
}
