package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class PickerMode { HSL, RGB }

/**
 * Inline color editor with HSL (default) / RGB toggle and an always-visible
 * alpha slider. Calls [onChange] live on every drag/hex commit.
 *
 * Each channel slider draws its own horizontal gradient that visually
 * represents the result of moving along it: R/G/B sliders hold the other
 * two channels constant; H scrolls through the rainbow at current S/L; S
 * goes desaturated→saturated; L goes black→current hue→white. Alpha goes
 * fully transparent → fully opaque, against a grey backdrop so the
 * transition is visible.
 *
 * **HSL drift cache.** During HSL interaction we cache the user's
 * *intended* (h, s, l) locally because the round-trip HSL → RGB → HSL is
 * lossy: deriving HSL from the just-emitted Color drifts h/s/l by ±1 every
 * frame, which manifests as "moving the S slider also nudges L and vice
 * versa". The cache is cleared whenever the user touches RGB or hex, since
 * those interactions redefine the canonical color and we want HSL to
 * resync.
 *
 * @param pickerKey stable identifier for the role being edited; used to
 *   reset local state when the editor reopens for a different role.
 */
@Composable
internal fun ColorPicker(
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

    // HSL cache — null until the user starts editing in HSL mode. While set,
    // it overrides the derived HSL so the slider positions match the user's
    // intent rather than drift-affected re-derivation.
    var hslCache by remember(pickerKey) { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    val (derivedH, derivedS, derivedL) = rgbToHsl(r / 255f, g / 255f, b / 255f)
    val displayH = hslCache?.first ?: derivedH
    val displayS = hslCache?.second ?: derivedS
    val displayL = hslCache?.third ?: derivedL

    var hexInput by remember(pickerKey) { mutableStateOf(argb.toHex(includeAlpha = a != 255)) }
    LaunchedEffect(argb) { hexInput = argb.toHex(includeAlpha = a != 255) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Top: large swatch over a checkerboard-ish grey backdrop + hex
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFCCCCCC), CircleShape)
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = { typed ->
                    hexInput = typed
                    parseHex(typed)?.let { parsed ->
                        hslCache = null  // hex redefines color; let HSL re-derive
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

        // Mode toggle: HSL (default) or RGB
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
                // Hue: full rainbow at the current S/L (always shows full
                // saturation so the rainbow is recognizable even when the
                // current color is desaturated).
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
                // Saturation: grey → saturated at current H/L
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
                // Lightness: black → current hue → white
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

        // Alpha — visible against a light grey backdrop so transparency reads
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

/**
 * One labelled channel row built on the stock M3 [Slider] for conventional drag/press
 * behavior and ripple. The default track is swapped for a rounded gradient bar that
 * previews the channel's range; the thumb is a neutral white handle that reads cleanly
 * over any track color. Everything is vector — no bitmap/raster handle artifacts.
 */
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
    val interactionSource = remember { MutableInteractionSource() }
    val trackShape = RoundedCornerShape(8.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(trackShape),
                ) {
                    // Optional: light-grey checker behind alpha track so transparency is visible.
                    if (backgroundChecker) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color(0xFFCCCCCC)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(gradient)
                            .border(1.dp, MaterialTheme.colorScheme.outline, trackShape),
                    )
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = valueLabel.padStart(5, ' '),
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
        )
    }
}

private fun rgb(r: Int, g: Int, b: Int, a: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f)

/** Standard RGB→HSL conversion, all inputs/outputs normalized to 0..1 (H is 0..360). */
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
