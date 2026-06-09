package com.mapo.ui.component.colorpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mapo.ui.compact.CompactDensity
import com.mapo.ui.compact.CompactFieldSize
import com.mapo.ui.compact.CompactTextField
import kotlin.math.roundToInt

/** The numeric/preset control sets shown to the right of the always-present wheel. */
private enum class PickerMode(val label: String) {
    RGB("RGB"),
    HSV("HSV"),
    THEME("Theme"),
}

/** Light-grey baseplate shown behind translucent colors so alpha reads. */
private val CheckerBase = Color(0xFFCCCCCC)

/**
 * The canonical Mapo color picker, presented as a conventional M3 dialog. Layout:
 *
 *   [ Title ..................... ◔ #RRGGBB [copy] ]   ← header
 *   [  hue/sat WHEEL   │  ( RGB | HSV | Theme )     ]   ← body: wheel left, controls right
 *   [  brightness ───  │  …channel controls…        ]
 *   [ A ─────────────────────────────────────────── ]  ← alpha (full width)
 *   [                              Cancel   Select   ]  ← actions
 *
 * Built on [BasicAlertDialog] + [Surface] (M3 dialog tokens) so the two-column body and the
 * hex-in-header layout are expressible. This is the **only** entry point — the picker is never
 * embedded inline; callers spawn it on tap (typically from a [ColorPickerButton] in a list row).
 *
 * **HSV cache.** While the user works the wheel/HSV controls we hold their intended
 * `[h, s, v]` locally, because re-deriving HSV from the just-emitted RGB color drifts by ±1
 * each frame. The cache clears the moment an RGB/hex/theme edit redefines the color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapoColorPickerDialog(
    initialColor: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    supportAlpha: Boolean = true,
) {
    var mode by remember { mutableStateOf(PickerMode.RGB) }
    var selected by remember { mutableStateOf(initialColor) }
    var hsvCache by remember { mutableStateOf<FloatArray?>(null) }

    val displayHsv = hsvCache ?: selected.toHsv()
    val alphaInt = (selected.alpha * 255f).roundToInt()

    // Emit from HSV-family controls (wheel + HSV sliders): cache the intent, derive the color.
    fun emitHsv(h: Float, s: Float, v: Float) {
        hsvCache = floatArrayOf(h, s, v)
        selected = hsvToColor(h, s, v, alphaInt)
    }
    // Emit a concrete color (RGB sliders, hex, theme swatch): the cache no longer applies.
    fun emitColor(c: Color) {
        hsvCache = null
        selected = c
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .padding(24.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.heightIn(max = maxHeight)) {
                // ── Header (sticky): title + mode switcher ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    SingleChoiceSegmentedButtonRow {
                        PickerMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { mode = m },
                                shape = SegmentedButtonDefaults.itemShape(i, PickerMode.entries.size),
                            ) { Text(m.label, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                HorizontalDivider()

                // ── Center (scrolls only if it must): driven by the selected mode ──
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    when (mode) {
                        // Theme: labeled swatches fill the whole center; no wheel needed.
                        PickerMode.THEME -> ThemeContent(selected, ::emitColor)
                        // RGB / HSV: wheel on the left, slider controls on the right — each
                        // centered in its column, and the two columns centered against each other.
                        else -> Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                ColorWheel(
                                    hue = displayHsv[0],
                                    saturation = displayHsv[1],
                                    value = displayHsv[2],
                                    onChange = { h, s -> emitHsv(h, s, displayHsv[2]) },
                                    modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (mode == PickerMode.RGB) {
                                    RgbContent(selected, alphaInt, ::emitColor)
                                } else {
                                    HsvContent(displayHsv, alphaInt, ::emitHsv)
                                }
                                if (supportAlpha) {
                                    val r = (selected.red * 255f).roundToInt()
                                    val g = (selected.green * 255f).roundToInt()
                                    val b = (selected.blue * 255f).roundToInt()
                                    ChannelSlider(
                                        label = "A",
                                        value = alphaInt,
                                        valueRange = 0..255,
                                        valueText = alphaInt.toString(),
                                        gradient = Brush.horizontalGradient(
                                            listOf(rgb(r, g, b, 0), rgb(r, g, b, 255)),
                                        ),
                                        checkerBackdrop = true,
                                        onChange = { selected = selected.copy(alpha = it / 255f) },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                // ── Footer (sticky): hex + copy on the left, actions on the right ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HexField(
                        selected = selected,
                        onColor = ::emitColor,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
                }
            }
        }
    }
}

/**
 * A circular swatch that reflects [color] and launches the picker on tap. The standard
 * launcher affordance — drop it in a list row's trailing slot (and make the row clickable to
 * the same [onClick]). A grey baseplate sits behind the color so translucent picks read.
 */
@Composable
fun ColorPickerButton(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        Box(Modifier.matchParentSize().background(CheckerBase))
        Box(Modifier.matchParentSize().background(color))
        Box(Modifier.matchParentSize().border(1.dp, outline, CircleShape))
    }
}

// ── Mode content ────────────────────────────────────────────────────────────────

@Composable
private fun RgbContent(color: Color, alpha: Int, onColor: (Color) -> Unit) {
    val r = (color.red * 255f).roundToInt()
    val g = (color.green * 255f).roundToInt()
    val b = (color.blue * 255f).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ChannelSlider("R", r, 0..255, r.toString(),
            Brush.horizontalGradient(listOf(rgb(0, g, b, alpha), rgb(255, g, b, alpha)))) {
            onColor(rgb(it, g, b, alpha))
        }
        ChannelSlider("G", g, 0..255, g.toString(),
            Brush.horizontalGradient(listOf(rgb(r, 0, b, alpha), rgb(r, 255, b, alpha)))) {
            onColor(rgb(r, it, b, alpha))
        }
        ChannelSlider("B", b, 0..255, b.toString(),
            Brush.horizontalGradient(listOf(rgb(r, g, 0, alpha), rgb(r, g, 255, alpha)))) {
            onColor(rgb(r, g, it, alpha))
        }
    }
}

@Composable
private fun HsvContent(hsv: FloatArray, alpha: Int, onHsv: (Float, Float, Float) -> Unit) {
    val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
    val rainbow = remember {
        Brush.horizontalGradient((0..360 step 60).map { hsvToColor(it.toFloat(), 1f, 1f, 255) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ChannelSlider("H", h.roundToInt(), 0..360, "${h.roundToInt()}°", rainbow) {
            onHsv(it.toFloat(), s, v)
        }
        ChannelSlider("S", (s * 100f).roundToInt(), 0..100, "${(s * 100f).roundToInt()}%",
            Brush.horizontalGradient(listOf(hsvToColor(h, 0f, v, alpha), hsvToColor(h, 1f, v, alpha)))) {
            onHsv(h, it / 100f, v)
        }
        ChannelSlider("V", (v * 100f).roundToInt(), 0..100, "${(v * 100f).roundToInt()}%",
            Brush.horizontalGradient(listOf(hsvToColor(h, s, 0f, alpha), hsvToColor(h, s, 1f, alpha)))) {
            onHsv(h, s, it / 100f)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeContent(selected: Color, onColor: (Color) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val swatches = listOf(
        "Primary" to scheme.primary,
        "Primary\ncontainer" to scheme.primaryContainer,
        "Secondary" to scheme.secondary,
        "Secondary\ncontainer" to scheme.secondaryContainer,
        "Tertiary" to scheme.tertiary,
        "Tertiary\ncontainer" to scheme.tertiaryContainer,
        "Error" to scheme.error,
        "Error\ncontainer" to scheme.errorContainer,
        "Surface" to scheme.surface,
        "Surface\nvariant" to scheme.surfaceVariant,
        "Outline" to scheme.outline,
        "On surface" to scheme.onSurface,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        swatches.forEach { (name, color) ->
            // Compare RGB only (ignore alpha) so a swatch reads as selected regardless of opacity.
            val isSelected = color.toArgb24() == selected.toArgb24()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) scheme.primary else scheme.outline,
                            shape = CircleShape,
                        )
                        .clickable(onClick = { onColor(color) }, role = Role.Button),
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────────

/** The header's circular preview swatch + hex field (editable) + copy button. */
@Composable
private fun HexField(selected: Color, onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val outline = MaterialTheme.colorScheme.outline

    // Decoupled from `selected` so a partial/invalid edit isn't clobbered; a committed (parsed)
    // edit flows through onColor, which re-syncs this via the effect.
    var hexText by remember { mutableStateOf(selected.toHexString()) }
    LaunchedEffect(selected.toArgb()) { hexText = selected.toHexString() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape)) {
            Box(Modifier.matchParentSize().background(CheckerBase))
            Box(Modifier.matchParentSize().background(selected))
            Box(Modifier.matchParentSize().border(1.dp, outline, CircleShape))
        }
        Spacer(Modifier.width(16.dp))
        CompactTextField(
            value = hexText,
            onValueChange = { typed ->
                hexText = typed
                parseHexColor(typed)?.let(onColor)
            },
            size = CompactFieldSize.Slim,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            clipboard.setText(AnnotatedString(selected.toHexString()))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy hex",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One labelled channel row: a native M3 [Slider] (stock thumb, so it stays crisp) with its
 * track swapped for a rounded gradient bar that previews the channel's range.
 */
@Composable
private fun ChannelSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    valueText: String,
    gradient: Brush,
    checkerBackdrop: Boolean = false,
    onChange: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val trackShape = RoundedCornerShape(8.dp)
    val outline = MaterialTheme.colorScheme.outline

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
            // "Dylan's Cut" variant: the stock M3 line handle, shortened to its 40dp thumb
            // height so the slider stack packs tighter vertically. Fully vector, crisp.
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(4.dp, CompactDensity.DylansCut.sliderThumbHeight),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(trackShape),
                ) {
                    if (checkerBackdrop) {
                        Box(Modifier.matchParentSize().background(CheckerBase))
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(gradient)
                            .border(1.dp, outline, trackShape),
                    )
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        // Wrap-content (no fixed width) so the weighted Slider expands right up to the readout —
        // no dead space between the track's right edge and the digits.
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
        )
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────────

private fun rgb(r: Int, g: Int, b: Int, a: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f)

/** RGB triple (alpha stripped) — for comparing colors by RGB only. */
private fun Color.toArgb24(): Int = toArgb() and 0x00FFFFFF
