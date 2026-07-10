package com.mappo.ui.glyph

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource

/**
 * Central glyph mapping for the remap UI, shared everywhere a mode / source / sub-input is shown so
 * the same thing reads identically across the screen.
 *
 * Glyphs are **built in code** (Material icons + letter badges) rather than bundled
 * controller-prompt assets — no external dependency, fully themeable, and a clean seam for an
 * Xbox↔PlayStation family toggle later (PlayStation cross/circle/square/triangle shapes are simple
 * geometric vectors to add when that toggle lands). Default family is Xbox/generic (A/B/X/Y letters).
 */
object InputGlyphs {
    /** Leading icon for a behavioral [BindingMode] in the Input Mode dropdown. */
    fun modeIcon(mode: BindingMode): ImageVector = when (mode) {
        BindingMode.DEVICE_DEFAULT -> Icons.Filled.PhoneAndroid
        BindingMode.NONE -> Icons.Filled.Block
        BindingMode.SINGLE_BUTTON -> Icons.Filled.RadioButtonChecked
        BindingMode.DPAD -> Icons.Filled.Gamepad
        BindingMode.BUTTON_PAD -> Icons.Filled.SportsEsports
        BindingMode.TRIGGER -> Icons.Filled.Straighten
        BindingMode.JOYSTICK_MOVE -> Icons.Filled.ControlCamera
        BindingMode.JOYSTICK_MOUSE -> Icons.Filled.Mouse
        BindingMode.FLICK_STICK -> Icons.Filled.Bolt
        BindingMode.MOUSE_REGION -> Icons.Filled.CropFree
        BindingMode.SCROLL_WHEEL -> Icons.Filled.UnfoldMore
        BindingMode.REFERENCE -> Icons.Filled.Link
        BindingMode.GYRO_TO_MOUSE,
        BindingMode.GYRO_TO_JOYSTICK_CAMERA,
        BindingMode.GYRO_TO_JOYSTICK_DEFLECTION -> Icons.Filled.ScreenRotation
        BindingMode.DIRECTIONAL_SWIPE -> Icons.Filled.Swipe
        BindingMode.RADIAL_MENU -> Icons.Filled.DonutLarge
        BindingMode.TOUCH_MENU -> Icons.Filled.GridView
        BindingMode.HOTBAR_MENU -> Icons.Filled.ViewModule
        else -> Icons.Filled.Tune
    }

    /** Section-header glyph for an input source. */
    fun sourceGlyph(source: InputSource?): ImageVector = when (source) {
        InputSource.BUTTON_DIAMOND -> Icons.Filled.SportsEsports
        InputSource.DPAD -> Icons.Filled.Gamepad
        InputSource.LEFT_TRIGGER, InputSource.RIGHT_TRIGGER -> Icons.Filled.Straighten
        InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> Icons.Filled.ControlCamera
        InputSource.GYRO -> Icons.Filled.ScreenRotation
        else -> Icons.Filled.RadioButtonChecked // bumpers / switches / "Other buttons"
    }

    /** Letter shown in a circled badge (Xbox family): face buttons + bumper shorthand, or null. */
    private fun faceButtonLetter(source: InputSource, subInputKey: String): String? = when (source) {
        InputSource.BUTTON_DIAMOND -> when (subInputKey) {
            "button_a" -> "A"; "button_b" -> "B"; "button_x" -> "X"; "button_y" -> "Y"; else -> null
        }
        InputSource.LEFT_BUMPER -> if (subInputKey == "click") "L1" else null
        InputSource.RIGHT_BUMPER -> if (subInputKey == "click") "R1" else null
        else -> null
    }

    /** Source-specific icon overrides for sub-inputs whose generic icon reads wrong. */
    private fun sourceSubInputIcon(source: InputSource, subInputKey: String): ImageVector? =
        when (source) {
            // Xbox-family glyph approximations: Start = hamburger, Select/View = stacked panes.
            InputSource.SWITCH_START -> if (subInputKey == "click") Icons.Filled.Menu else null
            InputSource.SWITCH_SELECT -> if (subInputKey == "click") Icons.Filled.FilterNone else null
            else -> null
        }

    /** Material icon for a non-face-button sub-input, or null when none fits (falls back to a spacer). */
    private fun subInputIcon(subInputKey: String): ImageVector? = when (subInputKey) {
        "dpad_up" -> Icons.Filled.ArrowUpward
        "dpad_down" -> Icons.Filled.ArrowDownward
        "dpad_left" -> Icons.AutoMirrored.Filled.ArrowBack
        "dpad_right" -> Icons.AutoMirrored.Filled.ArrowForward
        "full_pull", "soft_pull" -> Icons.Filled.Straighten
        "click" -> Icons.Filled.RadioButtonChecked
        "outer_ring" -> Icons.Filled.DonutLarge
        else -> null
    }

    /** Default glyph size used in headers and sub-input rows. */
    val GlyphSize = 22.dp

    /**
     * Leading glyph for a bindable sub-input row. Face buttons render as a circled letter badge
     * (Xbox family); d-pad / trigger / click etc. render a Material icon; anything else a sized
     * spacer so labels stay aligned. [size] lets callers scale it down for inline contexts (e.g.
     * a menu-item label).
     */
    @Composable
    fun SubInputGlyph(
        source: InputSource,
        subInputKey: String,
        modifier: Modifier = Modifier,
        size: androidx.compose.ui.unit.Dp = GlyphSize,
    ) {
        val tint = LocalContentColor.current
        val letter = faceButtonLetter(source, subInputKey)
        when {
            letter != null -> Box(
                modifier = modifier.size(size).border((size.value / 14.667f).dp, tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Two-character badges (L1/R1) drop a size step so they stay inside the circle.
                Text(
                    letter,
                    fontSize = (size.value * if (letter.length > 1) 0.38f else 0.5f).sp,
                    lineHeight = (size.value * if (letter.length > 1) 0.38f else 0.5f).sp,
                    color = tint,
                )
            }
            else -> {
                val icon = sourceSubInputIcon(source, subInputKey) ?: subInputIcon(subInputKey)
                if (icon != null) Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
                else Spacer(modifier.size(size))
            }
        }
    }
}
