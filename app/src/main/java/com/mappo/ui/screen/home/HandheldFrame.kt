package com.mappo.ui.screen.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mappo.ui.compact.scaledLayout
import com.mappo.ui.screen.softDropShadow

/** Duration of the frame's slide-up entrance / slide-down exit (MainScreen delays
 *  moveTaskToBack by this so the exit animation is visible). */
const val HandheldFrameSlideMillis = 420

private val ShellCorner = 30.dp
private val GlassInset = 12.dp

/**
 * The Mappo home chrome: a faux retro gaming handheld ("the device") that slides up from the
 * bottom of the display and whose 1:1 "screen" is the canvas every Mappo route renders into.
 *
 * DELIBERATE M3 DEVIATION: this is skeuomorphic hardware chrome, not a Material container. It
 * still draws from the theme's color roles (so the shell re-tints with the seed), but the dark
 * screen glass, LED, and the four accent dots are fixed identity colors by design.
 *
 * Layout adapts to the display: landscape gets side grips (decorative d-pad left, power +
 * accent dots + speaker grille right, wordmark under the screen); portrait goes Game Boy
 * (screen up top, one chrome band below).
 */
@Composable
fun HandheldFrame(
    shown: Boolean,
    powerOn: Boolean,
    onPowerChange: (Boolean) -> Unit,
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

        // Scrim — conventional modal scrim over the app underneath; fades with the slide and
        // doesn't travel with the device. (Sanctioned raw black per the scrim exception.)
        if (slide.value < 1f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - slide.value }
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(dismissEnabled) {
                        detectTapGestures { if (dismissEnabled) onDismissRequest() }
                    },
            )
        }

        val landscape = availW > availH
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * heightPx },
            contentAlignment = Alignment.Center,
        ) {
            if (landscape) {
                LandscapeShell(availW, availH, powerOn, onPowerChange, screenContent)
            } else {
                PortraitShell(availW, availH, powerOn, onPowerChange, screenContent)
            }
        }
    }
}

/** Horizontal handheld: grip | bezel | screen | bezel | grip, wordmark on the bottom bezel. */
@Composable
private fun LandscapeShell(
    maxW: Dp,
    maxH: Dp,
    powerOn: Boolean,
    onPowerChange: (Boolean) -> Unit,
    screenContent: @Composable () -> Unit,
) {
    val bezel = 18.dp
    val bezelBottom = 34.dp // taller than the top: carries the wordmark, Game Boy style
    var glass = (maxH * 0.94f - bezel - bezelBottom).coerceAtLeast(160.dp)
    val gripAvail = (maxW * 0.95f - glass - bezel * 2) / 2
    val grip = gripAvail.coerceIn(80.dp, 150.dp)
    if (gripAvail < 80.dp) {
        // Squat landscape: keep minimum grips and shrink the screen instead.
        glass = (maxW * 0.95f - bezel * 2 - grip * 2).coerceAtLeast(160.dp)
    }

    ShellSurface {
        Row(Modifier.height(glass + bezel + bezelBottom)) {
            // Left grip — decorative d-pad.
            Box(Modifier.width(grip).fillMaxHeight()) {
                DecorDpad(
                    modifier = Modifier.align(Alignment.Center),
                    size = (grip * 0.8f).coerceAtMost(92.dp),
                )
            }
            Spacer(Modifier.width(bezel))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(bezel))
                GlassScreen(glass, screenContent)
                Box(Modifier.height(bezelBottom).width(glass), contentAlignment = Alignment.Center) {
                    Wordmark()
                }
            }
            Spacer(Modifier.width(bezel))
            // Right grip — power up top, accent dots center, speaker grille bottom.
            Box(Modifier.width(grip).fillMaxHeight().padding(vertical = 18.dp)) {
                PowerControl(powerOn, onPowerChange, Modifier.align(Alignment.TopCenter))
                AccentDotsDiamond(
                    modifier = Modifier.align(Alignment.Center),
                    size = (grip * 0.55f).coerceAtMost(72.dp),
                )
                SpeakerGrille(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Vertical handheld (Game Boy): screen up top, one chrome band below. */
@Composable
private fun PortraitShell(
    maxW: Dp,
    maxH: Dp,
    powerOn: Boolean,
    onPowerChange: (Boolean) -> Unit,
    screenContent: @Composable () -> Unit,
) {
    val bezel = 18.dp
    val shellW = maxW * 0.92f
    val glass = shellW - bezel * 2
    val chromeH = (maxH * 0.92f - glass - bezel).coerceIn(112.dp, 188.dp)

    ShellSurface {
        Column(Modifier.width(shellW), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(bezel))
            GlassScreen(glass, screenContent)
            Box(Modifier.fillMaxWidth().height(chromeH)) {
                DecorDpad(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp),
                    size = (chromeH * 0.55f).coerceAtMost(84.dp),
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PowerControl(powerOn, onPowerChange)
                    Spacer(Modifier.height(6.dp))
                    Wordmark()
                }
                AccentDotsDiamond(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 28.dp),
                    size = (chromeH * 0.5f).coerceAtMost(72.dp),
                )
            }
        }
    }
}

@Composable
private fun ShellSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .softDropShadow(cornerRadius = ShellCorner, blurRadius = 24.dp, offsetY = 10.dp)
            // Consume taps on the shell so they don't reach the scrim's dismiss catcher.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // surfaceContainerHigh — the device shell chrome (re-tints with the theme seed).
        Surface(
            shape = RoundedCornerShape(ShellCorner),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            content = content,
        )
    }
}

/** Dark screen glass with the lit 1:1 canvas inset inside it. */
@Composable
private fun GlassScreen(side: Dp, content: @Composable () -> Unit) {
    // Deliberate raw near-black: LCD glass reads dark in both themes (skeuomorphic exception).
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C0F12)),
        contentAlignment = Alignment.Center,
    ) {
        // surfaceContainerLowest — the lit 1:1 screen canvas every Mappo route renders into.
        Box(
            Modifier
                .size(side - GlassInset * 2)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) { content() }
    }
}

@Composable
private fun Wordmark() {
    Text(
        text = "Mappo",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The master enable relocated from the old toolbar: a "Power" switch + LED driving remap and
 * the button overlay in lockstep (wired by the caller).
 */
@Composable
private fun PowerControl(
    powerOn: Boolean,
    onPowerChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Deliberate fixed LED green — a power LED is green regardless of theme.
            val led by animateColorAsState(
                targetValue = if (powerOn) Color(0xFF52E07C)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                label = "powerLed",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(led),
            )
            Spacer(Modifier.width(6.dp))
            // Strip the 48dp interactive halo + scale down so the switch reads as hardware trim.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Switch(
                    checked = powerOn,
                    onCheckedChange = onPowerChange,
                    modifier = Modifier.scaledLayout(0.8f),
                    thumbContent = {
                        Icon(
                            imageVector = if (powerOn) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = if (powerOn) "Mappo features on" else "Mappo features off",
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                )
            }
        }
        Text(
            text = "Power",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Decorative (non-interactive) d-pad molded into the grip. */
@Composable
private fun DecorDpad(modifier: Modifier = Modifier, size: Dp) {
    val fill = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val stroke = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val arm = s * 0.34f
        val r = CornerRadius(arm * 0.3f, arm * 0.3f)
        val vTop = Offset((s - arm) / 2f, 0f)
        val vSize = Size(arm, s)
        val hTop = Offset(0f, (s - arm) / 2f)
        val hSize = Size(s, arm)
        drawRoundRect(color = fill, topLeft = vTop, size = vSize, cornerRadius = r)
        drawRoundRect(color = fill, topLeft = hTop, size = hSize, cornerRadius = r)
        drawRoundRect(color = stroke, topLeft = vTop, size = vSize, cornerRadius = r, style = Stroke(1.dp.toPx()))
        drawRoundRect(color = stroke, topLeft = hTop, size = hSize, cornerRadius = r, style = Stroke(1.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.10f), radius = arm * 0.30f, center = Offset(s / 2f, s / 2f))
    }
}

/**
 * Four face-button dots in a diamond, carrying the flower's accent mapping (up=profile blue,
 * left=overlay green, right=remap purple, down=settings amber) as a hardware Easter egg.
 */
@Composable
private fun AccentDotsDiamond(modifier: Modifier = Modifier, size: Dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val r = s * 0.14f
        val c = s / 2f
        val dots = listOf(
            Offset(c, r) to ProfileAccent,
            Offset(r, c) to OverlayAccent,
            Offset(s - r, c) to RemapAccent,
            Offset(c, s - r) to SettingsAccent,
        )
        dots.forEach { (center, color) ->
            drawCircle(color.copy(alpha = 0.9f), radius = r, center = center)
            drawCircle(Color.Black.copy(alpha = 0.18f), radius = r, center = center, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun SpeakerGrille(modifier: Modifier = Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(modifier.size(width = 44.dp, height = 18.dp)) {
        val r = 1.6.dp.toPx()
        val cols = 5
        val rows = 2
        val stepX = (this.size.width - r * 2) / (cols - 1)
        val stepY = (this.size.height - r * 2) / (rows - 1)
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                drawCircle(dot, radius = r, center = Offset(r + i * stepX, r + j * stepY))
            }
        }
    }
}
