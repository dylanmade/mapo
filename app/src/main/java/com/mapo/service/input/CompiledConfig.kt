package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource
import org.json.JSONException
import org.json.JSONObject

/**
 * Runtime-optimized snapshot of a [ControllerConfig], shaped for O(1) per-event lookup.
 *
 * The materialized graph is great for editor UIs but terrible for per-event work:
 * resolving a single physical press would have to walk action set → preset entries →
 * binding group → group inputs → activators every time. [CompiledConfig] flattens that
 * walk into a single map keyed by [InputAddress].
 *
 * Phase 2 honors only the base action set's "active"-state preset entries — action set
 * switching (Phase 4) and layer stacking (Phase 5) will recompile when their state changes.
 */
data class CompiledConfig(
    val activeActionSetId: Long,
    val inputs: Map<InputAddress, CompiledInput>,
) {
    fun lookup(source: InputSource, inputKey: String): CompiledInput? =
        inputs[InputAddress(source, inputKey)]

    companion object {
        val EMPTY = CompiledConfig(activeActionSetId = 0L, inputs = emptyMap())
    }
}

/**
 * Identifies a sub-input within a binding group. The unit of activator evaluation —
 * `(BUTTON_DIAMOND, "button_a")`, `(DPAD, "dpad_north")`, `(LEFT_TRIGGER, "click")`, etc.
 */
data class InputAddress(
    val source: InputSource,
    val inputKey: String,
)

/** All activators wired to one [InputAddress]. */
data class CompiledInput(
    val groupInputId: Long,
    val activators: List<CompiledActivator>,
)

/**
 * One activator pre-resolved with its typed [BindingOutput]s plus parsed [settings].
 *
 * Brick 2.1 carried only `(id, type, bindings)`; Brick 3.1 extends this with the parsed
 * [CompiledActivatorSettings] so the evaluator's timing logic (long-press threshold, etc.)
 * doesn't have to touch JSON at the per-event hot path.
 */
data class CompiledActivator(
    val activatorId: Long,
    val type: ActivatorType,
    val bindings: List<BindingOutput>,
    val settings: CompiledActivatorSettings = CompiledActivatorSettings.DEFAULTS,
)

/**
 * Decoded view of an [com.mapo.data.model.steam.Activator]'s settingsJson, with every
 * setting either populated from JSON or filled with the Steam-Input default.
 *
 * Designed as an all-defaults bag rather than a sealed class so it can grow over Phase 3:
 *  - Brick 3.1 surfaces [longPressTimeMs].
 *  - Brick 3.2 will add `doubleTapTimeMs`.
 *  - Brick 3.3 will add universal settings (toggle, turbo, fire delays, cycle, chord).
 *
 * The evaluator reads only the fields its activator type cares about.
 */
data class CompiledActivatorSettings(
    /** Steam-default 0.6 s; min hold time before [ActivatorType.LONG_PRESS] fires. */
    val longPressTimeMs: Long,
) {
    companion object {
        const val DEFAULT_LONG_PRESS_TIME_MS = 600L

        val DEFAULTS = CompiledActivatorSettings(
            longPressTimeMs = DEFAULT_LONG_PRESS_TIME_MS,
        )

        /**
         * Parse the Activator's settingsJson into a [CompiledActivatorSettings]. Unknown
         * keys are ignored (forward-compat); missing keys fall back to defaults; parse
         * errors log and return [DEFAULTS] so a malformed row can't crash the evaluator.
         */
        fun parse(json: String): CompiledActivatorSettings {
            if (json.isBlank() || json == "{}") return DEFAULTS
            return try {
                val obj = JSONObject(json)
                CompiledActivatorSettings(
                    longPressTimeMs = obj.optLongOrNull("long_press_time_ms") ?: DEFAULT_LONG_PRESS_TIME_MS,
                )
            } catch (e: JSONException) {
                Log.w(TAG, "Failed to parse activator settings JSON: $json", e)
                DEFAULTS
            }
        }

        private const val TAG = "ActivatorSettings"
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

/**
 * Flatten the materialized [ControllerConfig] graph into a [CompiledConfig] for the
 * runtime evaluator. Honors only "active"-state preset entries on the base action set.
 *
 *  - Layer overrides are ignored (Phase 5).
 *  - Multi-binding activators (cycle_binding) keep all their bindings; the runtime
 *    cycle index is state owned by the evaluator, not the snapshot.
 *  - When the config has no action sets, [CompiledConfig.EMPTY] is returned.
 */
fun ControllerConfig.toCompiled(): CompiledConfig {
    val activeSet = activeActionSet ?: return CompiledConfig.EMPTY
    val inputs = HashMap<InputAddress, CompiledInput>()
    for (preset in activeSet.preset) {
        if (preset.state != "active") continue
        for (inputGraph in preset.group.inputs) {
            val address = InputAddress(preset.inputSource, inputGraph.input.inputKey)
            val compiledActivators = inputGraph.activators.map { actGraph ->
                CompiledActivator(
                    activatorId = actGraph.activator.id,
                    type = actGraph.activator.type,
                    bindings = actGraph.bindings.map { BindingOutput.fromEntity(it.outputType, it.args) },
                    settings = CompiledActivatorSettings.parse(actGraph.activator.settingsJson),
                )
            }
            inputs[address] = CompiledInput(inputGraph.input.id, compiledActivators)
        }
    }
    return CompiledConfig(activeSet.actionSet.id, inputs)
}
