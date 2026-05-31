package com.mapo.shizuku

/**
 * Linux kernel input subsystem constants mirrored from `linux/input-event-codes.h`.
 * Used by `:shizuku-service` to parse `/dev/input/event*` records without dragging
 * in the full kernel headers via JNI.
 *
 * **Struct layout** (arm64 Android, the only Mapo target):
 * ```
 * struct input_event {
 *   struct timeval time;  // 16 bytes (tv_sec[8] + tv_usec[8])
 *   __u16 type;           //  2 bytes
 *   __u16 code;           //  2 bytes
 *   __s32 value;          //  4 bytes
 * };  // total: 24 bytes
 * ```
 * 32-bit Linux builds would use 16-byte input_event (smaller `time_t`), but Android
 * 64-bit-only enforcement on API 30+ means we don't need to handle that case.
 */
object LinuxInputConstants {

    /** Size of one `struct input_event` on arm64. */
    const val EVENT_SIZE_BYTES: Int = 24

    // ── Event types (struct input_event.type) ────────────────────────────────
    const val EV_SYN: Int = 0x00
    const val EV_KEY: Int = 0x01
    const val EV_REL: Int = 0x02
    const val EV_ABS: Int = 0x03
    const val EV_MSC: Int = 0x04
    const val EV_SW: Int = 0x05

    // ── EV_SYN codes ─────────────────────────────────────────────────────────
    /** Marker that the current event group (preceding EV_ABS/EV_KEY/etc.)
     *  is complete and ready to dispatch. Used to coalesce per-axis bursts. */
    const val SYN_REPORT: Int = 0x00

    // ── EV_ABS axis codes we care about for gamepads ─────────────────────────
    const val ABS_X: Int = 0x00
    const val ABS_Y: Int = 0x01
    const val ABS_Z: Int = 0x02
    const val ABS_RX: Int = 0x03
    const val ABS_RY: Int = 0x04
    const val ABS_RZ: Int = 0x05
    const val ABS_GAS: Int = 0x09
    const val ABS_BRAKE: Int = 0x0A
    const val ABS_HAT0X: Int = 0x10
    const val ABS_HAT0Y: Int = 0x11

    // ── Multi-touch axis range (reject these — they identify touchscreens) ───
    const val ABS_MT_SLOT: Int = 0x2F
    const val ABS_MT_TOUCH_MAJOR: Int = 0x30
    const val ABS_MT_POSITION_X: Int = 0x35
    const val ABS_MT_POSITION_Y: Int = 0x36
    const val ABS_MT_LAST: Int = 0x3D

    // ── EV_KEY mouse button codes ────────────────────────────────────────────
    // Used by :app to call `injectMouseButton(btnCode, pressed)` on Mapo's
    // virtual mouse — sent across the AIDL boundary as ints so neither side
    // has to depend on linux/input-event-codes.h. Five-button standard
    // mouse: left/right/middle plus side/extra for browser-back/-forward.
    const val BTN_LEFT: Int = 0x110
    const val BTN_RIGHT: Int = 0x111
    const val BTN_MIDDLE: Int = 0x112
    const val BTN_SIDE: Int = 0x113
    const val BTN_EXTRA: Int = 0x114

    /** `true` if [code] is in the multi-touch range — i.e. emitted only by touchscreens. */
    fun isMultiTouchAbs(code: Int): Boolean = code in ABS_MT_SLOT..ABS_MT_LAST

    /**
     * Map an `EV_ABS` code to the [InputSourceId] it belongs to, plus the axis
     * index within that source (0 = primary/x, 1 = secondary/y).
     *
     * Returns `null` for axis codes that don't correspond to a gamepad source.
     *
     * **Mapping conventions** follow Android-handheld convention (AYN Thor,
     * Odin 2 / Mini, Anbernic, Retroid — Mapo's target devices). Verified on
     * the Thor 2026-05-24: right stick emits `ABS_Z` / `ABS_RZ`, triggers emit
     * `ABS_BRAKE` / `ABS_GAS`.
     *
     *  - `ABS_X` / `ABS_Y` → LEFT_JOYSTICK (x, y).
     *  - `ABS_Z` / `ABS_RZ` → RIGHT_JOYSTICK (x, y). **Android convention.**
     *    Pure XInput PC controllers (Xbox/etc.) use Z/RZ for triggers instead;
     *    on those controllers the triggers would be misread as right stick.
     *    Not a target platform — disambiguation via `EVIOCGBIT` capability
     *    queries lands as a follow-up only if XInput-direct controllers become
     *    a real ask.
     *  - `ABS_RX` / `ABS_RY` → RIGHT_JOYSTICK (x, y). Alternate right-stick
     *    mapping some controllers use; routes to the same source so a single
     *    AnalogReading flow downstream serves both.
     *  - `ABS_BRAKE` → LEFT_TRIGGER (single axis at index 0).
     *  - `ABS_GAS` → RIGHT_TRIGGER (single axis at index 0).
     *  - `ABS_HAT0X` / `ABS_HAT0Y` → DPAD (x, y, integer values −1/0/+1).
     */
    fun mapAbsToSource(code: Int): SourceAxis? = when (code) {
        ABS_X -> SourceAxis(InputSourceId.LEFT_JOYSTICK, axisIndex = 0)
        ABS_Y -> SourceAxis(InputSourceId.LEFT_JOYSTICK, axisIndex = 1)
        ABS_Z -> SourceAxis(InputSourceId.RIGHT_JOYSTICK, axisIndex = 0)
        ABS_RZ -> SourceAxis(InputSourceId.RIGHT_JOYSTICK, axisIndex = 1)
        ABS_RX -> SourceAxis(InputSourceId.RIGHT_JOYSTICK, axisIndex = 0)
        ABS_RY -> SourceAxis(InputSourceId.RIGHT_JOYSTICK, axisIndex = 1)
        ABS_BRAKE -> SourceAxis(InputSourceId.LEFT_TRIGGER, axisIndex = 0)
        ABS_GAS -> SourceAxis(InputSourceId.RIGHT_TRIGGER, axisIndex = 0)
        ABS_HAT0X -> SourceAxis(InputSourceId.DPAD, axisIndex = 0)
        ABS_HAT0Y -> SourceAxis(InputSourceId.DPAD, axisIndex = 1)
        else -> null
    }

    data class SourceAxis(val sourceId: Int, val axisIndex: Int)
}
