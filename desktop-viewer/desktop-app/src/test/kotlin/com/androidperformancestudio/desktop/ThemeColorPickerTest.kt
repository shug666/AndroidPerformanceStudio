package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeColorPickerTest {
    @Test
    fun `hsv conversion keeps the ranges Compose requires`() {
        val samples =
            listOf(
                Color.White,
                Color.Black,
                Color(0xFFFF0000),
                Color(0xFF00FF00),
                Color(0xFF0000FF),
                Color(0xFF5A92E5),
                Color(0xFF808080),
            )

        samples.forEach { color ->
            val hsv = color.toHsvColor()
            assertTrue(hsv.hue in 0f..360f, "hue out of range for $color: ${hsv.hue}")
            assertTrue(hsv.saturation in 0f..1f, "saturation out of range for $color")
            assertTrue(hsv.value in 0f..1f, "value out of range for $color")
        }
    }

    @Test
    fun `rebuilding a colour from its hsv stays on the same colour`() {
        listOf(Color(0xFF5A92E5), Color(0xFFD4042D), Color(0xFF722169)).forEach { source ->
            val hsv = source.toHsvColor()
            val rebuilt = Color.hsv(hsv.hue, hsv.saturation, hsv.value)
            assertEquals(source.red, rebuilt.red, 0.01f)
            assertEquals(source.green, rebuilt.green, 0.01f)
            assertEquals(source.blue, rebuilt.blue, 0.01f)
        }
    }
}
