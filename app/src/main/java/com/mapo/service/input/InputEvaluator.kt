package com.mapo.service.input

import android.util.Log
import android.view.MotionEvent
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.InputSource
import com.mapo.di.ApplicationScope
import com.mapo.service.input.modes.ModeContext
import com.mapo.service.input.modes.MouseEmitter
import com.mapo.service.input.modes.TriggerMode
import com.mapo.service.input.modes.handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime state machine that turns physical input events into [BindingOutput] emissions.
 *
 * Event-driven: every call corresponds to a discrete `onKeyEvent` (Phase 2.2) or
 * normalized analog edge (Phase 6, deferred); no polling, no per-frame tick. The
 * accessibility service hands events here, the evaluator decides what to fire,
 * [OutputEmitter] does the actual injection.
 *
 * Activator coverage:
 *  - **Brick 2.2** — `FULL_PRESS` (DOWN emits, UP releases).
 *  - **Brick 3.1** — `LONG_PRESS` / `START_PRESS` / `RELEASE_PRESS` with timing.
 *  - **Brick 3.2** — `DOUBLE_PRESS` window state machine + Regular/Double coexistence.
 *  - **Brick 3.3** — Universal settings (toggle, hold-to-repeat / turbo, fire start/end
 *    delays, cycle bindings, interruptable) and `CHORDED_PRESS`.
 *  - **Brick 5.1** — Action-set layer stack with `add_layer` / `remove_layer` /
 *    `hold_layer` verbs. Top-of-stack wins on lookup; `CHANGE_PRESET` clears the stack.
 *
 * State held here:
 *  - [held] — per-address held entries released on UP. Each entry captures the bindings,
 *    the activator they came from, and any `fire_end_delay` set on that activator (snapshot
 *    so a config swap between press and release doesn't change the release semantics).
 *  - [pending] — coroutine [Job]s scheduled for the *primary* timer of a temporal
 *    activator (`LONG_PRESS` threshold, etc.).
 *  - [doubleTapWindows] — per-address state for in-flight DOUBLE_PRESS detection.
 *  - [activatorState] — per-activator runtime state that outlives a single event: toggle
 *    latch, hold-to-repeat job, fire-start-delay job, cycle index. Keyed by activatorId
 *    rather than address so two inputs sharing one activator (Phase 6 reference groups)
 *    would correctly share state. Today every activator is reachable from exactly one
 *    address, so the keying choice is forward-compat rather than load-bearing.
 *
 * Config-change semantics: if the compiled config changes while a binding is held, the
 * original binding's release still fires because [held] is a snapshot of what was
 * emitted at press time — we do *not* re-resolve on release. Toggle latches survive a
 * config swap; pressing the activator again toggles off normally.
 */
@Singleton
class InputEvaluator @Inject constructor(
    private val dispatcher: InputDispatcher,
    private val emitter: OutputEmitter,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * One bindings-released-on-UP record. Multiple records per [InputAddress] are possible
     * when several activators on the same input emit holdable bindings (e.g., a FULL_PRESS
     * and a LONG_PRESS firing on the same A button). [fireEndDelayMs] is snapshotted from
     * the activator's settings at press time so the release path doesn't have to refetch.
     */
    private data class HeldEntry(
        val activatorId: Long,
        val bindings: List<BindingOutput>,
        val fireEndDelayMs: Long,
    )

    /**
     * In-flight DOUBLE_PRESS detection state. See `startDoubleTapWindow`.
     */
    private data class DoubleTapWindow(
        val doubleActivator: CompiledActivator,
        val deferredRegular: CompiledActivator?,
        var physicallyHeld: Boolean,
        var timerJob: Job? = null,
    )

    /**
     * Per-activator runtime state that outlives a single press/release.
     *  - [toggledOn] / [toggledBindings] — when `toggle=true`, the activator latches its
     *    bindings on after the first fire; the next fire releases them. Physical UP is
     *    suppressed while latched.
     *  - [repeatJob] — hold-to-repeat (turbo) timer that pulses bindings while active.
     *  - [startDelayJob] — `fire_start_delay` timer; cancelled if the user releases
     *    before it elapses.
     *  - [cycleIndex] — next binding index for `cycle_bindings=true` activators.
     */
    private data class ActivatorRuntimeState(
        var toggledOn: Boolean = false,
        var toggledAddress: InputAddress? = null,
        var toggledBindings: List<BindingOutput> = emptyList(),
        var repeatJob: Job? = null,
        var startDelayJob: Job? = null,
        var cycleIndex: Int = 0,
    )

    /**
     * Tracks an active `CHORDED_PRESS`: which chord input fired, against which partner,
     * for which activator. On UP of either address, the chord output releases.
     */
    private data class ChordLink(
        val chordAddress: InputAddress,
        val partnerAddress: InputAddress,
        val activatorId: Long,
    )

    private val held = HashMap<InputAddress, MutableList<HeldEntry>>()
    private val pending = HashMap<InputAddress, MutableMap<Long, Job>>()
    private val doubleTapWindows = HashMap<InputAddress, DoubleTapWindow>()
    private val activatorState = HashMap<Long, ActivatorRuntimeState>()
    private val physicallyHeld = HashSet<InputAddress>()
    private val activeChords = mutableListOf<ChordLink>()

    /**
     * Active action set layer stack (Brick 5.1). Front-to-back order: index 0 is the
     * earliest activation, last index is the most recent (top-of-stack, highest priority
     * on conflicts). Steam-faithful:
     *  - `add_layer X` when X already present is a no-op (no reorder).
     *  - Explicit `remove_layer X; add_layer X` is how the user moves a layer to the top.
     *  - `CHANGE_PRESET` clears the stack (per the docs: "Switching action sets clears
     *    ALL layers of the old set").
     *
     * **Brick 4 publish mirror.** [activeLayerIdsFlow] mirrors this deque as an immutable
     * list every time the stack mutates so [MotionCaptureCoordinator][com.mapo.service.input.capture.MotionCaptureCoordinator]
     * can re-evaluate its attach predicate without polling. Internal code keeps using
     * the deque directly — the flow is observation-only.
     */
    private val activeLayers = ArrayDeque<Long>()

    private val _activeLayerIdsFlow = MutableStateFlow<List<Long>>(emptyList())
    /** Bottom-of-stack first; top-of-stack is the last entry. Empty when no layers active. */
    val activeLayerIdsFlow: StateFlow<List<Long>> = _activeLayerIdsFlow.asStateFlow()

    private fun publishActiveLayers() {
        _activeLayerIdsFlow.value = activeLayers.toList()
    }

    /**
     * Per-address `hold_layer` link (Brick 5.1). When a binding emits `hold_layer N`
     * during a held press, we record (address → layerId) here so the matching UP can
     * deactivate that specific layer. Independent of [held] because the layer side has
     * no key-output to release through the emitter — only a stack mutation.
     */
    private val heldLayerByAddress = HashMap<InputAddress, Long>()

    /**
     * Currently-active action set id (Brick 4.2). 0L means "uninitialized — lazy-resolve
     * from `compiledConfig.startingActionSetId` on first event." A `CHANGE_PRESET`
     * controller_action verb mutates this; [flushAllRuntime] runs first so no held
     * binding or pending timer from the old set bleeds into the new one. When a config
     * swap removes the active set, [resolveActiveSet] silently falls back to the new
     * config's starting set.
     */
    private var activeSetId: Long = 0L
        set(value) {
            field = value
            _activeSetIdFlow.value = value
        }

    private val _activeSetIdFlow = MutableStateFlow(0L)
    /**
     * Brick 4 publish mirror of [activeSetId]. 0L means "lazy-uninitialized" — same
     * semantics as the internal field. [MotionCaptureCoordinator][com.mapo.service.input.capture.MotionCaptureCoordinator]
     * observes this to re-evaluate the attach predicate on `CHANGE_PRESET`.
     */
    val activeSetIdFlow: StateFlow<Long> = _activeSetIdFlow.asStateFlow()

    /**
     * FULL_PRESS activators that have been deferred by a coexisting LONG_PRESS on the same
     * address (with `interruptable=true`). On LONG fire: suppressed (popped, discarded). On
     * UP before LONG threshold: popped and fired as a tap. The DOUBLE deferral lives on
     * [DoubleTapWindow] and is independent of this map.
     */
    private val longPressDeferrals = HashMap<InputAddress, CompiledActivator>()

    /**
     * Brick 5: synthetic-edge latch state per (source, virtual sub-input).
     * Modes write here indirectly via [SourceMode.evaluate][com.mapo.service.input.modes.SourceMode.evaluate]'s
     * `digitalEmit` callback — when that callback fires `(subInput, true)` the
     * corresponding key flips to true here, `(subInput, false)` removes it. The
     * evaluator hands the per-source slice back to evaluate() on the next motion
     * event as `ctx.priorLatched`, which lets modes do hysteresis edge detection
     * without holding state themselves (modes are singletons).
     *
     * Persists across motion events; cleared per-source by [flushAnalog] on
     * profile/set switch.
     */
    private val analogLatched = HashMap<InputAddress, Boolean>()

    private fun stateFor(id: Long): ActivatorRuntimeState =
        activatorState.getOrPut(id) { ActivatorRuntimeState() }

    /**
     * Resolve the currently-active [CompiledActionSet]. Lazy-initializes [activeSetId]
     * from the snapshot's starting set on the first call after a fresh config. If the
     * active set was removed (e.g. user deleted it via the editor), falls back to the
     * new starting set without releasing anything — held entries are already snapshotted
     * bindings and release normally on UP.
     */
    private fun resolveActiveSet(): CompiledActionSet? {
        val cfg = dispatcher.compiledConfig.value
        if (cfg.sets.isEmpty()) return null
        if (activeSetId == 0L || activeSetId !in cfg.sets) {
            if (activeSetId != 0L) {
                Log.d(TAG, "active set $activeSetId missing from current config; falling back to starting set ${cfg.startingActionSetId}")
            }
            activeSetId = cfg.startingActionSetId
        }
        return cfg.sets[activeSetId]
    }

    /**
     * Lookup with layer-stack overlay (Brick 5.1). Walks [activeLayers] top-down (last
     * entry = highest priority); first overlay that holds the address wins. Falls back
     * to the base set's inputs when no layer in the stack overrides the address.
     */
    private fun lookupActive(address: InputAddress): CompiledInput? {
        val set = resolveActiveSet() ?: return null
        if (activeLayers.isNotEmpty()) {
            for (i in activeLayers.indices.reversed()) {
                val overlay = set.layers[activeLayers[i]]?.inputs?.get(address)
                if (overlay != null) return overlay
            }
        }
        return set.inputs[address]
    }

    /**
     * Handle a digital press/release at [address]. Returns true if the event was consumed
     * (at least one configured activator acted on it), false if there's no binding
     * configured for the address and the physical event should pass through.
     */
    fun handleDigital(address: InputAddress, isDown: Boolean): Boolean {
        return if (isDown) onPress(address) else onRelease(address)
    }

    /**
     * Brick 5: motion-event dispatcher. Extracts normalized [AnalogEvent]s and routes
     * each through its source's resolved [SourceMode][com.mapo.service.input.modes.SourceMode].
     * The mode does any edge detection / continuous-output translation it needs and
     * emits synthetic sub-input edges via the `digitalEmit` callback; the dispatcher
     * routes those back into the activator engine.
     *
     * **What's wired this brick:**
     *  - Trigger SOFT_PRESS — when the analog magnitude crosses `soft_threshold`
     *    (with hysteresis on release), the synthetic `"soft_press"` edge fires
     *    SOFT_PRESS-type activators on the source's `"click"` sub-input.
     *
     * **Future:** Brick 6 (Joystick Move) emits synthetic `"dpad_north"` / etc.
     * edges which route through the normal digital path; Brick 7 (Mouse modes)
     * uses the `MouseEmitter` continuous-output sink instead.
     *
     * Returns false so the platform input pipeline keeps routing the underlying
     * MotionEvent normally — Mapo doesn't consume the gesture, it just samples it.
     */
    fun handleMotion(event: MotionEvent): Boolean {
        val readings = MotionEventNormalizer.extract(event)
        // Per feedback_input_logging: input events always logged. Single line per
        // event keeps the volume manageable vs. one line per axis when the stick
        // is at rest with two non-zero axes.
        if (readings.isNotEmpty()) {
            val summary = readings.joinToString(" ") { r ->
                "${r.source}(${"%.3f".format(r.x)},${"%.3f".format(r.y)})"
            }
            Log.d(TAG_MOTION, "handleMotion action=${event.actionMasked} $summary")
        }

        val set = resolveActiveSet() ?: return false
        for (reading in readings) {
            val resolved = findSourceModeFor(set, reading.source) ?: continue
            val handler = resolved.mode.handler()
            // Priors slice — only this source's virtual sub-input latch state.
            val priors = analogLatched.entries
                .filter { it.key.source == reading.source }
                .associate { it.key.inputKey to it.value }
            val ctx = ModeContext(
                source = reading.source,
                settingsJson = resolved.settingsJson,
                priorLatched = priors,
                activeLayerIds = activeLayers.toList(),
            )
            handler.evaluate(
                reading,
                ctx,
                digitalEmit = { subInput, isDown ->
                    dispatchSyntheticEdge(reading.source, subInput, isDown)
                },
                mouse = MouseEmitter.NOOP,
            )
        }
        return false
    }

    /**
     * Resolve which mode + settings apply to [source] given the active set + layer
     * stack. Walks layers top-down (highest priority first) looking for any
     * compiled input on this source — if found, that layer's binding_group's mode
     * + settings win. Falls back to the base set otherwise. Returns null when no
     * binding_group covers the source (e.g. the source is UNBOUND or never seeded).
     */
    private fun findSourceModeFor(set: CompiledActionSet, source: InputSource): SourceModeView? {
        if (activeLayers.isNotEmpty()) {
            for (i in activeLayers.indices.reversed()) {
                val layer = set.layers[activeLayers[i]] ?: continue
                val match = layer.inputs.entries.firstOrNull { it.key.source == source }?.value
                if (match != null) return SourceModeView(match.mode, match.modeSettingsJson)
            }
        }
        val base = set.inputs.entries.firstOrNull { it.key.source == source }?.value
            ?: return null
        return SourceModeView(base.mode, base.modeSettingsJson)
    }

    /**
     * Route a mode's synthetic edge into the activator engine via the normal
     * digital press/release path. The mode emits a virtual sub-input key
     * (e.g. `"soft_press"` for triggers, `"dpad_north"` for joysticks in
     * Brick 6); the corresponding `CompiledInput(source, subInput)` resolves
     * via [lookupActive] and the activator engine handles fire / release
     * exactly as if a physical DOWN/UP had arrived at that address.
     *
     * For this to work, the mode's [SourceMode.validInputs] must declare the
     * sub-input — otherwise the compile step drops it and `lookupActive`
     * returns null, leaving the synthetic edge inert (which is also the
     * correct outcome when the source isn't in the right mode).
     */
    private fun dispatchSyntheticEdge(source: InputSource, subInput: String, isDown: Boolean) {
        val address = InputAddress(source, subInput)
        if (isDown) onPress(address) else onRelease(address)
        if (isDown) analogLatched[address] = true else analogLatched.remove(address)
        Log.d(TAG_MOTION, "synthetic edge: $source.$subInput ${if (isDown) "DOWN" else "UP"}")
    }

    /**
     * Lightweight return type for [findSourceModeFor]; keeps the resolved
     * mode + settings together without leaking [CompiledInput] internals.
     */
    private data class SourceModeView(val mode: BindingMode, val settingsJson: String)

    private fun onPress(address: InputAddress): Boolean {
        // Record the physical state up-front so any CHORDED_PRESS activator triggered by
        // *this* event sees a consistent held set if it queries.
        physicallyHeld.add(address)

        // Second-tap path: an active DOUBLE_PRESS window on this address means this DOWN
        // is the second tap. Cancel the window, fire DOUBLE_PRESS (the deferred Regular is
        // dropped — that's the suppression behavior).
        val activeWindow = doubleTapWindows.remove(address)
        if (activeWindow != null) {
            activeWindow.timerJob?.cancel()
            Log.d(TAG, "double-tap detected at $address — firing DOUBLE_PRESS")
            firePressBindings(address, activeWindow.doubleActivator)
            return true
        }

        // Defensive: a duplicate DOWN without an intervening UP can happen on flaky
        // controllers. Treat it as a release+re-press so we don't leak held bindings or
        // orphan scheduled timers. (Toggle latches intentionally survive this — they're
        // already in the user's toggled-on state.)
        if (held.containsKey(address) || pending.containsKey(address)) {
            Log.d(TAG, "onPress: address $address already in flight — releasing stale state first")
            forceReleaseAddress(address)
        }

        val compiledInput = lookupActive(address)
        if (compiledInput == null) {
            Log.d(TAG, "onPress: no compiled input for $address — passing through")
            return false
        }

        val doubleActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.DOUBLE_PRESS }
        val longActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.LONG_PRESS }
        val regularActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.FULL_PRESS }
        // 3.3: interruptable now configurable. Steam default is true. A Regular Press is
        // deferred whenever it's interruptable AND a longer-duration activator (LONG or
        // DOUBLE) coexists — the longer activator gets first claim and suppresses Regular
        // if it fires. If it doesn't fire (user releases too early), the deferred Regular
        // fires retroactively as a tap.
        val deferRegular = regularActivator != null
            && regularActivator.settings.interruptable
            && (doubleActivator != null || longActivator != null)

        var consumed = false
        for (activator in compiledInput.activators) {
            when (activator.type) {
                ActivatorType.FULL_PRESS -> {
                    if (deferRegular) {
                        // When DOUBLE exists, its window owns the deferral via DoubleTapWindow.
                        // When only LONG exists, longPressDeferrals owns it. (Triple
                        // coexistence — FULL+LONG+DOUBLE — routes through DOUBLE; the LONG
                        // suppression of an already-late-fired-by-DOUBLE-window Regular is a
                        // known limitation, documented in plan.)
                        if (doubleActivator == null && longActivator != null) {
                            longPressDeferrals[address] = activator
                            Log.d(TAG, "FULL_PRESS deferred to LONG_PRESS on $address")
                        }
                        consumed = true
                    } else {
                        if (firePressBindings(address, activator)) consumed = true
                        else if (activator.bindings.isNotEmpty()) consumed = true
                    }
                }
                ActivatorType.LONG_PRESS -> {
                    scheduleLongPress(address, activator)
                    consumed = true
                }
                ActivatorType.DOUBLE_PRESS -> {
                    startDoubleTapWindow(
                        address = address,
                        doubleActivator = activator,
                        deferredRegular = if (deferRegular) regularActivator else null,
                    )
                    consumed = true
                }
                ActivatorType.START_PRESS -> {
                    emitTap(activator)
                    consumed = true
                }
                ActivatorType.RELEASE_PRESS -> {
                    // Fires on UP. Mark the address as consumed so the event doesn't pass
                    // through, but no DOWN-side emission.
                    consumed = true
                }
                ActivatorType.SOFT_PRESS -> {
                    // Brick 5 follow-up: SOFT_PRESS is no longer a user-pickable
                    // activator type — the user expresses soft-pull behavior by
                    // adding any activator type to the trigger's `soft_press`
                    // sub-input row (UI: "L2/R2 Soft Pull"). This case stays as
                    // a defensive no-op for legacy data + the eventual VDF import
                    // path (Steam's SOFT_PRESS activator translates into a Mapo
                    // activator on the `soft_press` sub-input, but if any legacy
                    // SOFT_PRESS row survives on `click`, treat it as inert here
                    // rather than firing on the hardware threshold).
                }
                ActivatorType.CHORDED_PRESS -> {
                    val partner = activator.settings.chordPartner
                    when {
                        partner == null -> {
                            Log.d(TAG, "chord at $address ignored: no partner configured")
                        }
                        partner == address -> {
                            // Defensive — a chord with itself as the partner can never satisfy.
                            Log.d(TAG, "chord at $address ignored: partner equals chord address")
                        }
                        partner in physicallyHeld -> {
                            if (firePressBindings(address, activator)) {
                                activeChords += ChordLink(
                                    chordAddress = address,
                                    partnerAddress = partner,
                                    activatorId = activator.activatorId,
                                )
                                Log.d(TAG, "chord fired: $address with partner $partner")
                            }
                        }
                        else -> {
                            Log.d(TAG, "chord at $address ignored: partner $partner not currently held")
                        }
                    }
                    consumed = true
                }
            }
        }
        return consumed
    }

    private fun onRelease(address: InputAddress): Boolean {
        physicallyHeld.remove(address)
        cancelPendingTimers(address)

        // If a Regular Press was deferred to a LONG_PRESS on this address and the user
        // releases before LONG fires, the deferred Regular fires retroactively as a tap.
        // (Steam-faithful: interruptable means "the longer activator gets first claim"
        // but if it never fires, Regular still gets to fire.)
        val deferredRegular = longPressDeferrals.remove(address)
        if (deferredRegular != null) {
            Log.d(TAG, "UP before LONG threshold on $address; firing deferred FULL_PRESS as tap")
            emitTap(deferredRegular)
        }

        // CHORDED_PRESS upkeep: if [address] is the partner for any active chord, that
        // chord's output must release now (the user let go of the partner first). If
        // [address] is itself a chord, the normal release path below handles the bindings;
        // we just clean up the activeChords tracking.
        releaseChordsDependingOn(address)

        // 5.1: `hold_layer` linked to this address releases here. Done before the
        // RELEASE_PRESS activator path so RELEASE_PRESS bindings already see the
        // underlying-set bindings restored (matches Steam: the layer goes away with the UP).
        releaseHeldLayer(address)

        // If a DOUBLE_PRESS window is still active for this address, this UP belongs to the
        // first tap. Note the physical state — the window timer uses it later to decide
        // whether the deferred Regular fires as a tap or as held.
        doubleTapWindows[address]?.physicallyHeld = false

        val compiledInput = lookupActive(address)
        compiledInput?.activators?.forEach { activator ->
            // Cancel any in-flight fire_start_delay timer — user released before it fired.
            if (activator.settings.fireStartDelayMs > 0) {
                stateFor(activator.activatorId).startDelayJob?.cancel()
            }
            if (activator.type == ActivatorType.RELEASE_PRESS) {
                emitTap(activator)
            }
        }

        val records = held.remove(address)
        if (records == null) {
            return compiledInput?.activators?.isNotEmpty() == true
        }

        for (entry in records) {
            cancelRepeatJob(stateFor(entry.activatorId))
            if (entry.fireEndDelayMs > 0L) {
                val bindings = entry.bindings
                val delayMs = entry.fireEndDelayMs
                scope.launch {
                    delay(delayMs)
                    bindings.forEach(emitter::emitRelease)
                }
            } else {
                entry.bindings.forEach(emitter::emitRelease)
            }
        }
        Log.d(TAG, "onRelease: $address released ${records.sumOf { it.bindings.size }} binding(s)")
        return true
    }

    /**
     * Open a [ActivatorType.DOUBLE_PRESS] detection window on a first DOWN.
     */
    private fun startDoubleTapWindow(
        address: InputAddress,
        doubleActivator: CompiledActivator,
        deferredRegular: CompiledActivator?,
    ) {
        val window = DoubleTapWindow(
            doubleActivator = doubleActivator,
            deferredRegular = deferredRegular,
            physicallyHeld = true,
        )
        doubleTapWindows[address] = window
        val timerMs = doubleActivator.settings.doubleTapTimeMs
        window.timerJob = scope.launch {
            delay(timerMs)
            val expired = doubleTapWindows.remove(address) ?: return@launch
            Log.d(TAG, "double-tap window expired at $address — no second tap")
            val regular = expired.deferredRegular ?: return@launch
            if (expired.physicallyHeld) {
                firePressBindings(address, regular)
            } else {
                emitTap(regular)
            }
        }
        Log.d(TAG, "opened double-tap window ($timerMs ms) at $address" +
            (if (deferredRegular != null) " with deferred Regular" else ""))
    }

    /**
     * Schedule a [ActivatorType.LONG_PRESS] timer. Combines `long_press_time` with
     * `fire_start_delay` so the threshold honors both. firePressBindings then handles
     * toggle / cycle / hold-to-repeat semantics if any of those are configured too.
     */
    private fun scheduleLongPress(address: InputAddress, activator: CompiledActivator) {
        val delayMs = activator.settings.longPressTimeMs
        val job = scope.launch {
            delay(delayMs)
            pending[address]?.remove(activator.activatorId)
            if (pending[address]?.isEmpty() == true) pending.remove(address)
            // Suppress any deferred Regular Press on this address — LONG's about to fire
            // and `interruptable=true` is exactly what gave us the deferral.
            val suppressed = longPressDeferrals.remove(address)
            if (suppressed != null) {
                Log.d(TAG, "LONG_PRESS firing; suppressing deferred FULL_PRESS on $address")
            }
            firePressBindings(address, activator)
            Log.d(TAG, "long-press fired for $address activator=${activator.activatorId}")
        }
        val bucket = pending.getOrPut(address) { HashMap() }
        bucket[activator.activatorId] = job
        Log.d(TAG, "scheduled long-press at $delayMs ms for $address activator=${activator.activatorId}")
    }

    /**
     * Fire a press for [activator] at [address] with full universal-settings semantics.
     *
     *  - Toggle: if already toggled-on, this fire releases the latched bindings; otherwise
     *    proceed to emit and latch (the actual latch happens in [doEmitPress]).
     *  - Cycle: choose only one binding from the list per fire, advancing the index.
     *  - Fire-start-delay: defer the actual emit by `fire_start_delay`. Cancelled by a UP
     *    on the address (handled in [onRelease]).
     *
     * Returns true if anything was (or will be) emitted; matches the 3.2 contract.
     */
    private fun firePressBindings(address: InputAddress, activator: CompiledActivator): Boolean {
        val st = stateFor(activator.activatorId)

        // Toggle off: second fire releases the latched bindings.
        if (activator.settings.toggle && st.toggledOn) {
            st.toggledBindings.forEach(emitter::emitRelease)
            cancelRepeatJob(st)
            st.toggledBindings = emptyList()
            st.toggledOn = false
            st.toggledAddress = null
            Log.d(TAG, "toggle off: $address activator=${activator.activatorId}")
            return true
        }

        val bindingsToEmit = pickBindings(activator, st)

        if (activator.settings.fireStartDelayMs > 0L) {
            st.startDelayJob?.cancel()
            val captured = bindingsToEmit
            st.startDelayJob = scope.launch {
                delay(activator.settings.fireStartDelayMs)
                doEmitPress(address, activator, captured, st)
            }
            return true
        }
        return doEmitPress(address, activator, bindingsToEmit, st)
    }

    /**
     * Pick which bindings of [activator] to emit on this fire. With `cycle_bindings=true`,
     * returns only the next binding (round-robin via [ActivatorRuntimeState.cycleIndex]);
     * otherwise returns the full list (all bindings fire together).
     */
    private fun pickBindings(
        activator: CompiledActivator,
        st: ActivatorRuntimeState,
    ): List<BindingOutput> {
        if (!activator.settings.cycleBindings || activator.bindings.isEmpty()) return activator.bindings
        val size = activator.bindings.size
        val idx = ((st.cycleIndex % size) + size) % size
        st.cycleIndex = (idx + 1) % size
        return listOf(activator.bindings[idx])
    }

    /**
     * Emit the actual press for [bindings] and either latch them (toggle) or record them
     * for release-on-UP (normal). Starts a hold-to-repeat job if configured.
     */
    private fun doEmitPress(
        address: InputAddress,
        activator: CompiledActivator,
        bindings: List<BindingOutput>,
        st: ActivatorRuntimeState,
    ): Boolean {
        val holdable = mutableListOf<BindingOutput>()
        for (binding in bindings) {
            if (tryHandleControllerAction(binding, address)) continue
            if (emitter.emitPress(binding)) holdable += binding
        }

        if (activator.settings.toggle) {
            // Latch — release is via the next toggle fire, not physical UP. Note: if a
            // duplicate DOWN or config swap happens while latched, the bindings stay held.
            // The user toggles off normally by pressing again.
            st.toggledOn = true
            st.toggledAddress = address
            st.toggledBindings = holdable
        } else if (holdable.isNotEmpty()) {
            held.getOrPut(address) { mutableListOf() }.add(
                HeldEntry(
                    activatorId = activator.activatorId,
                    bindings = holdable,
                    fireEndDelayMs = activator.settings.fireEndDelayMs,
                )
            )
        }

        if (activator.settings.holdToRepeat) {
            startRepeatJob(activator, st)
        }

        return holdable.isNotEmpty()
    }

    /**
     * Hold-to-repeat (turbo). Pulses the activator's bindings as taps at
     * `repeat_rate_ms`. Cancelled when the activator deactivates (UP for normal,
     * second toggle-press for latched).
     */
    private fun startRepeatJob(activator: CompiledActivator, st: ActivatorRuntimeState) {
        st.repeatJob?.cancel()
        val rate = activator.settings.repeatRateMs.coerceAtLeast(10L)
        val bindings = activator.bindings
        st.repeatJob = scope.launch {
            while (isActive) {
                delay(rate)
                for (binding in bindings) {
                    // Repeat path: no owning address. `hold_layer` skips with a warning;
                    // `add_layer` / `remove_layer` repeat harmlessly (already-active is a no-op).
                    if (tryHandleControllerAction(binding, address = null)) continue
                    val holdable = emitter.emitPress(binding)
                    if (holdable) emitter.emitRelease(binding)
                }
            }
        }
    }

    private fun cancelRepeatJob(st: ActivatorRuntimeState) {
        st.repeatJob?.cancel()
        st.repeatJob = null
    }

    /**
     * Edge-triggered single tap: emit press + release back-to-back. Used by START_PRESS
     * (on DOWN), RELEASE_PRESS (on UP), and the DOUBLE_PRESS-window-expired-while-not-held
     * path. Respects `cycle_bindings`.
     */
    private fun emitTap(activator: CompiledActivator) {
        val st = stateFor(activator.activatorId)
        val bindings = pickBindings(activator, st)
        for (binding in bindings) {
            // Tap path: no held semantics. `hold_layer` skips (logged) because it would
            // never get a release; sticky `add_layer` / `remove_layer` are fine.
            if (tryHandleControllerAction(binding, address = null)) continue
            val holdable = emitter.emitPress(binding)
            if (holdable) emitter.emitRelease(binding)
        }
    }

    private fun cancelPendingTimers(address: InputAddress) {
        val bucket = pending.remove(address) ?: return
        bucket.values.forEach { it.cancel() }
    }

    /**
     * Release everything currently associated with [address] — bindings, timers, windows.
     * Used by the duplicate-DOWN guard so we never leak held key codes when an unexpected
     * second DOWN arrives. Toggle latches are intentionally not cleared here — they're
     * the user's state, not transient event state.
     */
    private fun forceReleaseAddress(address: InputAddress) {
        cancelPendingTimers(address)
        doubleTapWindows.remove(address)?.timerJob?.cancel()
        longPressDeferrals.remove(address)
        releaseChordsDependingOn(address)
        // 5.1: drop any layer held by this address so a stale duplicate DOWN doesn't
        // strand the overlay active forever.
        releaseHeldLayer(address)
        held.remove(address)?.forEach { entry ->
            entry.bindings.forEach(emitter::emitRelease)
        }
    }

    /**
     * Sweep [activeChords] for any link involving [address]. If [address] is the chord's
     * partner (and not the chord itself), release the chord output bindings directly — the
     * normal `onRelease` path for the chord address won't run. If [address] is the chord
     * itself, just remove the link; the standard release path handles the bindings.
     */
    private fun releaseChordsDependingOn(address: InputAddress) {
        val iter = activeChords.iterator()
        while (iter.hasNext()) {
            val link = iter.next()
            if (link.partnerAddress == address && link.chordAddress != address) {
                val bucket = held[link.chordAddress]
                val entry = bucket?.firstOrNull { it.activatorId == link.activatorId }
                if (entry != null) {
                    entry.bindings.forEach(emitter::emitRelease)
                    bucket.remove(entry)
                    if (bucket.isEmpty()) held.remove(link.chordAddress)
                }
                cancelRepeatJob(stateFor(link.activatorId))
                Log.d(TAG, "chord released due to partner UP: $link")
                iter.remove()
            } else if (link.chordAddress == address) {
                // Chord input released — normal onRelease handles binding release.
                iter.remove()
            }
        }
    }

    /**
     * Intercept evaluator-managed controller verbs *before* they're handed to
     * [OutputEmitter]. Returns true when the binding was consumed by the evaluator,
     * in which case the caller should skip the normal emit path.
     *
     * [address] is the physical input that emitted this binding, if known. Required by
     * `hold_layer` (which needs to know whose UP releases the layer). Null is supplied
     * by call sites that don't have a single owning address — turbo repeat and tap
     * paths — and `hold_layer` no-ops with a warning in that case.
     *
     * Brick 4.2 introduced `CHANGE_PRESET`; Brick 5.1 adds `add_layer`, `remove_layer`,
     * and `hold_layer`. `mode_shift` lands in Phase 6 with mode authoring.
     */
    private fun tryHandleControllerAction(output: BindingOutput, address: InputAddress?): Boolean {
        if (output !is BindingOutput.ControllerAction) return false
        return when (output.verb) {
            "CHANGE_PRESET" -> {
                val targetId = output.args.firstOrNull()?.toLongOrNull()
                if (targetId == null) {
                    Log.w(TAG, "CHANGE_PRESET has no valid target set id: ${output.args}")
                } else {
                    setActiveSet(targetId)
                }
                true
            }
            "add_layer" -> {
                val layerId = output.args.firstOrNull()?.toLongOrNull()
                if (layerId == null) Log.w(TAG, "add_layer missing layer id: ${output.args}")
                else addLayer(layerId)
                true
            }
            "remove_layer" -> {
                val layerId = output.args.firstOrNull()?.toLongOrNull()
                if (layerId == null) Log.w(TAG, "remove_layer missing layer id: ${output.args}")
                else removeLayer(layerId)
                true
            }
            "hold_layer" -> {
                val layerId = output.args.firstOrNull()?.toLongOrNull()
                when {
                    layerId == null -> Log.w(TAG, "hold_layer missing layer id: ${output.args}")
                    address == null -> Log.w(TAG, "hold_layer($layerId) from a tap/repeat context has no UP to release on — ignoring (use FULL_PRESS for while-held layers)")
                    else -> holdLayer(address, layerId)
                }
                true
            }
            else -> false
        }
    }

    /**
     * Push [layerId] onto the active layer stack. Per Steam: "Activating an already-active
     * layer = no-op." (To move a layer to the top, `remove_layer` + `add_layer` explicitly.)
     * Guards against unknown ids — if the layer isn't on the active set, log + skip.
     */
    private fun addLayer(layerId: Long) {
        val set = resolveActiveSet()
        if (set == null) {
            Log.w(TAG, "add_layer($layerId): no active set; ignoring")
            return
        }
        if (layerId !in set.layers) {
            Log.w(TAG, "add_layer($layerId): set ${set.actionSetId} has no such layer; ignoring")
            return
        }
        if (layerId in activeLayers) {
            Log.d(TAG, "add_layer($layerId): already active — no-op")
            return
        }
        activeLayers.addLast(layerId)
        publishActiveLayers()
        Log.d(TAG, "add_layer($layerId): pushed; stack depth=${activeLayers.size}")
    }

    /**
     * Remove [layerId] from anywhere in the stack. Lower-priority layers and the base
     * set remain — the gap closes naturally. No-op when the layer isn't active.
     */
    private fun removeLayer(layerId: Long) {
        if (activeLayers.remove(layerId)) {
            publishActiveLayers()
            Log.d(TAG, "remove_layer($layerId): popped; stack depth=${activeLayers.size}")
        } else {
            Log.d(TAG, "remove_layer($layerId): not active — no-op")
        }
    }

    /**
     * `hold_layer`: while-held single-verb form. Activates the layer and binds its release
     * to the next UP on [address]. If [address] already holds another layer (duplicate DOWN
     * without an intervening UP), the prior layer is released first.
     */
    private fun holdLayer(address: InputAddress, layerId: Long) {
        heldLayerByAddress[address]?.let { existing ->
            Log.d(TAG, "hold_layer($layerId) on $address replacing prior held layer $existing")
            removeLayer(existing)
        }
        addLayer(layerId)
        heldLayerByAddress[address] = layerId
        Log.d(TAG, "hold_layer($layerId): linked to $address; release on UP")
    }

    /**
     * Release whatever layer (if any) is held by [address] (Brick 5.1). Called from the
     * normal release path and the forced-release path so a duplicate DOWN or set-switch
     * doesn't leak a held layer.
     */
    private fun releaseHeldLayer(address: InputAddress) {
        val layerId = heldLayerByAddress.remove(address) ?: return
        Log.d(TAG, "hold_layer release on $address: removing layer $layerId")
        removeLayer(layerId)
    }

    /**
     * Swap the runtime active set. Steam semantics: switching clears all transient
     * runtime state from the old set so a bound key currently held in set A doesn't
     * keep emitting in set B. [physicallyHeld] is preserved — it tracks physical
     * state, independent of which set is active, so a chord queried right after the
     * switch sees the user's actual buttons.
     */
    private fun setActiveSet(newSetId: Long) {
        if (newSetId == activeSetId) return
        val cfg = dispatcher.compiledConfig.value
        if (newSetId !in cfg.sets) {
            Log.w(TAG, "CHANGE_PRESET target set $newSetId not in compiled config; ignoring")
            return
        }
        val previous = activeSetId
        flushAllRuntime()
        activeSetId = newSetId
        Log.d(TAG, "active set: $previous -> $newSetId")
    }

    /**
     * Release everything currently in flight: held bindings (with their releases sent),
     * pending timers, in-flight double-tap windows, long-press deferrals, toggle latches,
     * hold-to-repeat jobs, fire-start-delay jobs, active chords, and the layer stack
     * (Brick 5.1: "Switching action sets clears ALL layers of the old set" per Steam).
     * Called on set-switch (Brick 4.2 / 5.1). Physical state ([physicallyHeld]) is
     * left intact.
     */
    private fun flushAllRuntime() {
        for ((_, entries) in held) {
            for (entry in entries) {
                entry.bindings.forEach(emitter::emitRelease)
            }
        }
        held.clear()
        for ((_, bucket) in pending) {
            bucket.values.forEach { it.cancel() }
        }
        pending.clear()
        for ((_, window) in doubleTapWindows) {
            window.timerJob?.cancel()
        }
        doubleTapWindows.clear()
        longPressDeferrals.clear()
        for ((_, st) in activatorState) {
            if (st.toggledOn) {
                st.toggledBindings.forEach(emitter::emitRelease)
                st.toggledOn = false
                st.toggledBindings = emptyList()
                st.toggledAddress = null
            }
            st.repeatJob?.cancel()
            st.repeatJob = null
            st.startDelayJob?.cancel()
            st.startDelayJob = null
        }
        activeChords.clear()
        if (activeLayers.isNotEmpty()) {
            Log.d(TAG, "flushAllRuntime: clearing ${activeLayers.size} active layer(s) $activeLayers")
            activeLayers.clear()
            publishActiveLayers()
        }
        heldLayerByAddress.clear()
        flushAnalog()
    }

    /**
     * Brick 4: release any analog-mode runtime state. Called from [flushAllRuntime]
     * (set-switch path) and from `MotionCaptureCoordinator` on profile transitions
     * so a `CHANGE_PRESET` or profile swap doesn't leak in-flight analog state —
     * synthetic SOFT_PRESS edges latched on a trigger, synthetic dpad edges (Brick
     * 6) held by a JOYSTICK_MOVE source, etc. — into the new set / profile.
     *
     * Brick 5 fills in trigger Soft_Press. For each latched virtual sub-input we
     * synthesize the matching UP edge so any held bindings the analog path put
     * into [held] get released through the same release path a real falling
     * edge would use — guarantees parity with normal release semantics (fire-end
     * delay, hold-to-repeat cancellation, etc.).
     */
    fun flushAnalog() {
        if (analogLatched.isEmpty()) return
        Log.d(TAG, "flushAnalog: releasing ${analogLatched.size} latched synthetic edge(s)")
        // Snapshot before mutation — dispatchSyntheticEdge writes back into
        // analogLatched on each call.
        val snapshot = analogLatched.keys.toList()
        for (address in snapshot) {
            dispatchSyntheticEdge(address.source, address.inputKey, isDown = false)
        }
        // Safety: dispatchSyntheticEdge should have cleared each entry on its
        // own (isDown=false → remove), but a future mode that skips on stale
        // state could leave an orphan. Clear unconditionally.
        analogLatched.clear()
    }

    /** Test seam: which action set is currently active. Returns 0L before first event. */
    internal fun currentActiveSetId(): Long = activeSetId

    /** Test seam: how many addresses are currently in the held set. */
    internal fun heldAddressCount(): Int = held.size

    /** Test seam: how many addresses currently have pending timers. */
    internal fun pendingAddressCount(): Int = pending.size

    /** Test seam: how many addresses have an in-flight DOUBLE_PRESS detection window. */
    internal fun doubleTapWindowCount(): Int = doubleTapWindows.size

    /** Test seam: is the given activator currently toggled-on? */
    internal fun isToggledOn(activatorId: Long): Boolean =
        activatorState[activatorId]?.toggledOn == true

    /** Test seam: number of activators with an active repeat (turbo) job. */
    internal fun activeRepeatJobCount(): Int =
        activatorState.values.count { it.repeatJob?.isActive == true }

    /** Test seam: number of active CHORDED_PRESS activations. */
    internal fun activeChordCount(): Int = activeChords.size

    /** Test seam: how many layers are currently active in the stack. */
    internal fun activeLayerCount(): Int = activeLayers.size

    /** Test seam: active layer ids, bottom-of-stack first. Top-of-stack is the last entry. */
    internal fun activeLayerIds(): List<Long> = activeLayers.toList()

    /** Test seam: is the given layer currently in the active stack? */
    internal fun isLayerActive(layerId: Long): Boolean = layerId in activeLayers

    companion object {
        private const val TAG = "InputEvaluator"
        /** Distinct from [TAG] so motion logs can be filtered independently (`-s InputEvaluator.Motion`). */
        private const val TAG_MOTION = "InputEvaluator.Motion"
    }
}
