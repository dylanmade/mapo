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
 * walk into a [CompiledActionSet] per action set, each keyed by [InputAddress].
 *
 * Brick 4.2 widened this from "only the active set's inputs" to "every set's inputs":
 * runtime active-set switching (`CHANGE_PRESET`) needs every set immediately resolvable
 * without recompiling. [defaultActionSetId] is what the evaluator initializes its
 * runtime `activeSetId` to; the live active set after `CHANGE_PRESET` fires lives in
 * the evaluator's mutable state, not this snapshot.
 *
 * Layer stacking (Phase 5) is still unmodeled here — overlay groups don't appear in
 * any [CompiledActionSet.inputs] yet.
 */
data class CompiledConfig(
    val defaultActionSetId: Long,
    val sets: Map<Long, CompiledActionSet>,
) {
    /**
     * Resolve an address within a specific action set. Returns null if either the set
     * isn't in the snapshot or the address has no compiled input under it.
     */
    fun lookup(setId: Long, source: InputSource, inputKey: String): CompiledInput? =
        sets[setId]?.inputs?.get(InputAddress(source, inputKey))

    companion object {
        val EMPTY = CompiledConfig(defaultActionSetId = 0L, sets = emptyMap())
    }
}

/**
 * One action set's compiled lookup table. Each [InputAddress] in [inputs] resolves to
 * its full activator list, pre-parsed.
 */
data class CompiledActionSet(
    val actionSetId: Long,
    val inputs: Map<InputAddress, CompiledInput>,
)

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
 *  - Brick 3.2 added [doubleTapTimeMs].
 *  - Brick 3.3 adds universal settings: [toggle], [holdToRepeat] + [repeatRateMs],
 *    [fireStartDelayMs], [fireEndDelayMs], [cycleBindings], [interruptable], plus the
 *    chord-partner ([chordPartnerSource] / [chordPartnerKey]) for `CHORDED_PRESS`.
 *
 * The evaluator reads only the fields its activator type cares about.
 */
data class CompiledActivatorSettings(
    /** Steam-default 0.6 s; min hold time before [ActivatorType.LONG_PRESS] fires. */
    val longPressTimeMs: Long = 600L,
    /** Steam-default 0.19 s; max interval between the two taps of a [ActivatorType.DOUBLE_PRESS]. */
    val doubleTapTimeMs: Long = 190L,
    /**
     * When true, this activator's first fire latches the bindings on; the next fire toggles
     * them off. Releases on physical UP are suppressed while toggled-on. Steam default: off.
     */
    val toggle: Boolean = false,
    /** When true, pulse the bindings repeatedly at [repeatRateMs] while the activator is active. */
    val holdToRepeat: Boolean = false,
    /** Repeat interval for [holdToRepeat] in ms. Steam-default 150 ms. */
    val repeatRateMs: Long = 150L,
    /** Delay (ms) between activation conditions being met and the first emission. */
    val fireStartDelayMs: Long = 0L,
    /** Extra time (ms) the bindings stay active after physical release before releasing. */
    val fireEndDelayMs: Long = 0L,
    /**
     * When true, each fire advances to the next [BindingOutput] in the activator's bindings
     * list (round-robin). When false, every binding fires together (default).
     */
    val cycleBindings: Boolean = false,
    /**
     * When true, a longer/double activator on the same input suppresses this one when it
     * resolves. Only meaningful on `FULL_PRESS` and `RELEASE_PRESS`. Steam default: true.
     */
    val interruptable: Boolean = true,
    /**
     * Partner input that must be currently held for a [ActivatorType.CHORDED_PRESS] to fire.
     * Null on non-chord activators (and on chord activators that haven't picked a partner yet).
     */
    val chordPartnerSource: InputSource? = null,
    /** Partner sub-input key (e.g. `button_a`) — see [chordPartnerSource]. */
    val chordPartnerKey: String? = null,
) {
    /** Convenience accessor: the chord partner as an [InputAddress], or null if unconfigured. */
    val chordPartner: InputAddress?
        get() = chordPartnerSource?.let { src ->
            chordPartnerKey?.let { key -> InputAddress(src, key) }
        }

    companion object {
        const val DEFAULT_LONG_PRESS_TIME_MS = 600L
        const val DEFAULT_DOUBLE_TAP_TIME_MS = 190L
        const val DEFAULT_REPEAT_RATE_MS = 150L

        val DEFAULTS = CompiledActivatorSettings(
            longPressTimeMs = DEFAULT_LONG_PRESS_TIME_MS,
            doubleTapTimeMs = DEFAULT_DOUBLE_TAP_TIME_MS,
            toggle = false,
            holdToRepeat = false,
            repeatRateMs = DEFAULT_REPEAT_RATE_MS,
            fireStartDelayMs = 0L,
            fireEndDelayMs = 0L,
            cycleBindings = false,
            interruptable = true,
            chordPartnerSource = null,
            chordPartnerKey = null,
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
                    doubleTapTimeMs = obj.optLongOrNull("double_tap_time_ms") ?: DEFAULT_DOUBLE_TAP_TIME_MS,
                    toggle = obj.optBooleanOrNull("toggle") ?: false,
                    holdToRepeat = obj.optBooleanOrNull("hold_to_repeat") ?: false,
                    repeatRateMs = obj.optLongOrNull("repeat_rate_ms") ?: DEFAULT_REPEAT_RATE_MS,
                    fireStartDelayMs = obj.optLongOrNull("fire_start_delay_ms") ?: 0L,
                    fireEndDelayMs = obj.optLongOrNull("fire_end_delay_ms") ?: 0L,
                    cycleBindings = obj.optBooleanOrNull("cycle_bindings") ?: false,
                    interruptable = obj.optBooleanOrNull("interruptable") ?: true,
                    chordPartnerSource = obj.optInputSourceOrNull("chord_partner_source"),
                    chordPartnerKey = obj.optStringOrNull("chord_partner_key"),
                )
            } catch (e: JSONException) {
                Log.w(TAG, "Failed to parse activator settings JSON: $json", e)
                DEFAULTS
            }
        }

        private const val TAG = "ActivatorSettings"
    }

    /**
     * Serialize back to a settingsJson string. Inverse of [parse]. Default-valued fields
     * are still written so the row round-trips byte-stably; unrecognized keys from the
     * stored JSON aren't preserved.
     */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("long_press_time_ms", longPressTimeMs)
        obj.put("double_tap_time_ms", doubleTapTimeMs)
        obj.put("toggle", toggle)
        obj.put("hold_to_repeat", holdToRepeat)
        obj.put("repeat_rate_ms", repeatRateMs)
        obj.put("fire_start_delay_ms", fireStartDelayMs)
        obj.put("fire_end_delay_ms", fireEndDelayMs)
        obj.put("cycle_bindings", cycleBindings)
        obj.put("interruptable", interruptable)
        if (chordPartnerSource != null) obj.put("chord_partner_source", chordPartnerSource.name)
        if (chordPartnerKey != null) obj.put("chord_partner_key", chordPartnerKey)
        return obj.toString()
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONObject.optInputSourceOrNull(key: String): InputSource? {
    val raw = optStringOrNull(key) ?: return null
    return try {
        InputSource.valueOf(raw)
    } catch (e: IllegalArgumentException) {
        Log.w("ActivatorSettings", "Unknown InputSource '$raw' in $key", e)
        null
    }
}

/**
 * Flatten the materialized [ControllerConfig] graph into a [CompiledConfig] for the
 * runtime evaluator. Every action set is compiled — runtime set switching reads
 * directly from [CompiledConfig.sets] with no recompilation cost. Each set honors only
 * its "active"-state preset entries.
 *
 *  - Layer overrides are ignored (Phase 5).
 *  - Multi-binding activators (cycle_binding) keep all their bindings; the runtime
 *    cycle index is state owned by the evaluator, not the snapshot.
 *  - When the config has no action sets, [CompiledConfig.EMPTY] is returned.
 *  - [CompiledConfig.defaultActionSetId] mirrors `controllerProfile.defaultActionSetId`
 *    (falling back to the first set by orderIndex when the pointer is null/stale).
 */
fun ControllerConfig.toCompiled(): CompiledConfig {
    if (actionSets.isEmpty()) return CompiledConfig.EMPTY
    val compiledSets = HashMap<Long, CompiledActionSet>(actionSets.size)
    for (setGraph in actionSets) {
        val inputs = HashMap<InputAddress, CompiledInput>()
        for (preset in setGraph.preset) {
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
        compiledSets[setGraph.actionSet.id] = CompiledActionSet(setGraph.actionSet.id, inputs)
    }
    val defaultId = activeActionSet?.actionSet?.id ?: actionSets.first().actionSet.id
    return CompiledConfig(defaultActionSetId = defaultId, sets = compiledSets)
}
