package com.mappo.ui.glyph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Gamepad
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mappo.R
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource

/**
 * Central glyph mapping for the remap UI, shared everywhere a mode / source / sub-input is shown
 * so the same thing reads identically across the screen.
 *
 * Physical-button prompts come from the **Kenney Input Prompts 1.5** Xbox Series vector set
 * (CC0), imported as vector drawables (`res/drawable/xbox_*.xml`) — filled variants, color face
 * buttons, per-side stick marks. Prompts render UNTINTED (fixed hardware colors — the face
 * button hues and the dpad's highlighted-arm accent must not re-tint with the theme). Concept
 * icons (behavioral modes, source sections) stay Material glyphs: they describe ideas, not
 * hardware. A PlayStation-family toggle later = a parallel `ps_*` drawable set behind
 * [buttonPromptRes].
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

    /**
     * Kenney Xbox button-prompt drawable for a physical (source, sub-input) pair, or null when
     * the pair has no hardware prompt (falls back to a Material icon / spacer).
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

    /** Material fallback for sub-inputs with no hardware prompt (e.g. face buttons remapped
     *  into Directional Pad mode surface dpad_* keys on BUTTON_DIAMOND). */
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
     * Leading glyph for a bindable sub-input row: the Kenney Xbox button prompt when the pair
     * maps to physical hardware, a Material icon fallback otherwise, and a sized spacer when
     * neither fits so labels stay aligned. Prompts render UNTINTED — the drawables carry fixed
     * hardware colors (face hues, dpad accent) that deliberately ignore the theme; only the
     * Material fallbacks tint with [LocalContentColor]. [size] lets callers scale it down for
     * inline contexts (e.g. a menu-item label).
     */
    @Composable
    fun SubInputGlyph(
        source: InputSource,
        subInputKey: String,
        modifier: Modifier = Modifier,
        size: androidx.compose.ui.unit.Dp = GlyphSize,
    ) {
        val tint = LocalContentColor.current
        val promptRes = buttonPromptRes(source, subInputKey)
        when {
            promptRes != null -> Icon(
                painter = painterResource(promptRes),
                contentDescription = null,
                modifier = modifier.size(size),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            else -> {
                val icon = subInputIcon(subInputKey)
                if (icon != null) Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
                else Spacer(modifier.size(size))
            }
        }
    }
}
