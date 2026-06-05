package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.modes.StubMode
import com.mapo.service.input.modes.acceptsFor
import com.mapo.service.input.modes.handler
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
 * without recompiling. [startingActionSetId] is what the evaluator initializes its
 * runtime `activeSetId` to; the live active set after `CHANGE_PRESET` fires lives in
 * the evaluator's mutable state, not this snapshot.
 *
 * Brick 5.1 adds [CompiledActionSet.layers] so the evaluator can stack overlay groups
 * (last-in-wins) on top of the base set's inputs. The runtime layer-stack state still
 * lives in the evaluator — this snapshot only carries the *available* layers and their
 * overlays per set. The compiler currently produces layer rows with empty `inputs`
 * maps (no per-layer preset-binding schema yet — wired in a later brick); the runtime
 * machinery is testable end-to-end via tests that synthesize overlays directly.
 */
data class CompiledConfig(
    val startingActionSetId: Long,
    val sets: Map<Long, CompiledActionSet>,
    /**
     * Phase 7 Brick B.5: every binding_group in the materialized config compiled to
     * a source-agnostic form keyed by group id. Mode-shift target groups resolve
     * through this map at activation — each [CompiledModeShift] carries its
     * `targetGroupId`, and the evaluator looks the group up here to get the
     * pre-compiled mode + sub-input → activators it should layer over the
     * target source while the trigger is held.
     */
    val compiledGroups: Map<Long, CompiledBindingGroup> = emptyMap(),
) {
    /**
     * Resolve an address within a specific action set. Returns null if either the set
     * isn't in the snapshot or the address has no compiled input under it. Does NOT
     * consult layer overlays — that's a runtime concern (the evaluator's layer stack
     * decides which overlay wins).
     */
    fun lookup(setId: Long, source: InputSource, inputKey: String): CompiledInput? =
        sets[setId]?.inputs?.get(InputAddress(source, inputKey))

    companion object {
        val EMPTY = CompiledConfig(startingActionSetId = 0L, sets = emptyMap())
    }
}

/**
 * Phase 7 Brick B.5 — source-agnostic compiled form of a binding_group. Used by
 * mode-shift activation: when a [CompiledModeShift] activates, the evaluator
 * looks up `compiledGroups[targetGroupId]` here and overlays its [mode] +
 * [inputs] onto the target source for as long as the trigger is held.
 *
 * The compile step doesn't pre-validate sub-input keys against any particular
 * source (this group might never be activated, or might be activated against
 * multiple sources across its lifetime). Validation happens at activation
 * time against the actual target source via [validInputsFor].
 */
data class CompiledBindingGroup(
    val groupId: Long,
    val mode: BindingMode,
    val modeSettingsJson: String,
    /** sub-input key → activators */
    val inputs: Map<String, List<CompiledActivator>>,
)

/**
 * One action set's compiled lookup table. Each [InputAddress] in [inputs] resolves to
 * its full activator list, pre-parsed. [layers] holds every layer available on this
 * set, keyed by layer id; the evaluator picks which (if any) are currently active.
 */
data class CompiledActionSet(
    val actionSetId: Long,
    val inputs: Map<InputAddress, CompiledInput>,
    val layers: Map<Long, CompiledLayer> = emptyMap(),
    /**
     * Phase 7 Brick A: sources whose mode is [BindingMode.NONE] in this set — the
     * "Mapo intercepts and silences" mode. Maintained separately from [inputs]
     * because NONE-mode sources have no bindable sub-inputs (validInputsFor
     * returns emptySet), so they'd otherwise be invisible to the runtime. The
     * evaluator consults this set to consume-without-emit on a NONE source's
     * events. [BindingMode.DEVICE_DEFAULT] (pass-through) is the absence of any
     * entry in either [inputs] or this set — the evaluator's natural "no
     * binding → return false" path handles it.
     */
    val noneModeSources: Set<InputSource> = emptySet(),
    /**
     * Phase 7 Brick B.5 — mode shifts owned by this action set. Each is a
     * `(ownerSource, triggerAddress, targetGroupId)` triple. The runtime
     * indexes [InputEvaluator] watches every digital press for a trigger
     * match — on a hit, the shift is appended to the evaluator's active list
     * for as long as the trigger is held. Set-owned shifts are always
     * present while the action set is active.
     */
    val modeShifts: List<CompiledModeShift> = emptyList(),
)

/**
 * One stacking overlay on a [CompiledActionSet]. [inputs] holds only the addresses
 * the layer overrides — addresses absent from this map fall through to the base set
 * (or to a lower-priority layer in the active stack). Layer stacking semantics live
 * in the evaluator: later-activated layers win conflicts; re-activating an already-
 * active layer is a no-op (Steam docs).
 */
data class CompiledLayer(
    val layerId: Long,
    val inputs: Map<InputAddress, CompiledInput>,
    /**
     * Phase 7 Brick B.5 — mode shifts owned by this layer. Only active while
     * the layer is in the active stack; mirrors [CompiledActionSet.modeShifts]
     * shape exactly.
     */
    val modeShifts: List<CompiledModeShift> = emptyList(),
)

/**
 * Phase 7 Brick B.5 — pre-compiled mode shift definition. [ownerSource] is the
 * source whose mode/bindings are overridden while the trigger is held;
 * [triggerAddress] is the physical input whose DOWN appends this to the
 * evaluator's active list and whose UP removes it; [targetGroupId] resolves
 * via [CompiledConfig.compiledGroups] to the override's mode + sub-input
 * bindings.
 *
 * Shifts without an assigned trigger (user added the row but hasn't picked a
 * trigger yet) are skipped by the compile step — there's nothing to match
 * against, so they can never activate.
 */
data class CompiledModeShift(
    val ownerSource: InputSource,
    val triggerAddress: InputAddress,
    val targetGroupId: Long,
)

/**
 * Identifies a sub-input within a binding group. The unit of activator evaluation —
 * `(BUTTON_DIAMOND, "button_a")`, `(DPAD, "dpad_north")`, `(LEFT_TRIGGER, "click")`, etc.
 */
data class InputAddress(
    val source: InputSource,
    val inputKey: String,
)

/**
 * Sentinel sub-input key used to expose a source-level mode in
 * [CompiledActionSet.inputs] for sources that have no bindable sub-inputs
 * (today: just [InputSource.GYRO] — gyro modes emit continuous output, not
 * synthetic edges). Compile path adds an `InputAddress(source, this)` entry
 * with the configured mode + settings so [findSourceModeFor] and gating
 * predicates (e.g. [GyroLifecycleCoordinator]) can detect the mode without a
 * separate per-source lookup table. Empty string is safe — real physical-key
 * dispatch always carries a non-empty inputKey like `"click"` / `"button_a"`
 * / `"dpad_up"`, so the sentinel can't collide with onKeyEvent's address
 * builder.
 */
const val SOURCE_MODE_SENTINEL_KEY: String = ""

/**
 * All activators wired to one [InputAddress], plus the [BindingMode] this address's
 * binding group was configured under. The mode determines how the evaluator interprets
 * the source's events — digital modes route directly via [onKeyEvent]; analog modes
 * (`JOYSTICK_*`, `MOUSE_*`, `TRIGGER` soft-press, etc.) consume `MotionEvent`s through
 * the source's [com.mapo.service.input.modes.SourceMode] handler. Brick 1 of Phase 6:
 * mode is now carried in the compiled snapshot; runtime consumption lands in Brick 5+.
 */
data class CompiledInput(
    val groupInputId: Long,
    val activators: List<CompiledActivator>,
    val mode: BindingMode,
    /**
     * The binding_group's `settingsJson` — duplicated onto every sub-input under
     * the group so the evaluator's analog-mode dispatcher can hand it to the
     * mode handler ([SourceMode.evaluate][com.mapo.service.input.modes.SourceMode.evaluate])
     * without an extra lookup. Defaults to `""` so test fixtures that don't care
     * about mode settings don't need to thread a value through every constructor.
     */
    val modeSettingsJson: String = "",
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
    /**
     * Haptic strength to fire on each activation — the per-source override folded over
     * this activator's own [CompiledActivatorSettings.hapticIntensity] at compile time
     * (see [resolveEffectiveHaptic]). Pre-resolved here so the evaluator's hot path is a
     * field read, never a JSON parse. Defaults to OFF for test/builder construction.
     */
    val effectiveHaptic: HapticIntensity = HapticIntensity.OFF,
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
     * When true, a more-specific activator on the same input suppresses this one
     * when it resolves. Per Steam: meaningful on `FULL_PRESS`, `RELEASE_PRESS`,
     * and `CHORDED_PRESS`. Steam default: true.
     *
     * Specificity precedence (most → least specific): LONG_PRESS / DOUBLE_PRESS /
     * CHORDED_PRESS > FULL_PRESS > RELEASE_PRESS. With this flag on:
     *  - FULL_PRESS: yields to coexisting LONG/DOUBLE (deferred at DOWN) and to
     *    CHORDED_PRESS (suppressed at DOWN when the chord's partner is held).
     *  - RELEASE_PRESS: yields to LONG/DOUBLE/CHORD that fired during this press
     *    cycle — its UP-side `emitTap` is suppressed.
     *  - CHORDED_PRESS: yields to coexisting LONG_PRESS (deferred at DOWN, fires
     *    retroactively on UP if partner still held). CHORD+DOUBLE coexistence
     *    intentionally not handled (chord fires synchronously even when
     *    interruptable=true) — DOUBLE's 190ms window would add an awkward chord
     *    latency. Document as a known gap.
     */
    val interruptable: Boolean = true,
    /**
     * When true, mouse-output bindings on this activator emit through Android's
     * `AccessibilityService.dispatchGesture` (synthetic touch) instead of the
     * default uinput virtual-mouse path (`BTN_LEFT` / `BTN_RIGHT` / `REL_WHEEL`).
     *
     * Why this exists: emulator frontends with their own input layers (RetroArch
     * libretro-pointer cores, GameNative's touch wrapper) consume synthetic
     * touch events but ignore real mouse buttons. Standard Android apps respond
     * to either, but prefer real mouse semantics. Default false (real mouse).
     * Users flip on per-binding for emulator compatibility. No effect on
     * non-mouse outputs (keyboard, gamepad).
     */
    val sendAsGesture: Boolean = false,
    /**
     * This activator's own haptic strength. The per-source "Haptic intensity override"
     * (on the binding group) can override this; the two are folded into
     * [CompiledActivator.effectiveHaptic] at compile time via [resolveEffectiveHaptic].
     * Steam default: off (button presses don't rumble unless opted in).
     */
    val hapticIntensity: HapticIntensity = HapticIntensity.OFF,
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
            sendAsGesture = false,
            hapticIntensity = HapticIntensity.OFF,
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
                    sendAsGesture = obj.optBooleanOrNull("send_as_gesture") ?: false,
                    hapticIntensity = HapticIntensity.fromId(obj.optStringOrNull(HapticIntensity.ACTIVATOR_KEY))
                        ?: HapticIntensity.OFF,
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
        obj.put("send_as_gesture", sendAsGesture)
        obj.put(HapticIntensity.ACTIVATOR_KEY, hapticIntensity.id)
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
 *  - Multi-binding activators (cycle_binding) keep all their bindings; the runtime
 *    cycle index is state owned by the evaluator, not the snapshot.
 *  - When the config has no action sets, [CompiledConfig.EMPTY] is returned.
 *  - [CompiledConfig.startingActionSetId] is the first set by orderIndex (Steam's
 *    starting-set convention). Runtime set-switching is the evaluator's job.
 *  - Layer rows (Brick 5.1) materialize with their own `inputs` map. Brick 5.5.a
 *    adds the per-layer preset-binding schema (`ActionLayerGraph.preset`), so each
 *    layer's overrides are now folded into [CompiledLayer.inputs] exactly the same
 *    shape as a set's `inputs` — just keyed under the layer instead of the set. The
 *    runtime evaluator (Brick 5.1) already walks the active layer stack top-down.
 */
private const val TAG_COMPILE = "CompiledConfig"

fun ControllerConfig.toCompiled(): CompiledConfig {
    if (actionSets.isEmpty()) return CompiledConfig.EMPTY

    data class CompileResult(
        val inputs: Map<InputAddress, CompiledInput>,
        val noneSources: Set<InputSource>,
    )

    fun compileInputs(presetEntries: List<com.mapo.data.model.steam.PresetEntry>): CompileResult {
        val inputs = HashMap<InputAddress, CompiledInput>()
        val noneSources = HashSet<InputSource>()
        for (preset in presetEntries) {
            if (preset.state != "active") continue
            // Validate sub-input keys against the source-aware Steam vocabulary.
            // Phase 7 Brick A: switched from mode-only `SourceMode.accepts` to the
            // source-and-mode-aware `acceptsFor(source, mode, inputKey)` since the
            // valid sub-input set depends on both — e.g. face-buttons-in-Dpad-mode
            // binds A/B/X/Y, joystick-in-Dpad-mode binds Up/Down/Left/Right.
            // StubMode (modes whose runtime hasn't landed) keeps the permissive
            // fallback so seeded data for unimplemented modes isn't silently
            // dropped before the corresponding brick ships.
            val mode = preset.group.group.mode
            // NONE mode → intercept-and-silence at runtime. Record the source even
            // though it has no bindable sub-inputs, so the evaluator can consume
            // its events without firing anything. DEVICE_DEFAULT is the absence —
            // no inputs entries + not in noneSources = pass-through.
            if (mode == BindingMode.NONE) {
                noneSources += preset.inputSource
                continue
            }
            val sourceMode = mode.handler()
            var addedAnyForSource = false
            for (inputGraph in preset.group.inputs) {
                val inputKey = inputGraph.input.inputKey
                if (sourceMode !is StubMode && !acceptsFor(preset.inputSource, mode, inputKey)) {
                    Log.w(TAG_COMPILE, "compile: dropping group_input '$inputKey' on " +
                        "${preset.inputSource} — not valid for (${preset.inputSource}, $mode)")
                    continue
                }
                val address = InputAddress(preset.inputSource, inputKey)
                val groupSettingsJson = preset.group.group.settingsJson
                val compiledActivators = inputGraph.activators.map { actGraph ->
                    val settings = CompiledActivatorSettings.parse(actGraph.activator.settingsJson)
                    CompiledActivator(
                        activatorId = actGraph.activator.id,
                        type = actGraph.activator.type,
                        bindings = actGraph.bindings.map { BindingOutput.fromEntity(it.outputType, it.args) },
                        settings = settings,
                        effectiveHaptic = resolveEffectiveHaptic(groupSettingsJson, settings.hapticIntensity),
                    )
                }
                inputs[address] = CompiledInput(
                    groupInputId = inputGraph.input.id,
                    activators = compiledActivators,
                    mode = preset.group.group.mode,
                    modeSettingsJson = preset.group.group.settingsJson,
                )
                addedAnyForSource = true
            }
            // Source-mode sentinel for sources with no bindable sub-inputs (today:
            // just GYRO — gyro modes emit continuous output, not edges). Without
            // this, [findSourceModeFor] in the evaluator and
            // [GyroLifecycleCoordinator]'s predicate would both miss the
            // configured mode because they iterate `inputs` by source. The
            // sentinel uses [SOURCE_MODE_SENTINEL_KEY] as its sub-input key so it
            // can't collide with real physical-key dispatch (real inputKeys are
            // never blank). Skip for DEVICE_DEFAULT — that's the "Mapo doesn't
            // intercept" case; no entry needed.
            if (!addedAnyForSource && mode != BindingMode.DEVICE_DEFAULT) {
                inputs[InputAddress(preset.inputSource, SOURCE_MODE_SENTINEL_KEY)] = CompiledInput(
                    groupInputId = 0L,
                    activators = emptyList(),
                    mode = preset.group.group.mode,
                    modeSettingsJson = preset.group.group.settingsJson,
                )
            }
        }
        return CompileResult(inputs, noneSources)
    }

    // Phase 7 Brick B — pre-compile every BindingGroup in the config keyed by id, so
    // mode-shift activation can look up target groups by their stable group id at
    // runtime without re-walking the materialized graph. Walks every reachable group:
    // (a) set preset groups, (b) layer-owned bindingGroups, (c) layer preset groups.
    // The same group may appear in multiple buckets — `putIfAbsent` keeps the first.
    fun compileBindingGroup(group: com.mapo.data.model.steam.BindingGroupGraph): CompiledBindingGroup {
        val inputsBySubInput = HashMap<String, List<CompiledActivator>>()
        val groupSettingsJson = group.group.settingsJson
        for (inputGraph in group.inputs) {
            val compiledActivators = inputGraph.activators.map { actGraph ->
                val settings = CompiledActivatorSettings.parse(actGraph.activator.settingsJson)
                CompiledActivator(
                    activatorId = actGraph.activator.id,
                    type = actGraph.activator.type,
                    bindings = actGraph.bindings.map { BindingOutput.fromEntity(it.outputType, it.args) },
                    settings = settings,
                    effectiveHaptic = resolveEffectiveHaptic(groupSettingsJson, settings.hapticIntensity),
                )
            }
            inputsBySubInput[inputGraph.input.inputKey] = compiledActivators
        }
        return CompiledBindingGroup(
            groupId = group.group.id,
            mode = group.group.mode,
            modeSettingsJson = group.group.settingsJson,
            inputs = inputsBySubInput,
        )
    }

    val compiledGroups = HashMap<Long, CompiledBindingGroup>()
    for (setGraph in actionSets) {
        // Set preset groups
        for (preset in setGraph.preset) {
            compiledGroups.putIfAbsent(preset.group.group.id, compileBindingGroup(preset.group))
        }
        // Phase 7 Brick B.5 — set-owned mode-shift target groups. They have
        // `BindingGroup.actionSetId = X` but no preset entry, so they wouldn't
        // be reached by the preset walk above.
        for (shift in setGraph.modeShifts) {
            compiledGroups.putIfAbsent(shift.group.group.id, compileBindingGroup(shift.group))
        }
        for (layerGraph in setGraph.layers) {
            // Layer-owned binding groups (not necessarily preset-bound) — also
            // covers layer-owned mode-shift target groups, since those have
            // `actionLayerId = Y` and are returned by getByActionLayers.
            for (group in layerGraph.bindingGroups) {
                compiledGroups.putIfAbsent(group.group.id, compileBindingGroup(group))
            }
            // Layer preset groups (overlap with bindingGroups in most cases, but
            // putIfAbsent keeps the first deterministic)
            for (preset in layerGraph.preset) {
                compiledGroups.putIfAbsent(preset.group.group.id, compileBindingGroup(preset.group))
            }
            // Layer-owned mode-shift target groups (defensive — they'll
            // typically already be in `bindingGroups` from the layer walk).
            for (shift in layerGraph.modeShifts) {
                compiledGroups.putIfAbsent(shift.group.group.id, compileBindingGroup(shift.group))
            }
        }
    }

    // Phase 7 Brick B.5 — compile mode-shift definitions. Walks the graph's
    // SourceModeShiftGraph entries, drops any without a fully-assigned trigger
    // (user added but hasn't picked the trigger yet), and turns each into a
    // CompiledModeShift the runtime can match against incoming presses.
    fun compileModeShifts(graphs: List<com.mapo.data.model.steam.SourceModeShiftGraph>): List<CompiledModeShift> {
        if (graphs.isEmpty()) return emptyList()
        val out = ArrayList<CompiledModeShift>(graphs.size)
        for (g in graphs) {
            val src = g.shift.triggerSource ?: continue
            val key = g.shift.triggerSubInput ?: continue
            out += CompiledModeShift(
                ownerSource = g.shift.ownerSource,
                triggerAddress = InputAddress(src, key),
                targetGroupId = g.shift.bindingGroupId,
            )
        }
        return out
    }

    val compiledSets = HashMap<Long, CompiledActionSet>(actionSets.size)
    for (setGraph in actionSets) {
        val baseCompiled = compileInputs(setGraph.preset)
        val compiledLayers = if (setGraph.layers.isEmpty()) emptyMap() else
            setGraph.layers.associate { layerGraph ->
                val layerCompiled = compileInputs(layerGraph.preset)
                layerGraph.layer.id to CompiledLayer(
                    layerId = layerGraph.layer.id,
                    inputs = layerCompiled.inputs,
                    modeShifts = compileModeShifts(layerGraph.modeShifts),
                )
            }
        compiledSets[setGraph.actionSet.id] = CompiledActionSet(
            actionSetId = setGraph.actionSet.id,
            inputs = baseCompiled.inputs,
            layers = compiledLayers,
            noneModeSources = baseCompiled.noneSources,
            modeShifts = compileModeShifts(setGraph.modeShifts),
        )
    }
    val startingId = actionSets.first().actionSet.id
    return CompiledConfig(
        startingActionSetId = startingId,
        sets = compiledSets,
        compiledGroups = compiledGroups,
    )
}
