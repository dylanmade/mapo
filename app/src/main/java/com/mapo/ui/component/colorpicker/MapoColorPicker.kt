package com.mapo.ui.component.colorpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mapo.ui.compact.CompactDensity
import com.mapo.ui.compact.CompactFieldSize
import com.mapo.ui.compact.CompactTextField
import kotlin.math.roundToInt

/** The numeric/preset control sets shown to the right of the always-present wheel. */
private enum class PickerMode(val label: String) {
    RGB("RGB"),
    HSL("HSL"),
    COLORS("Colors"),
}

/** Light-grey baseplate shown behind translucent colors so alpha reads. */
private val CheckerBase = Color(0xFFCCCCCC)

/**
 * The canonical Mapo color picker, presented as a conventional M3 dialog. Layout:
 *
 *   [ Title ............ ( RGB | HSL | Colors ) ]   ← header (sticky)
 *   [  hue/sat WHEEL    │   …channel controls…   ]   ← center: wheel left, controls right
 *   [  hex + copy ........... Cancel    Save     ]   ← footer (sticky)
 *
 * Built on [BasicAlertDialog] + [Surface] (M3 dialog tokens) so the two-column body and the
 * sticky header/footer are expressible. This is the **only** entry point — the picker is never
 * embedded inline; callers spawn it on tap (typically from a [ColorPickerButton] in a list row).
 *
 * **HSL cache.** While the user works the wheel/HSL controls we hold their intended
 * `[h, s, l]` locally, because re-deriving HSL from the just-emitted RGB color drifts by ±1
 * each frame. The cache clears the moment an RGB/hex/swatch edit redefines the color.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    var hslCache by remember { mutableStateOf<FloatArray?>(null) }

    val displayHsl = hslCache ?: selected.toHsl()
    val alphaInt = (selected.alpha * 255f).roundToInt()

    // Emit from HSL-family controls (wheel + HSL sliders): cache the intent, derive the color.
    fun emitHsl(h: Float, s: Float, l: Float) {
        hslCache = floatArrayOf(h, s, l)
        selected = hslToColor(h, s, l, alphaInt)
    }
    // Emit a concrete color (RGB sliders, hex, swatch): the cache no longer applies.
    fun emitColor(c: Color) {
        hslCache = null
        selected = c
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    // Locked modal height (estimated for the 4:3 RGB/HSL page) so the dialog is a constant size
    // the way its width is — RGB/HSL fill it; the taller Colors tab scrolls within it. Capped to
    // the screen so it can't overflow a short display. Tune this constant to taste.
    val lockedHeight = 380.dp

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        // Fixed Surface width so the modal is the SAME width on the 4:3 bottom screen and the
        // wider 16:9 top screen — it no longer stretches to fill 16:9. Order matters: the cap
        // (widthIn) must precede fillMaxWidth, otherwise fillMaxWidth forces the node to full
        // screen width and the cap only constrains the inner child. ~384dp ≈ the 4:3 screen minus
        // its 24dp side margins; narrower screens fill gracefully (the cap simply doesn't bind).
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 450.dp)
            .fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.height(lockedHeight.coerceAtMost(maxHeight))) {
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
                    // M3 expressive single-select connected button group.
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        PickerMode.entries.forEachIndexed { i, m ->
                            ToggleButton(
                                checked = mode == m,
                                onCheckedChange = { mode = m },
                                shapes = when (i) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    PickerMode.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                modifier = Modifier.semantics { role = Role.RadioButton },
                            ) { Text(m.label, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                HorizontalDivider()

                // ── Center: RGB/HSL centered in the locked area; Colors fills + scrolls ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (mode) {
                        // Colors: swatch sections (Recent / Theme / Common) fill + scroll.
                        PickerMode.COLORS -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ColorsContent(selected, ::emitColor)
                        }
                        // RGB / HSL: wheel on the left, slider controls on the right — the pair
                        // vertically centered in the locked center area.
                        else -> Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                ColorWheel(
                                    hue = displayHsl[0],
                                    saturation = displayHsl[1],
                                    lightness = displayHsl[2],
                                    onChange = { h, s -> emitHsl(h, s, displayHsl[2]) },
                                    // Capped below the narrowest screen's column width so the wheel
                                    // is the SAME size on the 4:3 and 16:9 screens (no aspect-ratio
                                    // scaling, no overflow → no center scrolling on the short 16:9).
                                    modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth(),
                                    alpha = selected.alpha,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (mode == PickerMode.RGB) {
                                    RgbContent(selected, alphaInt, ::emitColor)
                                } else {
                                    HslContent(displayHsl, alphaInt, ::emitHsl)
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
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            ColorPickerRecents.add(selected)
                            onConfirm(selected)
                        },
                    ) { Text("Save") }
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
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        if (color.alpha < 1f) Box(Modifier.matchParentSize().background(CheckerBase))
        Box(Modifier.matchParentSize().background(color))
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
private fun HslContent(hsl: FloatArray, alpha: Int, onHsl: (Float, Float, Float) -> Unit) {
    val h = hsl[0]; val s = hsl[1]; val l = hsl[2]
    val rainbow = remember(alpha) {
        Brush.horizontalGradient((0..360 step 60).map { hslToColor(it.toFloat(), 1f, 0.5f, alpha) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ChannelSlider("H", h.roundToInt(), 0..360, "${h.roundToInt()}°", rainbow) {
            onHsl(it.toFloat(), s, l)
        }
        ChannelSlider("S", (s * 100f).roundToInt(), 0..100, "${(s * 100f).roundToInt()}%",
            Brush.horizontalGradient(listOf(hslToColor(h, 0f, l, alpha), hslToColor(h, 1f, l, alpha)))) {
            onHsl(h, it / 100f, l)
        }
        // Lightness runs black → vivid → white (3-stop), so max L is always pure white.
        ChannelSlider("L", (l * 100f).roundToInt(), 0..100, "${(l * 100f).roundToInt()}%",
            Brush.horizontalGradient(
                listOf(
                    hslToColor(h, s, 0f, alpha),
                    hslToColor(h, s, 0.5f, alpha),
                    hslToColor(h, s, 1f, alpha),
                ),
            )) {
            onHsl(h, s, it / 100f)
        }
    }
}

/**
 * The "Colors" tab: Photoshop-style square swatches grouped into labelled sections — Recent
 * (process-lifetime picks), Theme (the live M3 colorScheme), and Common (per-hue light→dark
 * ramps). The center area scrolls, so the Common section's many rows are fine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsContent(selected: Color, onColor: (Color) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val themeColors = listOf(
        scheme.primary, scheme.primaryContainer,
        scheme.secondary, scheme.secondaryContainer,
        scheme.tertiary, scheme.tertiaryContainer,
        scheme.error, scheme.errorContainer,
        scheme.surface, scheme.surfaceVariant,
        scheme.outline, scheme.onSurface,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ColorPickerRecents.colors.isNotEmpty()) {
            ColorSection("Recent") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ColorPickerRecents.colors.forEach { Swatch(it, selected, onColor) }
                }
            }
        }
        ColorSection("Theme") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                themeColors.forEach { Swatch(it, selected, onColor) }
            }
        }
        ColorSection("Common") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CommonSwatches.rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { Swatch(it, selected, onColor) }
                    }
                }
            }
        }
    }
}

/** A section header (titleSmall) over its swatch [content]. */
@Composable
private fun ColorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** A square, tappable color chip with a selection ring + check when it matches [selected]. */
@Composable
private fun Swatch(color: Color, selected: Color, onColor: (Color) -> Unit, size: Dp = 28.dp) {
    // Compare RGB only (ignore alpha) so a swatch reads as selected regardless of opacity.
    val isSelected = color.toArgb24() == selected.toArgb24()
    val shape = RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .clickable(onClick = { onColor(color) }, role = Role.Button),
    ) {
        if (color.alpha < 1f) Box(Modifier.matchParentSize().background(CheckerBase))
        Box(Modifier.matchParentSize().background(color))
        if (isSelected) {
            Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.primary, shape))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────────

/** The header's circular preview swatch + hex field (editable) + copy button. */
@Composable
private fun HexField(selected: Color, onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current

    // Decoupled from `selected` so a partial/invalid edit isn't clobbered; a committed (parsed)
    // edit flows through onColor, which re-syncs this via the effect.
    var hexText by remember { mutableStateOf(selected.toHexString()) }
    LaunchedEffect(selected.toArgb()) { hexText = selected.toHexString() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(modifier = Modifier.size(34.dp).clip(CircleShape)) {
            // Checker only when translucent — stacking it behind an opaque color let the grey
            // peek through the circle's anti-aliased edge as a faint ring.
            if (selected.alpha < 1f) Box(Modifier.matchParentSize().background(CheckerBase))
            Box(Modifier.matchParentSize().background(selected))
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
            // Trim the inset on the copy-icon side so it doesn't sit so far from the border.
            contentPadding = PaddingValues(start = 16.dp, top = 7.dp, end = 9.dp, bottom = 8.dp),
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
    val trackHeight = 16.dp
    val handleHeight = CompactDensity.DylansCut.sliderThumbHeight
    // Gap fill matches the dialog surface (surfaceContainerHigh) so the strips flanking the
    // handle read as the M3 track "gaps" around the thumb rather than as opaque blocks.
    val gapColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val handleColor = MaterialTheme.colorScheme.primary

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
            // "Dylan's Cut" line handle (shortened to its 40dp thumb height) with surface-colored
            // strips on each side — full handle height + a touch wider so the gap matches true M3.
            thumb = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(handleHeight).background(gapColor))
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(handleHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(handleColor),
                    )
                    Box(Modifier.width(3.dp).height(handleHeight).background(gapColor))
                }
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .clip(trackShape),
                ) {
                    if (checkerBackdrop) {
                        Box(Modifier.matchParentSize().background(CheckerBase))
                    }
                    Box(Modifier.matchParentSize().background(gradient))
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        // Constant-width readout. Without a reserved width the readout sizes to its glyph
        // count ("5" vs "255" vs "100%"), and because the Slider is weight(1f) it absorbs the
        // difference — so the slider visibly resized as the value changed. Reserve the widest
        // value any channel shows ("360°" / "100%") via invisible sizing siblings (so it adapts
        // to font + scale rather than a brittle dp): the box width is now value-independent, the
        // slider width is static, and every slider shares one right edge.
        Box(contentAlignment = Alignment.CenterEnd) {
            Text("360°", style = MaterialTheme.typography.labelMedium, color = Color.Transparent)
            Text("100%", style = MaterialTheme.typography.labelMedium, color = Color.Transparent)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────────

private fun rgb(r: Int, g: Int, b: Int, a: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f)

/** RGB triple (alpha stripped) — for comparing colors by RGB only. */
private fun Color.toArgb24(): Int = toArgb() and 0x00FFFFFF
