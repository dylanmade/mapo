package com.mapo.service.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolved, fire-ready haptic strength for a single activation. The "use activator
 * settings" option that appears in the per-source override dropdown is NOT a value
 * here — it's resolved away at compile time by [resolveEffectiveHaptic], which folds
 * the source override and the activator's own setting into one of these.
 *
 * [id] is the JSON token persisted in settingsJson (and the VDF round-trip form).
 */
enum class HapticIntensity(val id: String) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        /** Parse a stored intensity id (`off`/`low`/`medium`/`high`); null if unrecognized. */
        fun fromId(id: String?): HapticIntensity? = entries.firstOrNull { it.id == id }

        /** JSON key for an activator's own haptic strength (in Activator.settingsJson). */
        const val ACTIVATOR_KEY = "haptic_intensity"

        /** JSON key for the per-source override (in BindingGroup.settingsJson). */
        const val OVERRIDE_KEY = "haptic_intensity_override"

        /** Override value meaning "defer to the activator's own haptic_intensity." */
        const val OVERRIDE_USE_ACTIVATOR = "use_activator"
    }
}

/**
 * Fold the per-source override (from a binding group's settingsJson) and an activator's
 * own [activatorHaptic] into the effective intensity to fire. Called at COMPILE time
 * (not the per-event hot path), so JSON parsing here is fine.
 *
 *  - override missing / blank / "use_activator" → [activatorHaptic]
 *  - override = off/low/medium/high → that intensity (overrides the activator)
 *
 * [groupSettingsJson] is the raw `BindingGroup.settingsJson`; we read only the
 * [HapticIntensity.OVERRIDE_KEY] string, tolerant of any other shape.
 */
fun resolveEffectiveHaptic(
    groupSettingsJson: String,
    activatorHaptic: HapticIntensity,
): HapticIntensity {
    if (groupSettingsJson.isBlank() || groupSettingsJson == "{}") return activatorHaptic
    val raw = try {
        org.json.JSONObject(groupSettingsJson).optString(HapticIntensity.OVERRIDE_KEY, "")
    } catch (_: Exception) {
        ""
    }
    if (raw.isBlank() || raw == HapticIntensity.OVERRIDE_USE_ACTIVATOR) return activatorHaptic
    return HapticIntensity.fromId(raw) ?: activatorHaptic
}

/**
 * Emits device vibration when a remapped input fires. On the handhelds Mapo targets
 * (AYN Thor etc.) the device's own vibration motor *is* the controller rumble; routing
 * rumble to an external Bluetooth gamepad is a separate future path.
 *
 * Driven from [InputEvaluator]'s fire sites with the activator's pre-resolved
 * [HapticIntensity]. [buzz] is a fast, fire-and-forget system call made AFTER the key
 * inject, so it stays off the input-injection critical path (latency).
 */
@Singleton
class HapticEmitter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** One short vibration pulse at [intensity]. No-op for [HapticIntensity.OFF]. */
    fun buzz(intensity: HapticIntensity) {
        if (intensity == HapticIntensity.OFF) return
        val v = vibrator ?: return
        val (durationMs, amplitude) = when (intensity) {
            HapticIntensity.OFF -> return
            HapticIntensity.LOW -> 15L to 70
            HapticIntensity.MEDIUM -> 25L to 150
            HapticIntensity.HIGH -> 40L to 255
        }
        try {
            // createOneShot honors amplitude only where hasAmplitudeControl(); elsewhere
            // the duration still differentiates the three levels.
            val amp = if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed for $intensity", e)
        }
    }

    private companion object {
        const val TAG = "HapticEmitter"
    }
}
