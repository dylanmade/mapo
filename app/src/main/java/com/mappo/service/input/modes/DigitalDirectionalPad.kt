package com.mappo.service.input.modes

import org.json.JSONException
import org.json.JSONObject

/**
 * Directional-pad layout for a source in DPAD mode. The four layouts differ only in how
 * *simultaneous* directional inputs collapse — the logic here is geometry-free (it works
 * off press order), so it serves both the digital button cluster and the real D-Pad.
 *
 * Behavior (per the Steam reference + the settings helper text):
 *  - [EIGHT_WAY]: all held directions fire (diagonals = both commands).
 *  - [FOUR_WAY]: one at a time, latest press wins ("diagonals → single nearest").
 *  - [CROSS_GATE]: diagonals ARE allowed; its distinction is a *deflection-magnitude*
 *    bias (diagonals harder to hit near center, easier at the edge) that only an analog
 *    source can express — see [com.mappo.service.input.modes.DpadMode]. On a digital
 *    cluster (always full deflection) there's no magnitude to bias, so it behaves as
 *    [EIGHT_WAY].
 *  - [ANALOG_EMULATION]: a *digital* dpad emulating an analog stick — the held direction's
 *    command is PWM-pulsed (rapid on/off) so the game perceives analog-ish movement.
 *    Diagonals are allowed (a stick can be diagonal), so it acts like 8-way but pulsed.
 */
enum class DirectionalPadLayout(val id: String) {
    EIGHT_WAY("8_way"),
    FOUR_WAY("4_way"),
    ANALOG_EMULATION("analog_emulation"),
    CROSS_GATE("cross_gate");

    /**
     * True when this layout passes every held direction straight through to the normal
     * per-button path — i.e. the runtime can skip the gating/pulsing layer. 8-way and
     * cross-gate both allow all held directions on a digital cluster (cross-gate's bias
     * is analog-only); 4-way needs single-active collapsing and analog-emulation needs
     * pulsing, so those are intercepted.
     */
    val isDigitalPassthrough: Boolean
        get() = this == EIGHT_WAY || this == CROSS_GATE

    companion object {
        fun fromId(id: String?): DirectionalPadLayout = entries.firstOrNull { it.id == id } ?: EIGHT_WAY

        fun parse(settingsJson: String): DirectionalPadLayout {
            if (settingsJson.isBlank() || settingsJson == "{}") return EIGHT_WAY
            return try {
                fromId(JSONObject(settingsJson).optString("dpad_layout", EIGHT_WAY.id))
            } catch (_: JSONException) {
                EIGHT_WAY
            }
        }
    }
}

/**
 * Which directional sub-inputs are active (fire their bindings) given the press-ordered
 * held list and the [layout]. [EIGHT_WAY]/[ANALOG_EMULATION] return all held (passthrough);
 * [FOUR_WAY] returns the latest-pressed; [CROSS_GATE] returns the first-pressed.
 */
fun activeDirectionalInputs(layout: DirectionalPadLayout, heldInOrder: List<String>): Set<String> =
    when (layout) {
        // Cross-gate allows diagonals on a digital cluster (its bias is analog-only).
        DirectionalPadLayout.EIGHT_WAY,
        DirectionalPadLayout.ANALOG_EMULATION,
        DirectionalPadLayout.CROSS_GATE -> heldInOrder.toSet()
        DirectionalPadLayout.FOUR_WAY -> heldInOrder.lastOrNull()?.let { setOf(it) } ?: emptySet()
    }
