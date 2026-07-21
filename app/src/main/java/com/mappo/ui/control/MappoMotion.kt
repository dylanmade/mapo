package com.mappo.ui.control

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** The motion vocabulary [mappoInteractiveMotion] speaks. Both variants are RELATIVE on
 *  press — the same travel from wherever the element currently rests, never an absolute
 *  target (absolute targets made focus presses feel far more intense than unfocused ones,
 *  a lesson learned once per variant). */
enum class MappoMotion {
    /** Focus/hover raises the element [MappoFocusLift]; press displaces it [MappoPressTravel]
     *  down from its current position (release springs it back). The elevation metaphor —
     *  the library default since 2026-07-14. */
    Lift,

    /** Focus/hover grows the element [MappoFocusedScale]×; press dips it to
     *  [MappoPressedFraction] of its current size. The original treatment (retired as the
     *  default 2026-07-14 — read kitschy) — kept for spots where a grow fits better. */
    Scale,
}

/**
 * Interaction motion for Mappo's interactive controls — [MappoMotion.Lift] (default) or
 * [MappoMotion.Scale]. Pass the SAME [interactionSource] to the element's `clickable` so
 * key-clicks and touch presses both register. Draw-phase only ([graphicsLayer]), so layout
 * bounds (and any morph-origin rects measured from them) never move. Chain it BEFORE the
 * element's border/background so the whole control moves as one.
 */
@Composable
fun Modifier.mappoInteractiveMotion(
    interactionSource: InteractionSource,
    motion: MappoMotion = MappoMotion.Lift,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val pressed by interactionSource.collectIsPressedAsState()
    // The press lands faster than the focus lift/grow — a tap should feel immediate.
    val durationMillis = if (pressed) 90 else 150
    val tracked = this.onFocusChanged { focused = it.isFocused }
    return when (motion) {
        MappoMotion.Lift -> {
            val base = if (focused) -MappoFocusLift else 0.dp
            val offset by animateDpAsState(
                targetValue = if (pressed) base + MappoPressTravel else base,
                animationSpec = tween(durationMillis),
                label = "mappoInteractiveLift",
            )
            tracked.graphicsLayer { translationY = offset.toPx() }
        }
        MappoMotion.Scale -> {
            val base = if (focused) MappoFocusedScale else 1f
            val scale by animateFloatAsState(
                targetValue = if (pressed) base * MappoPressedFraction else base,
                animationSpec = tween(durationMillis),
                label = "mappoInteractiveScale",
            )
            tracked.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

/** How far focus/hover raises an element ([MappoMotion.Lift]). */
private val MappoFocusLift = 1.25.dp

/** How far a press displaces an element DOWN from wherever it currently rests (focused:
 *  lift → back to base; unfocused: base → below it) — always the same travel. */
private val MappoPressTravel = 2.dp

/** Focus/hover grow for [MappoMotion.Scale]. */
private const val MappoFocusedScale = 1.05f

/** Press dip for [MappoMotion.Scale] — a fraction of the CURRENT resting size
 *  (focused+pressed = 1.05×0.93), never an absolute scale. */
private const val MappoPressedFraction = 0.93f
