package com.mappo.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the AIDL boundary's axis mapping. These constants are
 * shared between `:app` and `:shizuku-service` — keeping them honest matters
 * because the user-service binary runs in a separate process where a mapping
 * bug shows up as "no motion events" with no obvious error.
 */
class LinuxInputConstantsTest {

    @Test
    fun mapAbsToSource_handlesLeftStick() {
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.LEFT_JOYSTICK, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_X),
        )
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.LEFT_JOYSTICK, 1),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_Y),
        )
    }

    @Test
    fun mapAbsToSource_handlesRightStick_androidConvention() {
        // AYN Thor verified 2026-05-24: right stick emits ABS_Z (X) + ABS_RZ (Y).
        // Same Android-convention applies to Odin 2 / Mini, Anbernic, Retroid.
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.RIGHT_JOYSTICK, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_Z),
        )
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.RIGHT_JOYSTICK, 1),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_RZ),
        )
        // Alternate (some pads expose right stick on RX/RY instead). Same source,
        // so downstream sees a single combined flow regardless of which the
        // device chose.
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.RIGHT_JOYSTICK, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_RX),
        )
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.RIGHT_JOYSTICK, 1),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_RY),
        )
    }

    @Test
    fun mapAbsToSource_handlesTriggers_androidConvention() {
        // Thor verified 2026-05-24: triggers emit ABS_BRAKE (LT) + ABS_GAS (RT).
        // ABS_Z / ABS_RZ are NOT trigger codes on Mappo's target devices — they're
        // right-stick axes (see mapAbsToSource_handlesRightStick_androidConvention).
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.LEFT_TRIGGER, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_BRAKE),
        )
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.RIGHT_TRIGGER, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_GAS),
        )
    }

    @Test
    fun mapAbsToSource_handlesDpadHat() {
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.DPAD, 0),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_HAT0X),
        )
        assertEquals(
            LinuxInputConstants.SourceAxis(InputSourceId.DPAD, 1),
            LinuxInputConstants.mapAbsToSource(LinuxInputConstants.ABS_HAT0Y),
        )
    }

    @Test
    fun mapAbsToSource_returnsNull_forUnknownAxisCodes() {
        // ABS_PRESSURE (0x18) — stylus pressure. Not gamepad-relevant.
        assertNull(LinuxInputConstants.mapAbsToSource(0x18))
        // ABS_MISC (0x28). Same.
        assertNull(LinuxInputConstants.mapAbsToSource(0x28))
    }

    @Test
    fun isMultiTouchAbs_rangeBounds() {
        // Touchscreens hit this range immediately on first touch — reject fast.
        assertTrue(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_SLOT))
        assertTrue(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_TOUCH_MAJOR))
        assertTrue(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_POSITION_X))
        assertTrue(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_POSITION_Y))
        assertTrue(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_LAST))
        assertFalse(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_MT_LAST + 1))
        assertFalse(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_X))
        assertFalse(LinuxInputConstants.isMultiTouchAbs(LinuxInputConstants.ABS_HAT0Y))
    }

    @Test
    fun eventStructSize_isArm64Layout() {
        // arm64: timeval[16] + type[2] + code[2] + value[4] = 24.
        // 32-bit Android (which we don't support post-API-30) would be 16.
        // Hard-coded value protects against an accidental architecture-aware
        // rewrite breaking the parser silently.
        assertEquals(24, LinuxInputConstants.EVENT_SIZE_BYTES)
    }
}
