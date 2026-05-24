package com.mapo.shizuku

/**
 * Stable identifiers for analog input sources transmitted across the
 * `:app` ↔ `:shizuku-service` AIDL boundary.
 *
 * **Why a separate constant table** (not just [com.mapo.data.model.steam.InputSource]'s
 * ordinals): `:shizuku-service` cannot depend on `:app`'s Steam schema, and ordinal
 * coupling silently breaks if anyone reorders the enum. Pinning explicit IDs here
 * means the AIDL contract is independent — :app side maps `id → InputSource` in
 * [com.mapo.service.shizuku.ShizukuMotionStream], the service side just emits IDs.
 *
 * Add new IDs at the bottom; **never renumber** existing ones (would break running
 * UserService binaries that still emit the old ID).
 */
object InputSourceId {
    const val UNKNOWN: Int = 0
    const val DPAD: Int = 1
    const val LEFT_JOYSTICK: Int = 2
    const val RIGHT_JOYSTICK: Int = 3
    const val LEFT_TRIGGER: Int = 4
    const val RIGHT_TRIGGER: Int = 5
}
