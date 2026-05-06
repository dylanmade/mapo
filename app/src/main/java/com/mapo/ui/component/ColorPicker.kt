package com.mapo.ui.component

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

private enum class PickerMode { HSL, RGB }

/**
 * Inline color editor with HSL (default) / RGB toggle and an always-visible
 * alpha slider. Calls [onChange] live on every drag/hex commit.
 *
 * Mirrors the picker in the themestudio module so Mapo can edit per-button colors
 * without depending on themestudio (which will be extracted into its own repo later).
 *
 * **HSL drift cache.** During HSL interaction we cache the user's intended (h, s, l)
 * locally because the round-trip HSL → RGB → HSL is lossy: deriving HSL from the
 * just-emitted Color drifts h/s/l by ±1 every frame. The cache clears whenever the
 * user touches RGB or hex, since those redefine the canonical color.
 *
 * @param pickerKey stable identifier for the role being edited; used to reset local
 *   state when the editor reopens for a different role.
 */
@Composable
fun ColorPicker(
    color: Color,
    onChange: (Color) -> Unit,
    onClearOverride: (() -> Unit)?,
    pickerKey: Any,
    modifier: Modifier = Modifier,
) {
    var mode by remember(pickerKey) { mutableStateOf(PickerMode.HSL) }

    val argb = color.toArgb()
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF

    var hslCache by remember(pickerKey) { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    val (derivedH, derivedS, derivedL) = rgbToHsl(r / 255f, g / 255f, b / 255f)
    val displayH = hslCache?.first ?: derivedH
    val displayS = hslCache?.second ?: derivedS
    val displayL = hslCache?.third ?: derivedL

    var hexInput by remember(pickerKey) { mutableStateOf(argb.toHex(includeAlpha = a != 255)) }
    LaunchedEffect(argb) { hexInput = argb.toHex(includeAlpha = a != 255) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color, RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = { typed ->
                    hexInput = typed
                    parseHex(typed)?.let { parsed ->
                        hslCache = null
                        onChange(Color(parsed))
                    }
                },
                label = { Text("Hex", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == PickerMode.HSL,
                onClick = { mode = PickerMode.HSL },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("HSL", fontSize = 11.sp) }
            SegmentedButton(
                selected = mode == PickerMode.RGB,
                onClick = { mode = PickerMode.RGB },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("RGB", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(8.dp))

        when (mode) {
            PickerMode.RGB -> {
                GradientSlider(
                    label = "R",
                    value = r,
                    valueRange = 0..255,
                    gradient = Brush.horizontalGradient(
                        listOf(rgb(0, g, b, a), rgb(255, g, b, a))
                    ),
                    valueLabel = r.toString(),
                    onChange = {
                        hslCache = null
                        onChange(rgb(it, g, b, a))
                    },
                )
                GradientSlider(
                    label = "G",
                    value = g,
                    valueRange = 0..255,
                    gradient = Brush.horizontalGradient(
                        listOf(rgb(r, 0, b, a), rgb(r, 255, b, a))
                    ),
                    valueLabel = g.toString(),
                    onChange = {
                        hslCache = null
                        onChange(rgb(r, it, b, a))
                    },
                )
                GradientSlider(
                    label = "B",
                    value = b,
                    valueRange = 0..255,
                    gradient = Brush.horizontalGradient(
                        listOf(rgb(r, g, 0, a), rgb(r, g, 255, a))
                    ),
                    valueLabel = b.toString(),
                    onChange = {
                        hslCache = null
                        onChange(rgb(r, g, it, a))
                    },
                )
            }
            PickerMode.HSL -> {
                val rainbow = remember {
                    Brush.horizontalGradient(
                        listOf(0, 60, 120, 180, 240, 300, 360).map { h ->
                            hsl(h.toFloat(), 1f, 0.5f, 255)
                        }
                    )
                }
                GradientSlider(
                    label = "H",
                    value = displayH.toInt().coerceIn(0, 360),
                    valueRange = 0..360,
                    gradient = rainbow,
                    valueLabel = "${displayH.toInt()}°",
                    onChange = { newH ->
                        hslCache = Triple(newH.toFloat(), displayS, displayL)
                        onChange(hsl(newH.toFloat(), displayS, displayL, a))
                    },
                )
                GradientSlider(
                    label = "S",
                    value = (displayS * 100f).toInt(),
                    valueRange = 0..100,
                    gradient = Brush.horizontalGradient(
                        listOf(hsl(displayH, 0f, displayL, a), hsl(displayH, 1f, displayL, a))
                    ),
                    valueLabel = "${(displayS * 100f).toInt()}%",
                    onChange = { newS ->
                        val s = newS / 100f
                        hslCache = Triple(displayH, s, displayL)
                        onChange(hsl(displayH, s, displayL, a))
                    },
                )
                GradientSlider(
                    label = "L",
                    value = (displayL * 100f).toInt(),
                    valueRange = 0..100,
                    gradient = Brush.horizontalGradient(
                        listOf(
                            hsl(displayH, displayS, 0f, a),
                            hsl(displayH, displayS, 0.5f, a),
                            hsl(displayH, displayS, 1f, a),
                        )
                    ),
                    valueLabel = "${(displayL * 100f).toInt()}%",
                    onChange = { newL ->
                        val l = newL / 100f
                        hslCache = Triple(displayH, displayS, l)
                        onChange(hsl(displayH, displayS, l, a))
                    },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        GradientSlider(
            label = "A",
            value = a,
            valueRange = 0..255,
            gradient = Brush.horizontalGradient(
                listOf(rgb(r, g, b, 0), rgb(r, g, b, 255))
            ),
            valueLabel = a.toString(),
            backgroundChecker = true,
            onChange = { onChange(rgb(r, g, b, it)) },
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
private fun GradientSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    gradient: Brush,
    valueLabel: String,
    onChange: (Int) -> Unit,
    backgroundChecker: Boolean = false,
) {
    val density = LocalDensity.current
    val thumbDiameter = 22.dp
    val span = (valueRange.last - valueRange.first).coerceAtLeast(1)
    val normalized = ((value - valueRange.first).toFloat() / span).coerceIn(0f, 1f)

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
            if (backgroundChecker) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFCCCCCC))
                )
            }
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
                    .pointerInput(valueRange) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onChange(positionToValue(down.position.x, size.width, valueRange))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                onChange(positionToValue(change.position.x, size.width, valueRange))
                                change.consume()
                            }
                        }
                    },
            )
            val thumbXDp = with(density) {
                (normalized * widthPx - thumbDiameter.toPx() / 2f).toDp()
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
            text = valueLabel.padStart(5, ' '),
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
        )
    }
}

private fun positionToValue(x: Float, widthPx: Int, range: IntRange): Int {
    if (widthPx <= 0) return range.first
    val span = range.last - range.first
    return (range.first + (x / widthPx) * span).toInt().coerceIn(range.first, range.last)
}

private fun rgb(r: Int, g: Int, b: Int, a: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f)

private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f) * 60f
        g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return Triple(h, s, l)
}

private fun hsl(h: Float, s: Float, l: Float, a: Int): Color {
    if (s == 0f) return rgb((l * 255).toInt(), (l * 255).toInt(), (l * 255).toInt(), a)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val hk = ((h % 360f + 360f) % 360f) / 360f
    val rf = hueToRgb(p, q, hk + 1f / 3f)
    val gf = hueToRgb(p, q, hk)
    val bf = hueToRgb(p, q, hk - 1f / 3f)
    return rgb((rf * 255).toInt(), (gf * 255).toInt(), (bf * 255).toInt(), a)
}

private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

private fun Int.toHex(includeAlpha: Boolean): String =
    if (includeAlpha) "#%08X".format(this)
    else "#%06X".format(this and 0xFFFFFF)

private fun parseHex(input: String): Int? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> runCatching { (0xFF000000.toInt()) or s.toInt(16) }.getOrNull()
        8 -> runCatching { s.toLong(16).toInt() }.getOrNull()
        else -> null
    }
}
