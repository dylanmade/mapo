package com.mappo.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
// Taller bottom border of the screen glass, carrying the wordmark — Game Boy Color style.
private val GlassBottomBand = 34.dp
// How far past the display's bottom edge the device body continues (its "buttons" live
// below the fold, off-screen).
private val BodyOverhang = 60.dp

/**
 * The Mappo home chrome: a faux vertical retro handheld (original Game Boy silhouette) that
 * slides up from the bottom of the display. Only its screen is visible: the dark glass bezel
 * with the lit 1:1 canvas every Mappo route renders into, plus the wordmark on the glass's
 * extended bottom border. The device body is the same width as the glass (nothing visible
 * left/right of the screen) and extends off the bottom edge of the display.
 *
 * DELIBERATE M3 DEVIATION: skeuomorphic hardware chrome, not a Material container. The body
 * draws from theme roles (re-tints with the seed); the glass is a fixed near-black by design.
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

        // The 1:1 LCD, as large as both axes allow inside the glass borders.
        val lcd = minOf(
            availW - GlassInset * 2,
            availH - TopMargin - GlassInset - GlassBottomBand,
        )
        val glassW = lcd + GlassInset * 2
        val glassH = lcd + GlassInset + GlassBottomBand
        val bodyH = (availH - TopMargin - glassH + BodyOverhang).coerceAtLeast(0.dp)

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * heightPx },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(TopMargin))
            Column(
                Modifier
                    .softDropShadow(cornerRadius = GlassCorner, blurRadius = 24.dp, offsetY = 6.dp)
                    // Consume taps on the device so they don't reach the scrim's dismiss catcher.
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlassPanel(lcd = lcd, content = screenContent)
                // surfaceContainerHigh — the device body below the screen, running off the
                // bottom of the display (re-tints with the theme seed).
                Box(
                    Modifier
                        .width(glassW)
                        .height(bodyH)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
    }
}

/**
 * The screen glass: dark bezel, rounded top corners, the lit LCD inset, and the extended
 * bottom border carrying the wordmark (GBC-style "first border layer" logo placement).
 */
@Composable
private fun GlassPanel(lcd: Dp, content: @Composable () -> Unit) {
    // Deliberate raw near-black: LCD glass reads dark in both themes (skeuomorphic exception).
    Column(
        Modifier
            .clip(RoundedCornerShape(topStart = GlassCorner, topEnd = GlassCorner))
            .background(Color(0xFF0C0F12)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // surfaceContainerLowest — the lit 1:1 screen canvas every Mappo route renders into.
        Box(
            Modifier
                .padding(start = GlassInset, top = GlassInset, end = GlassInset)
                .size(lcd)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) { content() }
        Box(Modifier.height(GlassBottomBand).width(lcd), contentAlignment = Alignment.Center) {
            // Deliberate fixed light gray — a printed logo on the fixed dark glass.
            Text(
                text = "Mappo",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9AA3AD),
            )
        }
    }
}
