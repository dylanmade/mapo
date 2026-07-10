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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mappo.ui.screen.softDropShadow

/** Duration of the frame's slide-up entrance / slide-down exit (MainScreen delays
 *  moveTaskToBack by this so the exit animation is visible). */
const val HandheldFrameSlideMillis = 420

private val TopMargin = 10.dp
private val GlassCorner = 20.dp
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
/** Duration of the expand ↔ contract resize between the 1:1 LCD and full width. */
private const val ExpandMillis = 320

// Deliberate fixed hardware colors (skeuomorphic exception — these must NOT re-tint with the
// theme seed): the shell is an off-white plastic, the glass a near-black darker than the LCD's
// surfaceContainerLowest so bezel and screen stay differentiated in dark theme.
private val BodyShellColor = Color(0xFFEFECE6)
private val GlassColor = Color(0xFF060809)
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

        // LCD bounds. Contracted: a 1:1 square as large as both axes allow inside the shell +
        // glass borders. Expanded: fill everything the display offers. Both animate through the
        // same pair so the glass + shell resize with the screen.
        val maxLcdW = availW - ShellBorder * 2 - GlassInset * 2
        val maxLcdH = availH - TopMargin - ShellBorder - GlassInset - GlassBottomBand
        val squareLcd = minOf(maxLcdW, maxLcdH)
        val lcdW by animateDpAsState(
            targetValue = if (expanded) maxLcdW else squareLcd,
            animationSpec = tween(ExpandMillis, easing = FastOutSlowInEasing),
            label = "lcdWidth",
        )
        val lcdH by animateDpAsState(
            targetValue = if (expanded) maxLcdH else squareLcd,
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
            // The off-white shell wraps the glass on all visible sides: border above and beside
            // the glass, body below it (fixed hardware color — see BodyShellColor).
            Column(
                Modifier
                    .softDropShadow(cornerRadius = ShellCorner, blurRadius = 24.dp, offsetY = 6.dp)
                    // Consume taps on the device so they don't reach the scrim's dismiss catcher.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .width(shellW)
                    .clip(RoundedCornerShape(topStart = ShellCorner, topEnd = ShellCorner))
                    .background(BodyShellColor),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(ShellBorder))
                GlassPanel(
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
    lcdW: Dp,
    lcdH: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(topStart = GlassCorner, topEnd = GlassCorner))
            .background(GlassColor),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // surfaceContainerLowest — the lit screen canvas every Mappo route renders into.
        Box(
            Modifier
                .padding(start = GlassInset, top = GlassInset, end = GlassInset)
                .size(width = lcdW, height = lcdH)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) { content() }
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
}
