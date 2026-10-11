package com.androidperformancestudio.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width shared by the saturation/value plane and the hue slider. */
internal val THEME_PICKER_WIDTH: Dp = 240.dp

private val SATURATION_VALUE_HEIGHT: Dp = 140.dp
private val HUE_SLIDER_HEIGHT: Dp = 18.dp
private val THUMB_RADIUS: Dp = 6.dp
private const val FULL_HUE = 360f

/** Hue/saturation/value of a colour, kept separately so dragging never loses the hue. */
internal data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/** Converts to HSV. The result always satisfies the ranges Color.hsv requires. */
internal fun Color.toHsvColor(): HsvColor {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue =
        when {
            delta == 0f -> 0f
            max == red -> 60f * (((green - blue) / delta) % 6f)
            max == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }.let { if (it < 0f) it + FULL_HUE else it }
    return HsvColor(
        hue = hue.coerceIn(0f, FULL_HUE),
        saturation = if (max == 0f) 0f else (delta / max).coerceIn(0f, 1f),
        value = max.coerceIn(0f, 1f),
    )
}

/** Picks a custom accent: a saturation/value plane over a hue slider. */
@Composable
internal fun ThemeColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hsv by remember { mutableStateOf(color.toHsvColor()) }
    // Re-sync when the colour changes from outside the picker (preset swatch, hex field, default).
    LaunchedEffect(color) {
        if (hsv.toColor(color.alpha) != color) hsv = color.toHsvColor()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SaturationValuePlane(
            hsv = hsv,
            onChange = { saturation, value ->
                hsv = hsv.copy(saturation = saturation, value = value)
                onColorChange(hsv.toColor(color.alpha))
            },
        )
        HueSlider(
            hue = hsv.hue,
            onChange = { hue ->
                hsv = hsv.copy(hue = hue)
                onColorChange(hsv.toColor(color.alpha))
            },
        )
    }
}

private fun HsvColor.toColor(alpha: Float): Color =
    Color.hsv(
        hue = hue.coerceIn(0f, FULL_HUE),
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )

@Composable
private fun SaturationValuePlane(hsv: HsvColor, onChange: (Float, Float) -> Unit) {
    Canvas(
        modifier =
            Modifier
                .width(THEME_PICKER_WIDTH)
                .height(SATURATION_VALUE_HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onChange(saturationAt(offset.x, size.width), valueAt(offset.y, size.height))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onChange(saturationAt(offset.x, size.width), valueAt(offset.y, size.height))
                        },
                        onDrag = { change, _ ->
                            onChange(
                                saturationAt(change.position.x, size.width),
                                valueAt(change.position.y, size.height),
                            )
                        },
                    )
                },
    ) {
        val saturation = hsv.saturation.coerceIn(0f, 1f)
        val value = hsv.value.coerceIn(0f, 1f)
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.White, Color.hsv(hsv.hue.coerceIn(0f, FULL_HUE), 1f, 1f)),
            ),
        )
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawThumb(Offset(saturation * size.width, (1f - value) * size.height))
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier =
            Modifier
                .width(THEME_PICKER_WIDTH)
                .height(HUE_SLIDER_HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onChange(hueAt(offset.x, size.width)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> onChange(hueAt(offset.x, size.width)) },
                        onDrag = { change, _ -> onChange(hueAt(change.position.x, size.width)) },
                    )
                },
    ) {
        drawRect(Brush.horizontalGradient(HUE_GRADIENT))
        drawThumb(Offset(hue.coerceIn(0f, FULL_HUE) / FULL_HUE * size.width, size.height / 2f))
    }
}

// A fully saturated hue wheel, wrapped at both ends so the rail reads as a continuum.
private val HUE_GRADIENT: List<Color> = List(13) { index -> Color.hsv(index * 30f, 1f, 1f) }

private fun saturationAt(x: Float, width: Int): Float =
    if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

private fun valueAt(y: Float, height: Int): Float =
    if (height <= 0) 0f else (1f - y / height).coerceIn(0f, 1f)

private fun hueAt(x: Float, width: Int): Float =
    if (width <= 0) 0f else (x / width).coerceIn(0f, 1f) * FULL_HUE

private fun DrawScope.drawThumb(center: Offset) {
    drawCircle(Color.White, radius = THUMB_RADIUS.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    drawCircle(
        Color.Black.copy(alpha = 0.45f),
        radius = (THUMB_RADIUS + 1.dp).toPx(),
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
}
