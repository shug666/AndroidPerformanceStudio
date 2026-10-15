package com.androidperformancestudio.frame.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayRefreshRateReaderTest {
    @Test
    fun `prefers active display refresh rate over supported mode fps values`() {
        val output =
            """
            mRefreshRate=120.0
            modes=[{id=1, fps=60.0}, {id=2, fps=120.0}]
            """.trimIndent()

        assertEquals(setOf(120.0), parseDisplayRefreshRates(output))
    }

    @Test
    fun `parses active render frame rate format from dumpsys display`() {
        val output = "DisplayDeviceInfo{Built-in Screen, renderFrameRate 120.00001, supportedModes []}"

        assertEquals(setOf(120.00001), parseDisplayRefreshRates(output))
    }

    @Test
    fun `ignores supported mode fps when active refresh rate is not reported`() {
        val output = "modes=[{id=1, fps=60.0}, {id=2, fps=90.0}]"

        assertEquals(emptySet(), parseDisplayRefreshRates(output))
    }
}
