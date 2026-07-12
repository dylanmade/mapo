package com.mappo.ui.screen.remap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayNameFor
import com.mappo.ui.glyph.InputGlyphs

/**
 * Compact hand-rolled chrome shared across the simplified Remap Controls screen (group boxes,
 * Gyro/Overlay strip). All metrics live here — never re-derive a private size constant per call
 * site. DELIBERATE M3 DEVIATION: sub-touch-target scale, accepted to fit the 1:1 viewport.
 * (Scaled back up ~20% from the density-experiment sizes once the layout settled — readability
 * pass, 2026-07-12; still deliberately below the 48dp M3 floor.)
 */

/** Compressed body text for the simple view's rows and pills. */
@Composable
internal fun remapMiniTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)

/** Overline treatment (uppercase callers + tracked-out small caps look) for editor headers. */
@Composable
internal fun remapOverlineTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp,
    )

/**
 * Container fill shared by the group input boxes and every pill control (dropdowns, label
 * field, output button): the accent tint composited over the low container plane. One family,
 * one treatment — the pills deliberately match the boxes' attributes (2026-07-12 experiment).
 */
@Composable
internal fun remapBoxContainer(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * The topmost button plane: buttons sitting ON an elevated box/card background (the expanded
 * group editor's dropdowns and output button) use this fill instead of [remapBoxContainer],
 * which would vanish against its own plane.
 */
internal val RemapElevatedContainer = Color(0xFF434A5B)

/**
 * Fill for text-input fields sitting on a box/card plane (e.g. the label field on the
 * expanded editor): a slightly darker "well" than the card it sits on. Deliberately FLAT —
 * no bevel border — because an input is not a button.
 */
@Composable
internal fun remapInputFieldContainer(): Color =
    lerp(remapBoxContainer(), Color.Black, 0.22f)

/** Bevel stroke width for the boxes + pill controls (slightly under the original 1dp). */
internal val RemapBoxStroke = 0.75.dp

/** How far the bevel's highlight/shadow deviate from the base fill — "ever so slightly".
 *  Highlights read hotter than shadows on the dark theme, so they get the lighter touch. */
private const val BevelHighlightStrength = 0.15f
private const val BevelShadowStrength = 0.25f

/** Where along the corner arc the bevel finishes fading: 1−cos(45°) of the radius — the
 *  point where the outline's tangent passes 45° and "top" geometrically becomes "side". */
private const val BevelFadeOfRadius = 0.6f

/**
 * The bevel border on buttons + cards (replaced the old solid accent outline): a very faint
 * thin top highlight and bottom shadow, each the base fill nudged toward white/black, fading
 * to transparent (same hue, zero alpha — not transparent-black, which muddies the fade).
 * The fade completes WITHIN the corner rounding — by the arc's 45° point — so the highlight
 * ends just before the top border becomes the side border; that needs the real component
 * size, hence a [ShaderBrush] with per-size stops rather than fraction-based gradient stops
 * (which overshot the corners on anything taller than a pill).
 */
@Composable
internal fun remapBevelBorder(base: Color, cornerRadius: Dp): BorderStroke {
    val fadePx = with(LocalDensity.current) { (cornerRadius * BevelFadeOfRadius).toPx() }
    return BorderStroke(
        RemapBoxStroke,
        BevelBrush(
            highlight = lerp(base, Color.White, BevelHighlightStrength),
            shadow = lerp(base, Color.Black, BevelShadowStrength),
            fadePx = fadePx,
        ),
    )
}

private class BevelBrush(
    private val highlight: Color,
    private val shadow: Color,
    private val fadePx: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val fade = (fadePx / size.height).coerceIn(0.01f, 0.49f)
        return LinearGradientShader(
            from = Offset.Zero,
            to = Offset(0f, size.height),
            colors = listOf(highlight, highlight.copy(alpha = 0f), shadow.copy(alpha = 0f), shadow),
            colorStops = listOf(0f, fade, 1f - fade, 1f),
        )
    }

    override fun equals(other: Any?): Boolean = other is BevelBrush &&
        other.highlight == highlight && other.shadow == shadow && other.fadePx == fadePx

    override fun hashCode(): Int =
        31 * (31 * highlight.hashCode() + shadow.hashCode()) + fadePx.hashCode()
}

/** Height of the pill dropdowns (mode / overlay pickers). */
internal val RemapPillHeight = 24.dp

/** Icon edge inside the pills. */
internal val RemapPillIconSize = 13.dp

/** Outer tap-target edge of [RemapMiniIconButton] (also its footprint spacer in editor rows). */
internal val RemapIconButtonSize = 24.dp

/** Icon edge inside [RemapMiniIconButton]. */
internal val RemapIconButtonIconSize = 16.dp

/**
 * A hand-rolled miniature pill button, in the shared box treatment. [filled] (the
 * command/output button) keeps its emphasis through the stronger text color only. [elevated]
 * uses the topmost button plane for buttons sitting on a box/card background. Disabled =
 * dimmed + inert.
 */
@Composable
internal fun RemapMiniPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    elevated: Boolean = false,
) {
    val content = if (filled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (elevated) RemapElevatedContainer else remapBoxContainer()
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        border = remapBevelBorder(container, RemapPillHeight / 2),
        modifier = modifier
            .height(RemapPillHeight)
            .then(
                if (enabled) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
                else Modifier.alpha(0.55f),
            ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = text,
                style = remapMiniTextStyle(),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A hand-rolled miniature icon button (cogs etc.) — ripple-clipped circle, no 48dp halo. */
@Composable
internal fun RemapMiniIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(RemapIconButtonSize)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(RemapIconButtonIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The mode-selection pill: current mode glyph + name + dropdown arrow → menu of valid modes.
 *  [overline] renders the text in the overline treatment (uppercase, tracked out) for the
 *  group editor's header. */
@Composable
internal fun ModePillDropdown(
    source: InputSource,
    currentMode: BindingMode,
    validModes: List<BindingMode>,
    enabled: Boolean,
    onPick: (BindingMode) -> Unit,
    overline: Boolean = false,
    elevated: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val container = if (elevated) RemapElevatedContainer else remapBoxContainer()
    Box {
        // Shared box treatment — pill-style dropdown button, no trailing arrow.
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            border = remapBevelBorder(container, RemapPillHeight / 2),
            modifier = Modifier
                .heightIn(min = RemapPillHeight)
                .then(
                    if (enabled) {
                        Modifier.clip(RoundedCornerShape(50))
                            .clickable(onClickLabel = "Change input mode") { open = true }
                    } else Modifier.alpha(0.6f),
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(
                    InputGlyphs.modeIcon(currentMode),
                    contentDescription = null,
                    modifier = Modifier.size(RemapPillIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = currentMode.displayNameFor(source)
                        .let { if (overline) it.uppercase() else it },
                    style = if (overline) remapOverlineTextStyle() else remapMiniTextStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 156.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            validModes.forEach { mode ->
                DropdownMenuItem(
                    leadingIcon = { Icon(InputGlyphs.modeIcon(mode), contentDescription = null) },
                    text = { Text(mode.displayNameFor(source)) },
                    trailingIcon = if (mode == currentMode) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = { open = false; if (mode != currentMode) onPick(mode) },
                )
            }
        }
    }
}
