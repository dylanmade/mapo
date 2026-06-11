package com.mapo.ui.component.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * HSL color wheel: **hue = angle** around the disc (0° at 3 o'clock, clockwise — matching
 * [Brush.sweepGradient]) and **saturation = distance from center** (neutral-grey core → full
 * saturation at the rim). The current **lightness** lightens/darkens the whole disc (max → white,
 * min → black) and is edited by a separate slider in the dialog (conventional HS-wheel + L split).
 *
 * Hand-drawn on [Canvas] — Material 3 ships no color-wheel primitive. Everything is vector
 * (sweep + radial gradients, stroked rings), so it stays crisp on any hardware-accelerated
 * surface. Drag anywhere on the disc to set hue+saturation live via [onChange].
 */
@Composable
internal fun ColorWheel(
    hue: Float,
    saturation: Float,
    lightness: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val thumbColor = hslToColor(hue, saturation, lightness, 255)
    val discAlpha = alpha.coerceIn(0f, 1f)

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

        // The disc (hue ring + saturation + lightness veil) is drawn into a layer so the picker's
        // alpha applies to the WHOLE disc uniformly (like the S/L slider tracks), while the thumb
        // below stays opaque (like the slider handles). saveLayer composites the layer at discAlpha.
        val canvas = drawContext.canvas
        val layered = discAlpha < 1f
        if (layered) {
            canvas.saveLayer(
                Rect(0f, 0f, size.width, size.height),
                Paint().apply { this.alpha = discAlpha },
            )
        }
        // Hue ring (full saturation at mid-lightness around the circle).
        drawCircle(brush = Brush.sweepGradient(HUE_SWEEP, c), radius = radius, center = c)
        // Saturation: neutral grey of the current lightness at the core, fading to the vivid rim.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(lightness, lightness, lightness, 1f), Color.Transparent),
                center = c,
                radius = radius,
            ),
            radius = radius,
            center = c,
        )
        // Lightness veil: darken toward black below 0.5, lighten toward white above 0.5, so the
        // whole disc tracks the L slider (max lightness → white, min → black).
        when {
            lightness < 0.5f ->
                drawCircle(Color.Black.copy(alpha = 1f - lightness * 2f), radius = radius, center = c)
            lightness > 0.5f ->
                drawCircle(Color.White.copy(alpha = lightness * 2f - 1f), radius = radius, center = c)
        }
        if (layered) canvas.restore()

        // Thumb at (hue angle, saturation radius) — opaque, on top of the (possibly faded) disc.
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

/** Even hue stops 0..360 (first == last so the sweep wraps seamlessly), vivid at L = 0.5. */
private val HUE_SWEEP: List<Color> = (0..360 step 60).map { h ->
    hslToColor(h.toFloat(), 1f, 0.5f, 255)
}
