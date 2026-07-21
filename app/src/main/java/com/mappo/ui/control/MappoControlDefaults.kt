package com.mappo.ui.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mappo's custom control library (`com.mappo.ui.control`): the compact hand-rolled chrome
 * born on the Remap Controls screen, generalized for app-wide use — beveled pill buttons,
 * mini icon buttons, pill dropdowns, the stepper slider, and the shared metrics/colors/
 * motion they speak. DELIBERATE M3 DEVIATION: sub-touch-target scale, accepted for
 * information-dense surfaces (remap screen, overlay edit drawer).
 *
 * Doctrine: reach for these FIRST when building Mappo UI; fall back to stock M3 (or
 * `com.mappo.ui.compact`) only where this library has no fitting component. All metrics
 * live here — never re-derive a private size constant per call site.
 */

/** Compressed body text for mini rows, pills, and control labels. */
@Composable
fun mappoMiniTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)

/** Overline treatment (uppercase callers + tracked-out small caps look) for headers. */
@Composable
fun mappoOverlineTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp,
    )

/**
 * Container fill shared by grouped boxes and every pill control (dropdowns, label fields,
 * buttons): the accent tint composited over the low container plane. One family, one
 * treatment — pills deliberately match their boxes' attributes.
 */
@Composable
fun mappoBoxContainer(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * The topmost button plane: controls sitting ON an elevated box/card background use this
 * fill instead of [mappoBoxContainer], which would vanish against its own plane.
 */
val MappoElevatedContainer = Color(0xFF434A5B)

/**
 * Fill for text-input fields sitting on a box/card plane: a slightly darker "well" than the
 * card it sits on. Deliberately FLAT — no bevel border — because an input is not a button.
 */
@Composable
fun mappoInputFieldContainer(): Color =
    lerp(mappoBoxContainer(), Color.Black, 0.22f)

/** Bevel stroke width for boxes + pill controls (slightly under the original 1dp). */
val MappoBoxStroke = 0.75.dp

/** How far the bevel's highlights deviate from the base fill — "ever so slightly". */
private const val BevelTopHighlightStrength = 0.10f
private const val BevelBottomHighlightStrength = 0.05f

/** Where along the corner arc the bevel finishes fading: 1−cos(45°) of the radius — the
 *  point where the outline's tangent passes 45° and "top" geometrically becomes "side". */
private const val BevelFadeOfRadius = 0.9f

/**
 * The bevel border on buttons + cards (replaced the old solid accent outline): a very faint
 * thin top and bottom highlight, each the base fill nudged toward white, fading
 * to transparent (same hue, zero alpha — not transparent-black, which muddies the fade).
 * The fade completes WITHIN the corner rounding — by the arc's 45° point — so the highlight
 * ends just before the top border becomes the side border; that needs the real component
 * size, hence a [ShaderBrush] with per-size stops rather than fraction-based gradient stops
 * (which overshot the corners on anything taller than a pill).
 */
@Composable
fun mappoBevelBorder(base: Color, cornerRadius: Dp): BorderStroke {
    val fadePx = with(LocalDensity.current) { (cornerRadius * BevelFadeOfRadius).toPx() }
    return BorderStroke(
        MappoBoxStroke,
        BevelBrush(
            topHighlight = lerp(base, Color.White, BevelTopHighlightStrength),
            bottomHighlight = lerp(base, Color.White, BevelBottomHighlightStrength),
            fadePx = fadePx,
        ),
    )
}

// NB: strokes are INNER (Surface `border=` / `Modifier.border`) by deliberate reversion
// (2026-07-13). An outer-stroke experiment (CSS-outline semantics via drawBehind) was tried
// and backed out: everything stateful in Compose — hover/press/focus layers, disabled alpha,
// Surface clipping — operates WITHIN bounds, so outside chrome needed custom parallel handling
// for every state and made borderless fields read smaller than their bordered siblings.

private class BevelBrush(
    private val topHighlight: Color,
    private val bottomHighlight: Color,
    private val fadePx: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val fade = (fadePx / size.height).coerceIn(0.01f, 0.49f)
        return LinearGradientShader(
            from = Offset.Zero,
            to = Offset(0f, size.height),
            colors = listOf(topHighlight, topHighlight.copy(alpha = 0f), bottomHighlight.copy(alpha = 0f), bottomHighlight),
            colorStops = listOf(0f, fade, 1f - fade, 1f),
        )
    }

    override fun equals(other: Any?): Boolean = other is BevelBrush &&
        other.topHighlight == topHighlight && other.bottomHighlight == bottomHighlight && other.fadePx == fadePx

    override fun hashCode(): Int =
        31 * (31 * topHighlight.hashCode() + bottomHighlight.hashCode()) + fadePx.hashCode()
}

/** Height of the pill controls (buttons, dropdowns). */
val MappoPillHeight = 24.dp

/** Width floor for pill dropdowns so short values ("None") don't collapse into a tiny chip. */
val MappoPillMinWidth = 62.dp

/** Icon edge inside the pills. */
val MappoPillIconSize = 13.dp

/** Horizontal content inset shared by every pill control (buttons, dropdowns, label fields). */
val MappoPillContentPadding = 10.dp

/** Gap between a leading glyph and its label (pills, headers, captions). */
val MappoGlyphLabelGap = 5.dp

/** Width cap for a pill dropdown's label before it ellipsizes. */
val MappoPillLabelMaxWidth = 156.dp

/** Optical-centering bias for FIXED-WIDTH, center-arranged pills with a leading icon: total
 *  extra END padding vs START, shifting the icon+label block bias/2 toward the icon. Cancels
 *  the leading icon's built-in live-area padding — Material/Lucide glyphs only ink ~10-11dp
 *  of their 13dp box, so with symmetric padding the left flank measures ~2dp wider than the
 *  right (device screenshot audit, 2026-07-13). Wrap-width pills don't need this: their
 *  flanks are pure padding with no centering slack to compare. M3 precedent for biasing
 *  padding toward the icon side: ButtonDefaults.ButtonWithIconContentPadding (16dp icon side
 *  vs 24dp text side). NOT glyph scaling — layout-only, tune freely. */
val MappoPillIconSideBias = 2.dp

/** Outer tap-target edge of [MappoIconButton] (also its footprint spacer in editor rows). */
val MappoIconButtonSize = 24.dp

/** Icon edge inside [MappoIconButton]. */
val MappoIconButtonIconSize = 16.dp
