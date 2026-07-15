package com.mappo.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mappo.data.settings.FrameStyle
import com.mappo.ui.screen.softDropShadow
import kotlin.random.Random

/** Duration of the frame's slide-up entrance / slide-down exit (MainScreen delays
 *  moveTaskToBack by this so the exit animation is visible). */
const val HandheldFrameSlideMillis = 420

private val TopMargin = 10.dp
// Corner-squaring pass 2026-07-14: real hardware rounds far less than M3 chrome — the glass
// especially (its INNER edge, at the bezel, is practically square; see LcdCorner/ScreenCorner).
private val GlassCorner = 6.dp
private val GlassInset = 10.dp
// Width of the off-white shell border wrapping the glass on its top/left/right (the shell also
// continues below the glass as the device body). Outer corner = GlassCorner + ShellBorder so
// the shell and glass corners run concentric.
private val ShellBorder = 10.dp
private val ShellCorner = GlassCorner + ShellBorder
// Taller bottom border of the screen glass, carrying the wordmark — Game Boy Color style.
private val GlassBottomBand = 34.dp
// How far past the display's bottom edge the device body continues (its "buttons" live
// below the fold, off-screen).
private val BodyOverhang = 60.dp
// Off-white body deliberately left visible BELOW the glass before the device runs off the
// display edge — shortens the glass and lifts the frame up by this much. Equal to ShellBorder
// so the visible frame reads the same width on all four sides.
private val BottomExposure = ShellBorder
/** Duration of the expand ↔ contract resize between the 1:1 LCD and full width. */
private const val ExpandMillis = 320

// Deliberate fixed hardware color (skeuomorphic exception — must NOT re-tint with the theme
// seed): a printed logo on the glass. The shell/glass/bezel core colors moved into
// [FrameStyle] (user-adjustable via the Frame style settings screen) but remain equally
// theme-independent — hardware doesn't repaint itself.
private val GlassPrintColor = Color(0xFF9AA3AD)

/**
 * The Mappo home chrome: a faux vertical retro handheld (original Game Boy silhouette) that
 * slides up from the bottom of the display. Only its screen is visible: the dark glass bezel
 * with the lit canvas every Mappo route renders into, plus the wordmark on the glass's
 * extended bottom border. The device body is the same width as the glass (nothing visible
 * left/right of the screen) and extends off the bottom edge of the display.
 *
 * The bottom band's trailing button toggles the LCD between its resting 1:1 square and an
 * expanded state filling the display's full width/height (responsive to whatever screen Mappo
 * runs on); the resize is animated and the glass/body follow.
 *
 * DELIBERATE M3 DEVIATION: skeuomorphic hardware chrome, not a Material container — the shell
 * and glass are fixed hardware colors ([BodyShellColor] / [GlassColor]).
 */
@Composable
fun HandheldFrame(
    shown: Boolean,
    // Only the MAIN route treats a tap on the scrim as "leave Mappo".
    dismissEnabled: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    style: FrameStyle = FrameStyle(),
    screenContent: @Composable () -> Unit,
) {
    // Starts hidden (1f) so the first composition animates the device up into view.
    val slide = remember { Animatable(1f) }
    LaunchedEffect(shown) {
        slide.animateTo(
            targetValue = if (shown) 0f else 1f,
            animationSpec = tween(HandheldFrameSlideMillis, easing = FastOutSlowInEasing),
        )
    }

    // Expanded = LCD fills the available width/height; contracted = the resting 1:1 square.
    var expanded by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        // Captured locals: BoxWithConstraintsScope properties aren't reachable from nested
        // content lambdas (DslMarker).
        val availW = maxWidth
        val availH = maxHeight

        // Frozen blurred backdrop of the app underneath (captured + downscale-blurred in
        // HomeBackdrop — window/RenderEffect blur is unusable on this device). Fades with the
        // slide so dismissing melts back to the live sharp app. Null (no capture) → the live
        // background stays fully visible, undimmed.
        val backdrop by HomeBackdrop.bitmap.collectAsState()
        backdrop?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - slide.value },
                contentScale = ContentScale.FillBounds,
            )
        }
        // Invisible tap-catcher (deliberately NOT a dimming scrim): a tap outside the device
        // dismisses the home on MAIN.
        if (slide.value < 1f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dismissEnabled) {
                        detectTapGestures { if (dismissEnabled) onDismissRequest() }
                    },
            )
        }

        // LCD bounds. Contracted: the GLASS (LCD + bezel insets + wordmark band) is the 1:1
        // square, so the LCD itself comes out slightly wide. Expanded: fill everything the
        // display offers. Both animate through the same pair so the glass + shell resize with
        // the screen.
        val maxLcdW = availW - ShellBorder * 2 - GlassInset * 2
        val maxLcdH = availH - TopMargin - ShellBorder - GlassInset - GlassBottomBand - BottomExposure
        val glassSquare = minOf(
            availW - ShellBorder * 2,
            availH - TopMargin - ShellBorder - BottomExposure,
        )
        val lcdW by animateDpAsState(
            targetValue = if (expanded) maxLcdW else glassSquare - GlassInset * 2,
            animationSpec = tween(ExpandMillis, easing = FastOutSlowInEasing),
            label = "lcdWidth",
        )
        val lcdH by animateDpAsState(
            targetValue = if (expanded) maxLcdH else glassSquare - GlassInset - GlassBottomBand,
            animationSpec = tween(ExpandMillis, easing = FastOutSlowInEasing),
            label = "lcdHeight",
        )
        val glassW = lcdW + GlassInset * 2
        val glassH = lcdH + GlassInset + GlassBottomBand
        val shellW = glassW + ShellBorder * 2
        // Shell continues below the glass as the device body, off the display's bottom edge.
        val bodyH = (availH - TopMargin - ShellBorder - glassH + BodyOverhang).coerceAtLeast(0.dp)

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * heightPx },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(TopMargin))
            // The plastic shell wraps the glass on all visible sides: border above and beside
            // the glass, body below it. Core color + grain + edge lighting from [style].
            Column(
                Modifier
                    .softDropShadow(cornerRadius = ShellCorner, blurRadius = 24.dp, offsetY = 6.dp)
                    // Consume taps on the device so they don't reach the scrim's dismiss catcher.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .width(shellW)
                    .clip(RoundedCornerShape(topStart = ShellCorner, topEnd = ShellCorner))
                    .background(style.shellColor)
                    .drawBehind { drawShellFinish(style) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(ShellBorder))
                GlassPanel(
                    style = style,
                    lcdW = lcdW,
                    lcdH = lcdH,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    content = screenContent,
                )
                Spacer(Modifier.height(bodyH))
            }
        }
    }
}

/**
 * The screen glass: dark bezel, rounded top corners, the lit LCD inset, and the extended
 * bottom border carrying the wordmark (GBC-style "first border layer" logo placement) plus the
 * expand/contract toggle on its right side.
 */
@Composable
private fun GlassPanel(
    style: FrameStyle,
    lcdW: Dp,
    lcdH: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The WELL: shell and glass are two separate physical parts, so a minute dark gap rings
    // the glass. Rendered as a [WellWidth] ring of shadowed shell color under the glass —
    // the inner column's insets shrink by the same amount, so the panel's outer footprint
    // (and the shell math built on it) is unchanged.
    val wellColor = lerp(style.shellColor, Color.Black, style.well)
    Column(
        Modifier
            .clip(RoundedCornerShape(topStart = GlassCorner, topEnd = GlassCorner))
            .background(wellColor),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .padding(WellWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = GlassCorner - WellWidth,
                        topEnd = GlassCorner - WellWidth,
                    ),
                )
                .background(style.glassColor)
                .drawBehind { drawGlassFinish(style) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The screen bezel wraps the lit canvas: a flat dark border (its own core color),
            // with the passive-LCD square vignette shading the canvas's edges just inside it.
            Box(
                Modifier
                    .padding(
                        start = GlassInset - WellWidth,
                        top = GlassInset - WellWidth,
                        end = GlassInset - WellWidth,
                    )
                    .size(width = lcdW, height = lcdH)
                    .clip(RoundedCornerShape(LcdCorner))
                    .background(style.bezelColor),
            ) {
                // surfaceContainerLowest — the lit screen canvas every Mappo route renders into.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(BezelWidth)
                        .clip(RoundedCornerShape(ScreenCorner))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) { content() }
                // Vignette overlay — draws OVER the canvas edges (pure draw layer: no
                // pointer/focus surface, so input passes straight through to the content).
                if (style.vignette > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(BezelWidth)
                            .clip(RoundedCornerShape(ScreenCorner))
                            .drawBehind { drawScreenVignette(style.vignette) },
                    )
                }
            }
            ChinBand(lcdW = lcdW, expanded = expanded, onToggleExpanded = onToggleExpanded)
        }
    }
}

@Composable
private fun ChinBand(
    lcdW: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Box(Modifier.height(GlassBottomBand).width(lcdW), contentAlignment = Alignment.Center) {
        // Deliberate fixed light gray — a printed logo on the fixed dark glass.
        Text(
            text = "Mappo",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = GlassPrintColor,
        )
        // Expand ↔ contract toggle, printed-hardware styling to match the wordmark.
        IconButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .size(28.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (expanded) "Contract screen" else "Expand screen",
                tint = GlassPrintColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Faux-hardware finish (2026-07-14): plastic grain + edge lighting + vignette ──────────
//
// All vector/draw-phase work — no bitmaps beyond one tiny tiled noise tile, no blur, no
// RuntimeShader — because the frame often renders over a high-intensity game. Each pass is a
// single drawRect/drawRoundRect. Derivation follows the remap-pill bevel principle: a layer's
// highlights/shadows are its CORE color lerped toward white/black by the user's intensity, so
// an intensity of 0 degenerates to the core color (invisible) and everything retints for free
// when the core changes.

/** Shell finish: plastic grain, top-edge highlight, and edge-shadow passes (annotations 1-3). */
private fun DrawScope.drawShellFinish(style: FrameStyle) {
    // 1 — plastic grain: a fixed-seed mono speckle tile, sampled REPEATED across the shell.
    if (style.shellTexture > 0f) {
        drawRect(ShellNoiseBrush, alpha = style.shellTexture)
    }
    // 3 — edge shadow: one gradient band per visible edge, each fading PERPENDICULAR to its
    // edge — shaded at the silhouette (where the front face rounds away to the top/side
    // face), fully clear by the front face. True shapes, not an outline stroke: a single
    // stroke can't carry three different gradient directions, and its roundrect path also
    // rounded the bottom corners the shell doesn't have. No bottom band — the shell's bottom
    // edge is the off-screen body. The clip keeps the bands inside the top rounding; the
    // small top-corner overlaps double up, which is where the two curvatures meet anyway.
    if (style.shellShadow > 0f) {
        val shadow = lerp(style.shellColor, Color.Black, style.shellShadow)
        val clear = shadow.copy(alpha = 0f)
        val band = ShellShadowBand.toPx()
        drawRect(
            Brush.verticalGradient(0f to shadow, 1f to clear, startY = 0f, endY = band),
            size = Size(size.width, band),
        )
        drawRect(
            Brush.horizontalGradient(0f to shadow, 1f to clear, startX = 0f, endX = band),
            size = Size(band, size.height),
        )
        drawRect(
            Brush.horizontalGradient(
                0f to clear, 1f to shadow,
                startX = size.width - band, endX = size.width,
            ),
            topLeft = Offset(size.width - band, 0f),
            size = Size(band, size.height),
        )
    }
    // 2 — top-edge highlight: light catching the inner face of the top rounding, drawn just
    // inside the shadow band's darkest zone and fading out past the corners.
    if (style.shellHighlight > 0f) {
        edgeStroke(
            brush = TopFadeBrush(
                lerp(style.shellColor, Color.White, style.shellHighlight),
                fadePx = ShellHighlightFade.toPx(),
            ),
            strokeWidth = ShellEdgeHighlightStroke.toPx(),
            inset = ShellHighlightInset.toPx(),
            cornerPx = ShellCorner.toPx(),
        )
    }
}

/** Glass finish: the subtle rim light on the glass frame's slight edge rounding (annotation 6). */
private fun DrawScope.drawGlassFinish(style: FrameStyle) {
    if (style.glassHighlight <= 0f) return
    edgeStroke(
        brush = TopFadeBrush(
            lerp(style.glassColor, Color.White, style.glassHighlight),
            fadePx = GlassHighlightFade.toPx(),
        ),
        strokeWidth = GlassEdgeHighlightStroke.toPx(),
        inset = 0f,
        cornerPx = (GlassCorner - WellWidth).toPx(),
    )
}

/** One rounded-rect edge stroke, inset from the bounds, corner radius kept concentric. */
private fun DrawScope.edgeStroke(
    brush: Brush,
    strokeWidth: Float,
    inset: Float,
    cornerPx: Float,
) {
    val off = inset + strokeWidth / 2f
    val sz = Size(size.width - off * 2f, size.height - off * 2f)
    val radius = CornerRadius((cornerPx - off).coerceAtLeast(0f))
    drawRoundRect(brush, Offset(off, off), sz, radius, style = Stroke(strokeWidth))
}

/** 8 — the passive-LCD square vignette: four edge gradients over the canvas rim; the corner
 *  overlaps double up, which is exactly how the real panel's corners read darker. */
private fun DrawScope.drawScreenVignette(strength: Float) {
    val v = VignetteWidth.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    val dark = Color.Black.copy(alpha = strength * MaxVignetteAlpha)
    val clear = Color.Black.copy(alpha = 0f)
    drawRect(
        Brush.verticalGradient(0f to dark, 1f to clear, startY = 0f, endY = v),
        size = Size(size.width, v),
    )
    drawRect(
        Brush.verticalGradient(0f to clear, 1f to dark, startY = size.height - v, endY = size.height),
        topLeft = Offset(0f, size.height - v),
        size = Size(size.width, v),
    )
    drawRect(
        Brush.horizontalGradient(0f to dark, 1f to clear, startX = 0f, endX = v),
        size = Size(v, size.height),
    )
    drawRect(
        Brush.horizontalGradient(0f to clear, 1f to dark, startX = size.width - v, endX = size.width),
        topLeft = Offset(size.width - v, 0f),
        size = Size(v, size.height),
    )
}

/** Vertical fade: [color] at the top edge, transparent by [fadePx] — the shell/glass top
 *  highlight. Same-hue-zero-alpha fade (not transparent black), per the bevel doctrine. */
private class TopFadeBrush(private val color: Color, private val fadePx: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val fade = (fadePx / size.height).coerceIn(0.01f, 1f)
        return LinearGradientShader(
            from = Offset.Zero,
            to = Offset(0f, size.height),
            colors = listOf(color, color.copy(alpha = 0f), color.copy(alpha = 0f)),
            colorStops = listOf(0f, fade, 1f),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is TopFadeBrush && other.color == color && other.fadePx == fadePx

    override fun hashCode(): Int = 31 * color.hashCode() + fadePx.hashCode()
}

/** The plastic-grain tile: fixed-seed mono speckle (random white/black pixels at random low
 *  alpha), tiled REPEATED. Generated once per process — 128×128, ~64KB, GPU-resident after
 *  first draw; per-frame cost is a single texture sample. Intensity rides the draw alpha. */
private val ShellNoiseBrush: ShaderBrush by lazy {
    val side = 128
    val rnd = Random(0x5EED)
    val pixels = IntArray(side * side) {
        val alpha = rnd.nextInt(0, 56)
        val luma = if (rnd.nextBoolean()) 0xFFFFFF else 0x000000
        (alpha shl 24) or luma
    }
    val bitmap = android.graphics.Bitmap.createBitmap(
        pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888,
    ).asImageBitmap()
    ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

// ── Finish metrics (hand-tune here; intensities/colors live in FrameStyle) ────────────────

/** The shell↔glass gap ring (annotation 4). */
private val WellWidth = 1.5.dp

/** Corner radius of the screen-bezel block — the glass's INNER edge, practically square. */
private val LcdCorner = 1.5.dp

/** Corner radius of the lit canvas itself — fully square: a real LCD is made of square
 *  pixels, so its corners are literal right angles. */
private val ScreenCorner = 0.dp

/** Width of the flat dark bezel between glass and the lit canvas (annotation 7). */
private val BezelWidth = 5.dp

/** How far the bezel vignette reaches into the canvas (annotation 8). */
private val VignetteWidth = 12.dp

/** Vignette alpha at intensity 1.0 — full black would read as a broken backlight. */
private const val MaxVignetteAlpha = 0.5f

/** How far the shell's edge-shadow bands reach in from the silhouette toward the front face. */
private val ShellShadowBand = 6.dp

private val ShellEdgeHighlightStroke = 2.5.dp

/** The highlight sits just inside the shadow band's darkest zone (the silhouette rim). */
private val ShellHighlightInset = 1.5.dp

/** How far down the shell's top highlight survives before fading out (past the corners). */
private val ShellHighlightFade = 48.dp

private val GlassEdgeHighlightStroke = 1.25.dp

/** Glass top-highlight fade reach — fixed, NOT corner-derived: squaring the corners
 *  (2026-07-14) would otherwise have collapsed the fade the user signed off on. */
private val GlassHighlightFade = 28.dp
