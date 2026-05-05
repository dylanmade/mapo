package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline RGB+hex color editor. Calls [onChange] live as the user drags any
 * slider or commits a hex value, so the surrounding theme updates in real
 * time.
 *
 * Each channel slider draws its own horizontal gradient (R: black→red, G:
 * black→green, B: black→blue, holding the other two channels at their
 * current values) so the slider visually shows what color the result will
 * become at any position. This avoids the Material [androidx.compose.material3.Slider]
 * inheriting `colorScheme.primary` — which is exactly the color the user is
 * usually editing, making all three sliders look identical.
 */
@Composable
internal fun ColorPicker(
    color: Color,
    onChange: (Color) -> Unit,
    onClearOverride: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF

    var hexInput by remember(argb) { mutableStateOf(argb.toHexNoAlpha()) }
    LaunchedEffect(argb) { hexInput = argb.toHexNoAlpha() }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(color, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = { typed ->
                    hexInput = typed
                    parseHex(typed)?.let { parsed -> onChange(Color(parsed)) }
                },
                label = { Text("Hex", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        GradientChannelSlider(
            label = "R",
            value = r,
            leftColor = Color(0, g, b),
            rightColor = Color(255, g, b),
            onChange = { onChange(Color(it, g, b)) },
        )
        GradientChannelSlider(
            label = "G",
            value = g,
            leftColor = Color(r, 0, b),
            rightColor = Color(r, 255, b),
            onChange = { onChange(Color(r, it, b)) },
        )
        GradientChannelSlider(
            label = "B",
            value = b,
            leftColor = Color(r, g, 0),
            rightColor = Color(r, g, 255),
            onChange = { onChange(Color(r, g, it)) },
        )
        if (onClearOverride != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClearOverride) { Text("Clear override") }
            }
        }
    }
}

@Composable
private fun GradientChannelSlider(
    label: String,
    value: Int,
    leftColor: Color,
    rightColor: Color,
    onChange: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val thumbDiameter = 22.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(8.dp))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val gradient = Brush.horizontalGradient(listOf(leftColor, rightColor))
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(10.dp))
                    .background(gradient)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp),
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onChange(positionToValue(down.position.x, size.width))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                onChange(positionToValue(change.position.x, size.width))
                                change.consume()
                            }
                        }
                    },
            )
            // Thumb
            val thumbXDp = with(density) {
                ((value / 255f) * widthPx - thumbDiameter.toPx() / 2f).toDp()
            }
            Box(
                modifier = Modifier
                    .offset(x = thumbXDp)
                    .align(Alignment.CenterStart)
                    .size(thumbDiameter)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFF333333), CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.toString().padStart(3, ' '),
            fontSize = 12.sp,
            modifier = Modifier.width(32.dp),
        )
    }
}

private fun positionToValue(x: Float, widthPx: Int): Int {
    if (widthPx <= 0) return 0
    return ((x / widthPx) * 255f).toInt().coerceIn(0, 255)
}

private fun Color(r: Int, g: Int, b: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)

private fun Int.toHexNoAlpha(): String =
    "#%06X".format(this and 0xFFFFFF)

private fun parseHex(input: String): Int? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> runCatching { (0xFF000000.toInt()) or s.toInt(16) }.getOrNull()
        8 -> runCatching { s.toLong(16).toInt() }.getOrNull()
        else -> null
    }
}
