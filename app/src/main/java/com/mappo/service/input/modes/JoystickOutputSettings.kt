package com.mappo.service.input.modes

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import org.json.JSONException
import org.json.JSONObject

/**
 * The output-shaping subset of the "Joystick" mode settings — the knobs that
 * apply when a source emits as a virtual gamepad stick. Drives the digital
 * Button-Pad/D-Pad → joystick synthesis (where the source vector is a
 * unit-ish direction from held buttons) and is reusable by the analog
 * real-joystick path when that menu wires up.
 *
 * Scales are stored 0..100 in JSON (the UI's percent) and parsed to 0..1 here.
 * Curve/deadzone knobs are intentionally absent: they're no-ops on a digital
 * (always-full-deflection) source and the analog path has its own
 * deadzone/curve handling. Keys match `SourceModeSettingsSchema`.
 */
internal data class JoystickOutputSettings(
    val horizontalScale: Float,
    val verticalScale: Float,
    val invertHorizontal: Boolean,
    val invertVertical: Boolean,
    val outputAxis: OutputAxis,
    val rotateDegrees: Float,
    val outputStick: OutputStick,
) {
    enum class OutputAxis { HORIZONTAL, VERTICAL, BOTH }
    enum class OutputStick { LEFT, RIGHT }

    /**
     * Shape a raw stick vector (each component nominally -1..1, +Y = up) into the
     * emitted vector. Order: rotate → per-axis scale → invert → axis-limit → clamp.
     */
    fun apply(rawX: Float, rawY: Float): Pair<Float, Float> {
        var x = rawX
        var y = rawY
        if (rotateDegrees != 0f) {
            val r = Math.toRadians(rotateDegrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            // Clockwise rotation so +90° maps forward (+Y) to right (+X), matching
            // the setting's "stick pushed forward outputs as pushed right" example.
            val nx = x * c + y * s
            val ny = -x * s + y * c
            x = nx
            y = ny
        }
        // Steam-parity scale curve. Steam's H/V scale slider is NOT a linear
        // deflection multiplier — measured against Steam Input (L4D2, face-pad →
        // joystick, linear response curve, no deadzone) its 50% setting emits
        // ~0.65 deflection while 100% emits full and 0% emits none. That fits a
        // power curve deflection = scale^0.6 (concave: rises fast off zero, flattens
        // toward the top). Mappo previously multiplied linearly, which matched Steam
        // at 100% but felt weak everywhere below it. Applying the same curve here
        // brings the slider's feel in line with Steam. Per-axis so asymmetric
        // H/V scales still work.
        x *= horizontalScale.pow(SCALE_CURVE_EXPONENT)
        y *= verticalScale.pow(SCALE_CURVE_EXPONENT)
        if (invertHorizontal) x = -x
        if (invertVertical) y = -y
        when (outputAxis) {
            OutputAxis.HORIZONTAL -> y = 0f
            OutputAxis.VERTICAL -> x = 0f
            OutputAxis.BOTH -> Unit
        }
        return x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)
    }

    companion object {
        /**
         * Exponent for the Steam-parity scale curve (see [apply]). ~0.6 fits the
         * measured Steam mapping (50% → ~0.65 deflection). Tunable if further
         * calibration points refine the fit.
         */
        private const val SCALE_CURVE_EXPONENT = 0.6f

        val DEFAULTS = JoystickOutputSettings(
            horizontalScale = 1f,
            verticalScale = 1f,
            invertHorizontal = false,
            invertVertical = false,
            outputAxis = OutputAxis.BOTH,
            rotateDegrees = 0f,
            outputStick = OutputStick.RIGHT,
        )

        fun parse(json: String): JoystickOutputSettings {
            if (json.isBlank() || json == "{}") return DEFAULTS
            return try {
                val obj = JSONObject(json)
                JoystickOutputSettings(
                    horizontalScale = (obj.optDouble("horizontal_scale", 100.0).toFloat() / 100f).coerceIn(0f, 1f),
                    verticalScale = (obj.optDouble("vertical_scale", 100.0).toFloat() / 100f).coerceIn(0f, 1f),
                    invertHorizontal = obj.optBoolean("invert_horizontal", false),
                    invertVertical = obj.optBoolean("invert_vertical", false),
                    outputAxis = when (obj.optString("output_axis", "both")) {
                        "horizontal" -> OutputAxis.HORIZONTAL
                        "vertical" -> OutputAxis.VERTICAL
                        else -> OutputAxis.BOTH
                    },
                    rotateDegrees = obj.optDouble("rotate_output", 0.0).toFloat().coerceIn(-180f, 180f),
                    outputStick = if (obj.optString("output_joystick", "right") == "left") {
                        OutputStick.LEFT
                    } else {
                        OutputStick.RIGHT
                    },
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }

        /**
         * Synthesize a unit-ish stick vector from the set of held digital direction
         * sub-inputs. Handles both the face-button cluster (`button_*`) and the D-Pad
         * (`dpad_*`) — a source only uses one scheme, so accepting both is safe and lets
         * the same Joystick-mode synthesis serve both digital sources. Diagonals are
         * normalized to magnitude 1 so they don't read as a stronger push than a cardinal.
         */
        fun directionalVector(heldKeys: Set<String>): Pair<Float, Float> {
            var x = 0f
            var y = 0f
            if ("button_b" in heldKeys || "dpad_right" in heldKeys) x += 1f   // east
            if ("button_x" in heldKeys || "dpad_left" in heldKeys) x -= 1f    // west
            if ("button_y" in heldKeys || "dpad_up" in heldKeys) y += 1f      // north
            if ("button_a" in heldKeys || "dpad_down" in heldKeys) y -= 1f    // south
            val mag = hypot(x, y)
            return if (mag > 1f) (x / mag) to (y / mag) else x to y
        }
    }
}
