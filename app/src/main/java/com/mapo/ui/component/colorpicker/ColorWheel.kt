package com.mapo.ui.component.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * HSV color wheel: **hue = angle** around the disc (0° at 3 o'clock, clockwise — matching
 * [Brush.sweepGradient]) and **saturation = distance from center** (white core → full
 * saturation at the rim). The current **value/brightness** dims the whole disc and is edited
 * by a separate slider in the dialog (conventional HS-wheel + V-slider split).
 *
 * Hand-drawn on [Canvas] — Material 3 ships no color-wheel primitive. Everything is vector
 * (sweep + radial gradients, stroked rings), so it stays crisp on any hardware-accelerated
 * surface. Drag anywhere on the disc to set hue+saturation live via [onChange].
 */
@Composable
internal fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val thumbColor = hsvToColor(hue, saturation, value, 255)

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                fun emit(pos: Offset) {
                    val radius = minOf(size.width, size.height) / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val d = pos - c
                    val sat = (d.getDistance() / radius).coerceIn(0f, 1f)
                    var angle = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
                    if (angle < 0f) angle += 360f
                    onChange(angle, sat)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    emit(down.position)
                    drag(down.id) { change ->
                        emit(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        val c = center

        // Hue ring (full saturation/value around the circle).
        drawCircle(brush = Brush.sweepGradient(HUE_SWEEP, c), radius = radius, center = c)
        // Saturation: white core fading to transparent at the rim.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = c,
                radius = radius,
            ),
            radius = radius,
            center = c,
        )
        // Brightness: a black veil whose opacity tracks (1 - value).
        if (value < 1f) {
            drawCircle(color = Color.Black.copy(alpha = 1f - value), radius = radius, center = c)
        }
        // Rim outline for definition against the dialog surface.
        drawCircle(color = outline, radius = radius, center = c, style = Stroke(width = 1.dp.toPx()))

        // Thumb at (hue angle, saturation radius).
        val rad = Math.toRadians(hue.toDouble())
        val thumb = Offset(
            x = c.x + saturation * radius * cos(rad).toFloat(),
            y = c.y + saturation * radius * sin(rad).toFloat(),
        )
        drawCircle(color = Color.White, radius = 9.dp.toPx(), center = thumb)
        drawCircle(color = thumbColor, radius = 7.dp.toPx(), center = thumb)
        drawCircle(
            color = Color.Black.copy(alpha = 0.45f),
            radius = 9.dp.toPx(),
            center = thumb,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** Even hue stops 0..360 (first == last so the sweep wraps seamlessly). */
private val HUE_SWEEP: List<Color> = (0..360 step 60).map { h ->
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f)))
}
