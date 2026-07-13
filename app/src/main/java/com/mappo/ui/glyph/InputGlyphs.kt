package com.mappo.ui.glyph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mappo.R
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource

/**
 * Central glyph mapping for the remap UI, shared everywhere a mode / source / sub-input is shown
 * so the same thing reads identically across the screen.
 *
 * Everything here comes from the **Kenney Input Prompts 1.5** vector sets (CC0), imported as
 * vector drawables — the Xbox Series set for physical-button prompts, plus the Flairs / Generic /
 * Keyboard & Mouse / Touch / Nintendo Switch sets for behavioral concepts (modes, directions).
 * The old approximate Material glyphs were swept 2026-07-13; Material remains only where Kenney
 * has no matching concept (REFERENCE → Link, HOTBAR_MENU → ViewModule) and for gyro, which keeps
 * the Lucide rotate-3d glyph the strip caption already used.
 *
 * Tinting: **prompts render UNTINTED** (fixed hardware colors — the face button hues and the
 * dpad's highlighted-arm accent must not re-tint with the theme). **Concept glyphs tint** with
 * the caller's content color like any icon (they're single-color white fills, so tinting is
 * lossless in both themes). A PlayStation-family toggle later = a parallel `ps_*` drawable set
 * behind [buttonPromptRes].
 */
object InputGlyphs {
    /** Leading glyph for a behavioral [BindingMode] — the mode pill and mode menus. Tint with
     *  the usual content color; these are concept glyphs, not hardware prompts. The icon leans
     *  on the mode's OUTPUT concept (mouse, stick, dpad…) since the menu's source is known. */
    @Composable
    fun modePainter(mode: BindingMode): Painter = when (mode) {
        BindingMode.DEVICE_DEFAULT -> painterResource(R.drawable.controller_generic)
        BindingMode.NONE -> painterResource(R.drawable.flair_disabled)
        BindingMode.SINGLE_BUTTON -> painterResource(R.drawable.generic_button_circle)
        BindingMode.DPAD -> painterResource(R.drawable.xbox_dpad)
        BindingMode.BUTTON_PAD -> painterResource(R.drawable.switch_buttons)
        BindingMode.TRIGGER -> painterResource(R.drawable.generic_button_trigger_a)
        BindingMode.JOYSTICK_MOVE,
        BindingMode.GYRO_TO_JOYSTICK_CAMERA,
        BindingMode.GYRO_TO_JOYSTICK_DEFLECTION -> painterResource(R.drawable.generic_stick)
        BindingMode.JOYSTICK_MOUSE,
        BindingMode.GYRO_TO_MOUSE -> painterResource(R.drawable.mouse_small)
        BindingMode.FLICK_STICK -> painterResource(R.drawable.flair_small_rotate)
        BindingMode.MOUSE_REGION -> painterResource(R.drawable.flair_circle_target_b)
        BindingMode.SCROLL_WHEEL -> painterResource(R.drawable.mouse_scroll_vertical)
        BindingMode.DIRECTIONAL_SWIPE -> painterResource(R.drawable.touch_swipe_move)
        BindingMode.RADIAL_MENU -> painterResource(R.drawable.flair_circle_8)
        BindingMode.TOUCH_MENU -> painterResource(R.drawable.touch_tap)
        // No Kenney equivalents for these concepts (link / hotbar row) — Material stays.
        BindingMode.REFERENCE -> rememberVectorPainter(Icons.Filled.Link)
        BindingMode.HOTBAR_MENU -> rememberVectorPainter(Icons.Filled.ViewModule)
        else -> rememberVectorPainter(Icons.Filled.Tune)
    }

    /** Identity glyph for an input source — editor headers. Side-aware (LT vs RT, L vs R
     *  stick). All current returns are single-color prompts, so callers may tint freely. */
    @Composable
    fun sourcePainter(source: InputSource?): Painter = when (source) {
        InputSource.BUTTON_DIAMOND -> painterResource(R.drawable.switch_buttons)
        InputSource.DPAD -> painterResource(R.drawable.xbox_dpad)
        InputSource.LEFT_TRIGGER -> painterResource(R.drawable.xbox_lt)
        InputSource.RIGHT_TRIGGER -> painterResource(R.drawable.xbox_rt)
        InputSource.LEFT_BUMPER -> painterResource(R.drawable.xbox_lb)
        InputSource.RIGHT_BUMPER -> painterResource(R.drawable.xbox_rb)
        InputSource.LEFT_JOYSTICK -> painterResource(R.drawable.xbox_stick_side_l)
        InputSource.RIGHT_JOYSTICK -> painterResource(R.drawable.xbox_stick_side_r)
        InputSource.SWITCH_START -> painterResource(R.drawable.xbox_button_menu)
        InputSource.SWITCH_SELECT -> painterResource(R.drawable.xbox_button_view)
        // Kenney has no gyro/motion glyph — the Lucide rotate-3d the strip caption uses.
        InputSource.GYRO -> painterResource(R.drawable.lucide_rotate_3d)
        else -> painterResource(R.drawable.generic_button_circle)
    }

    /**
     * Kenney Xbox button-prompt drawable for a physical (source, sub-input) pair, or null when
     * the pair has no hardware prompt (falls back to a concept glyph / spacer).
     */
    private fun buttonPromptRes(source: InputSource, subInputKey: String): Int? = when (source) {
        InputSource.BUTTON_DIAMOND -> when (subInputKey) {
            "button_a" -> R.drawable.xbox_button_color_a
            "button_b" -> R.drawable.xbox_button_color_b
            "button_x" -> R.drawable.xbox_button_color_x
            "button_y" -> R.drawable.xbox_button_color_y
            else -> null
        }
        InputSource.DPAD -> when (subInputKey) {
            "dpad_up" -> R.drawable.xbox_dpad_up
            "dpad_down" -> R.drawable.xbox_dpad_down
            "dpad_left" -> R.drawable.xbox_dpad_left
            "dpad_right" -> R.drawable.xbox_dpad_right
            else -> null
        }
        InputSource.LEFT_JOYSTICK -> when (subInputKey) {
            "dpad_up" -> R.drawable.xbox_stick_l_up
            "dpad_down" -> R.drawable.xbox_stick_l_down
            "dpad_left" -> R.drawable.xbox_stick_l_left
            "dpad_right" -> R.drawable.xbox_stick_l_right
            "click" -> R.drawable.xbox_stick_side_l
            else -> null
        }
        InputSource.RIGHT_JOYSTICK -> when (subInputKey) {
            "dpad_up" -> R.drawable.xbox_stick_r_up
            "dpad_down" -> R.drawable.xbox_stick_r_down
            "dpad_left" -> R.drawable.xbox_stick_r_left
            "dpad_right" -> R.drawable.xbox_stick_r_right
            "click" -> R.drawable.xbox_stick_side_r
            else -> null
        }
        // Triggers: one prompt for every pull depth — the row label carries full vs soft.
        InputSource.LEFT_TRIGGER -> R.drawable.xbox_lt
        InputSource.RIGHT_TRIGGER -> R.drawable.xbox_rt
        InputSource.LEFT_BUMPER -> R.drawable.xbox_lb
        InputSource.RIGHT_BUMPER -> R.drawable.xbox_rb
        // Xbox Series naming: Start = Menu (☰), Select = View (⧉).
        InputSource.SWITCH_START -> R.drawable.xbox_button_menu
        InputSource.SWITCH_SELECT -> R.drawable.xbox_button_view
        else -> null
    }

    /**
     * Direction prompts for dpad_* sub-inputs surfaced on sources with no directional hardware
     * (face buttons / gyro remapped into Directional Pad mode) — the Xbox dpad prompt with the
     * matching arm highlighted, rendered UNTINTED like any prompt (the arm accent is fixed).
     */
    private fun directionPromptRes(subInputKey: String): Int? = when (subInputKey) {
        "dpad_up" -> R.drawable.xbox_dpad_up
        "dpad_down" -> R.drawable.xbox_dpad_down
        "dpad_left" -> R.drawable.xbox_dpad_left
        "dpad_right" -> R.drawable.xbox_dpad_right
        else -> null
    }

    /** Tintable Kenney concept fallback for sub-inputs with no hardware or direction prompt. */
    private fun conceptFallbackRes(subInputKey: String): Int? = when (subInputKey) {
        "full_pull", "soft_pull" -> R.drawable.generic_button_trigger_a
        "click" -> R.drawable.generic_button_circle
        "outer_ring" -> R.drawable.flair_circle_target_a
        else -> null
    }

    /** Default glyph size used in headers and sub-input rows. */
    val GlyphSize = 22.dp

    /**
     * Leading glyph for a bindable sub-input row: the Kenney Xbox button prompt when the pair
     * maps to physical hardware, a direction prompt for dpad_* keys on non-directional sources,
     * a tinted Kenney concept glyph otherwise, and a sized spacer when nothing fits so labels
     * stay aligned. Prompts render UNTINTED — the drawables carry fixed hardware colors (face
     * hues, dpad accent) that deliberately ignore the theme; only the concept fallbacks tint
     * with [LocalContentColor]. [size] lets callers scale it down for inline contexts (e.g. a
     * menu-item label).
     */
    @Composable
    fun SubInputGlyph(
        source: InputSource,
        subInputKey: String,
        modifier: Modifier = Modifier,
        size: androidx.compose.ui.unit.Dp = GlyphSize,
    ) {
        val promptRes = buttonPromptRes(source, subInputKey) ?: directionPromptRes(subInputKey)
        when {
            promptRes != null -> Icon(
                painter = painterResource(promptRes),
                contentDescription = null,
                modifier = modifier.size(size),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            else -> {
                val fallback = conceptFallbackRes(subInputKey)
                if (fallback != null) {
                    Icon(
                        painter = painterResource(fallback),
                        contentDescription = null,
                        modifier = modifier.size(size),
                        tint = LocalContentColor.current,
                    )
                } else Spacer(modifier.size(size))
            }
        }
    }
}
