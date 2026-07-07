package com.mappo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DigitalDirectionalPadTest {

    @Test
    fun parse_readsLayoutId_defaultsToEightWay() {
        assertEquals(DirectionalPadLayout.EIGHT_WAY, DirectionalPadLayout.parse(""))
        assertEquals(DirectionalPadLayout.EIGHT_WAY, DirectionalPadLayout.parse("{}"))
        assertEquals(DirectionalPadLayout.EIGHT_WAY, DirectionalPadLayout.parse("""{"dpad_layout":"8_way"}"""))
        assertEquals(DirectionalPadLayout.FOUR_WAY, DirectionalPadLayout.parse("""{"dpad_layout":"4_way"}"""))
        assertEquals(DirectionalPadLayout.CROSS_GATE, DirectionalPadLayout.parse("""{"dpad_layout":"cross_gate"}"""))
        assertEquals(DirectionalPadLayout.ANALOG_EMULATION, DirectionalPadLayout.parse("""{"dpad_layout":"analog_emulation"}"""))
        // Unknown id falls back.
        assertEquals(DirectionalPadLayout.EIGHT_WAY, DirectionalPadLayout.parse("""{"dpad_layout":"wat"}"""))
    }

    @Test
    fun isDigitalPassthrough_eightWayAndCrossGate() {
        assertEquals(true, DirectionalPadLayout.EIGHT_WAY.isDigitalPassthrough)
        // Cross-gate allows diagonals on a digital cluster (its bias is analog-only).
        assertEquals(true, DirectionalPadLayout.CROSS_GATE.isDigitalPassthrough)
        // Analog emulation must be intercepted to pulse — not a passthrough.
        assertEquals(false, DirectionalPadLayout.ANALOG_EMULATION.isDigitalPassthrough)
        assertEquals(false, DirectionalPadLayout.FOUR_WAY.isDigitalPassthrough)
    }

    @Test
    fun activeInputs_eightWayCrossGateAnalog_passAllHeld() {
        // All keep both held directions active (diagonals allowed); analog-emulation
        // additionally pulses them, cross-gate's bias is analog-only.
        val held = listOf("button_y", "button_b")
        assertEquals(held.toSet(), activeDirectionalInputs(DirectionalPadLayout.EIGHT_WAY, held))
        assertEquals(held.toSet(), activeDirectionalInputs(DirectionalPadLayout.CROSS_GATE, held))
        assertEquals(held.toSet(), activeDirectionalInputs(DirectionalPadLayout.ANALOG_EMULATION, held))
    }

    @Test
    fun activeInputs_fourWay_latestPressWins() {
        assertEquals(setOf("button_b"), activeDirectionalInputs(DirectionalPadLayout.FOUR_WAY, listOf("button_y", "button_b")))
        assertEquals(setOf("button_y"), activeDirectionalInputs(DirectionalPadLayout.FOUR_WAY, listOf("button_b", "button_y")))
        assertEquals(emptySet<String>(), activeDirectionalInputs(DirectionalPadLayout.FOUR_WAY, emptyList()))
    }

}
