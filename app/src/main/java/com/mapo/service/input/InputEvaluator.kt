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
    private val mouseEmitter: MouseEmitterImpl,
    private val gamepadEmitter: com.mapo.service.shizuku.ShizukuGamepadInjector,
    private val haptics: HapticEmitter,
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
     * list every time the stack mutates so [ShizukuMotionCoordinator][com.mapo.service.shizuku.ShizukuMotionCoordinator]
     * can re-evaluate its gating predicate without polling. Internal code keeps using
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
     * Phase 7 Brick B.5 — active mode shifts. A single trigger press can activate
     * multiple shifts (e.g. RB shifts both LJ and RJ to different modes), so this
     * is a list, not a map keyed by trigger.
     *
     * Lifecycle: shifts whose `(triggerSource, triggerSubInput)` matches an
     * incoming press are appended in [activateModeShiftsFor]; shifts whose
     * trigger matches an incoming release are removed in [releaseModeShiftFor];
     * [flushAllRuntime] clears the list on profile/set switch. Steam-faithful:
     * mode shifts always release on UP, never sticky.
     *
     * Multi-shift on same target source: the most-recently-activated wins
     * (mirrors action-layer last-write-wins). Iteration order is insertion
     * order — newer entries appear later, so iteration writes the last match
     * into `winner` for that source.
     */
    private val activeModeShifts = mutableListOf<ActiveModeShift>()

    /**
     * Phase 7 Brick B.5 — one entry in [activeModeShifts]. [targetSource] is the
     * source being overridden; [targetGroupId] looks up into
     * [CompiledConfig.compiledGroups] to materialize the override's mode +
     * sub-input bindings. [triggerAddress] is the physical input whose UP will
     * deactivate this shift.
     */
    private data class ActiveModeShift(
        val triggerAddress: InputAddress,
        val targetSource: InputSource,
        val targetGroupId: Long,
    )

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
     * semantics as the internal field. [ShizukuMotionCoordinator][com.mapo.service.shizuku.ShizukuMotionCoordinator]
     * observes this to re-evaluate the gating predicate on `CHANGE_PRESET`.
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
     * Phase 7 follow-up: CHORDED_PRESS activators deferred by a coexisting LONG_PRESS on
     * the same address (with chord's `interruptable=true`). Parallel to [longPressDeferrals]
     * but for chords. On LONG fire: popped + discarded (chord suppressed by LONG). On UP
     * before LONG threshold: popped + fired retroactively *iff* the chord's partner is
     * still physically held. Chord+DOUBLE coexistence is left out of this path — the
     * DOUBLE window's ~190ms latency on chord activation is awkward in practice; if
     * chord coexists with DOUBLE, chord fires synchronously at DOWN as if interruptable
     * were false. Document as a known gap if it ever matters.
     */
    private val pendingChords = HashMap<InputAddress, CompiledActivator>()

    /**
     * Phase 7 follow-up: addresses where a "more specific" activator (LONG_PRESS,
     * DOUBLE_PRESS, or CHORDED_PRESS) fired during the current press cycle. Consulted
     * by RELEASE_PRESS in onRelease: if its `interruptable=true` AND this set contains
     * the address, RELEASE_PRESS is suppressed. Cleared per-address at the end of
     * onRelease; [flushAllRuntime] clears the whole set on profile/set switch.
     */
    private val moreSpecificFiredFor = HashSet<InputAddress>()

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

    /**
     * True iff the Shizuku UserService currently holds `EVIOCGRAB` on the
     * physical controller. When set, DEVICE_DEFAULT analog readings AND
     * raw key events route through the virtual gamepad as a passthrough
     * (the OS can't dispatch the original events while grabbed, so Mapo
     * has to re-emit them). Toggled by [setPhysicalPassthroughEnabled];
     * driven from [com.mapo.service.shizuku.ShizukuMotionCoordinator]'s
     * decision loop.
     */
    private val physicalPassthroughEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Coordinator hook for the EVIOCGRAB pipeline. See
     * [physicalPassthroughEnabled].
     */
    fun setPhysicalPassthroughEnabled(enabled: Boolean) {
        val changed = physicalPassthroughEnabled.compareAndSet(!enabled, enabled)
        if (changed) {
            Log.i(TAG, "physical passthrough ${if (enabled) "ENABLED" else "DISABLED"}")
        }
    }

    /**
     * Snapshot of the active mode per gamepad-emitting source, used to detect
     * mode changes across compile-config emissions. When a source's mode
     * changes (e.g. user switches Gyro from Joystick Deflection to Mouse),
     * that source's contribution to the virtual gamepad — and any
     * stateful-mode bookkeeping (Deflection's reference orientation,
     * DpadMode's gyro integrator) — has to be cleared so the new mode
     * starts clean. Without this, a stick deflection from the prior mode
     * persists in the gamepad cache and the character keeps moving in the
     * direction the user had been tilting before switching away.
     */
    private val priorModeBySource = mutableMapOf<InputSource, com.mapo.data.model.steam.BindingMode?>()

    @Volatile
    private var configWatcherJob: kotlinx.coroutines.Job? = null

    /**
     * Start the compiled-config watcher coroutine. Idempotent — safe to call
     * multiple times. Resets stale gamepad/mode state on the
     * [GAMEPAD_EMITTING_SOURCES] axis whenever any source's mode flips.
     * Active-set / layer / mode-shift changes all funnel through
     * compiledConfig too, so layer overrides + mode shifts get this same
     * cleanup automatically.
     *
     * Called from [com.mapo.service.InputAccessibilityService.onServiceConnected]
     * — the lifecycle point where the gamepad output path becomes active.
     * Lifted out of `init` so tests don't get an unkillable
     * forever-collecting coroutine parented to their TestScope.
     */
    fun start() {
        if (configWatcherJob?.isActive == true) {
            Log.d(TAG, "start: config watcher already active")
            return
        }
        configWatcherJob = scope.launch {
            try {
                dispatcher.compiledConfig.collect { compiled ->
                    handleConfigChange(compiled)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "compiledConfig watcher crashed", t)
            }
        }
    }

    /**
     * Stop the compiled-config watcher. Called from
     * [com.mapo.service.InputAccessibilityService.onUnbind].
     */
    fun stop() {
        configWatcherJob?.cancel()
        configWatcherJob = null
    }

    /**
     * Compare each gamepad-emitting source's current mode against the prior
     * snapshot. For sources whose mode changed, clear that source's
     * contribution to the virtual gamepad and reset any per-mode state
     * (Deflection's captured reference, DpadMode's gyro integrator).
     * MouseEmitter velocities are scoped per-source via the same key, so
     * we also zero those.
     */
    private fun handleConfigChange(compiled: CompiledConfig) {
        // Re-center + drop any digital→joystick synthesis held state; the source may
        // have just left JOYSTICK_MOVE mode, which would otherwise strand its stick.
        for (src in digitalStickHeld.keys) gamepadEmitter.clearSource(src)
        digitalStickHeld.clear()
        // Drop hat-derived held directions; the subsequent dpadActiveInputs / onRelease
        // sweep below releases anything they pressed through handleDigital, and the next
        // hat reading re-presses cleanly against an empty held set.
        hatHeldDirections.clear()
        // Stop any analog-emulation pulses (releasing their bindings), release any
        // single-active gated directions, then drop the gate state — so nothing sticks
        // across a mode/config change.
        for (addr in dpadPulseJobs.keys.toList()) stopDpadPulse(addr)
        for ((src, active) in dpadActiveInputs) for (k in active) onRelease(InputAddress(src, k))
        dpadActiveInputs.clear()
        dpadHeldOrder.clear()
        // Drop any held directional stick-OUTPUT bindings so they don't stick across change.
        emitter.resetStickOutputs()
        // Clear the analog trigger-OUTPUT contributions (TriggerMode "Analog output trigger").
        // The output axis / on-off is a setting, so changing it is a config change that
        // doesn't flip the source's mode — without this, switching the output axis (or to
        // Off) while the trigger is at rest would strand the old axis's value. The next
        // trigger reading re-establishes the target axis.
        gamepadEmitter.clearSource(InputSource.LEFT_TRIGGER)
        gamepadEmitter.clearSource(InputSource.RIGHT_TRIGGER)
        // Same rationale for the joysticks: Output Joystick (which virtual stick a
        // source emits as) is a JOYSTICK_MOVE setting, not a mode flip. Without an
        // unconditional clear, flipping it (or any stick setting) while the stick is
        // deflected would strand the previously-targeted stick's value. The next
        // stick reading re-establishes the correct target.
        gamepadEmitter.clearSource(InputSource.LEFT_JOYSTICK)
        gamepadEmitter.clearSource(InputSource.RIGHT_JOYSTICK)
        // Reset JoystickMove travel-haptic state so a stale last-position doesn't
        // produce a spurious travel spike (and buzz) after the config change.
        com.mapo.service.input.modes.JoystickMoveMode.resetState()
        val startingSetId = compiled.startingActionSetId
        val set = compiled.sets[startingSetId] ?: return
        for (source in GAMEPAD_EMITTING_SOURCES) {
            val current = set.inputs.entries.firstOrNull { it.key.source == source }?.value?.mode
            val prior = priorModeBySource[source]
            if (priorModeBySource.containsKey(source) && prior != current) {
                Log.i(TAG, "mode change on $source: $prior → $current — clearing stale gamepad/mouse state")
                gamepadEmitter.clearSource(source)
                mouseEmitter.setStickVelocity(source, 0f, 0f)
                if (source == InputSource.GYRO) {
                    com.mapo.service.input.modes.GyroToJoystickDeflectionMode.resetState()
                    com.mapo.service.input.modes.DpadMode.resetState()
                    com.mapo.service.input.modes.GyroFlickStickMode.resetState()
                }
                // FlickStickMode runs on joystick sources; its per-source
                // state machine (NEUTRAL/FLICKING/HOLDING) must reset on
                // mode change so a deflected stick from a prior mode doesn't
                // leak into the flick state.
                if (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) {
                    com.mapo.service.input.modes.FlickStickMode.resetState()
                    com.mapo.service.input.modes.ScrollWheelMode.resetState()
                }
            }
            priorModeBySource[source] = current
        }
    }

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
     * Lookup with mode-shift + layer-stack precedence (Brick 5.1 layers + Phase 7
     * Brick B mode shifts). Resolution order, highest priority first:
     *  1. Active mode shifts on this address's source — most recent wins.
     *  2. Action layer stack (top-down, last entry = highest priority).
     *  3. Base set's inputs.
     *
     * Mode shifts outrank layers per Steam semantics: a mode shift is the
     * momentary "while held this source becomes X" — it's more specific than
     * any layer override and should override an active layer's binding for the
     * same address.
     */
    private fun lookupActive(address: InputAddress): CompiledInput? {
        val set = resolveActiveSet() ?: return null
        val modeShiftHit = resolveModeShiftInput(address)
        if (modeShiftHit != null) return modeShiftHit
        if (activeLayers.isNotEmpty()) {
            for (i in activeLayers.indices.reversed()) {
                val overlay = set.layers[activeLayers[i]]?.inputs?.get(address)
                if (overlay != null) return overlay
            }
        }
        return set.inputs[address]
    }

    /**
     * Phase 7 Brick B — materialize a [CompiledInput] from any active mode shift on
     * [address]'s source. Returns null when no mode shift targets this source, or
     * when the target group has no entry for [address.inputKey]. Multiple shifts
     * on the same source: most recently activated wins (last entry in iteration).
     */
    private fun resolveModeShiftInput(address: InputAddress): CompiledInput? {
        if (activeModeShifts.isEmpty()) return null
        val cfg = dispatcher.compiledConfig.value
        // Last-activated wins on collision; iterate insertion order, overwrite.
        var winner: ActiveModeShift? = null
        for (shift in activeModeShifts) {
            if (shift.targetSource == address.source) winner = shift
        }
        val shift = winner ?: return null
        val group = cfg.compiledGroups[shift.targetGroupId] ?: return null
        val activators = group.inputs[address.inputKey] ?: return null
        return CompiledInput(
            groupInputId = 0L, // mode-shift overlay has no stable GroupInput id surface
            activators = activators,
            mode = group.mode,
            modeSettingsJson = group.modeSettingsJson,
        )
    }

    /**
     * Handle a digital press/release at [address]. Returns true if the event was consumed
     * (at least one configured activator acted on it, OR the source is in
     * [BindingMode.NONE] mode), false if Mapo doesn't intercept this source / address
     * and the physical event should pass through to the foreground app (Mapo's
     * [BindingMode.DEVICE_DEFAULT] semantic).
     *
     * Phase 7 Brick A: NONE-mode sources consume-and-silence by returning true here
     * before ever reaching [onPress] / [onRelease]. This is what makes NONE distinct
     * from DEVICE_DEFAULT — both have no bindable sub-inputs, but DEVICE_DEFAULT
     * falls through and NONE intercepts.
     */
    fun handleDigital(address: InputAddress, isDown: Boolean): Boolean {
        val activeSet = resolveActiveSet()
        if (activeSet != null && address.source in activeSet.noneModeSources) {
            // NONE mode: Mapo intercepts and silences. No activators fire; we
            // simply consume the event. UP edges follow the same path so the
            // game never sees the press OR the release.
            Log.d(TAG, "handleDigital: NONE-mode source ${address.source} — consuming + silencing")
            return true
        }
        // Analog TRIGGER mode owns full_pull via the analog axis. The hardware trigger
        // CLICK (KEYCODE_BUTTON_L2/R2 → full_pull, or BTN_TL2/TR2 under grab) trips at a
        // device-specific pull depth — ~2% on AYN Thor — which has nothing to do with
        // Steam's "Full Pull = end of travel". TriggerMode.evaluate synthesizes full_pull
        // (and soft_pull) from the analog magnitude at the configured thresholds, so
        // ignore the hardware click here to avoid an early/duplicate fire. The analog
        // synthesis reaches the engine via dispatchSyntheticEdge → onPress (not
        // handleDigital), so this suppression doesn't touch it. Device Default keeps the
        // hardware click (no sentinel/mode); Trigger (Digital)=SINGLE_BUTTON keeps it too.
        if ((address.source == InputSource.LEFT_TRIGGER || address.source == InputSource.RIGHT_TRIGGER) &&
            address.inputKey == TriggerMode.FULL_PULL_SUB_INPUT
        ) {
            val modeHere = lookupActive(address)?.mode
                ?: lookupActive(InputAddress(address.source, SOURCE_MODE_SENTINEL_KEY))?.mode
            if (modeHere == BindingMode.TRIGGER) {
                Log.d(TAG, "handleDigital: ignoring hardware full_pull click on ${address.source} — analog TRIGGER mode owns it")
                return true
            }
        }
        // Digital → joystick synthesis: a digital cluster (face buttons OR the D-Pad) in
        // JOYSTICK_MOVE mode emits a virtual gamepad stick rather than firing per-button
        // bindings. The sub-inputs aren't bindable in this mode (validInputsFor returns
        // none), so the source-mode sentinel carries the mode + settings; intercept here
        // and consume.
        if (address.source == InputSource.BUTTON_DIAMOND || address.source == InputSource.DPAD) {
            val sentinel = lookupActive(InputAddress(address.source, SOURCE_MODE_SENTINEL_KEY))
            if (sentinel?.mode == BindingMode.JOYSTICK_MOVE) {
                updateDigitalStick(address, isDown, sentinel.modeSettingsJson)
                return true
            }
        }
        // Digital directional-pad gating: a digital cluster (face buttons / d-pad) in
        // DPAD mode with a single-active layout (4-way / cross-gate) routes presses
        // through a press-order gate so only one direction is active at a time. 8-way
        // and analog-emulation pass straight through (handled by the normal path below).
        if (address.source == InputSource.BUTTON_DIAMOND || address.source == InputSource.DPAD) {
            val input = lookupActive(address)
            if (input != null && input.mode == BindingMode.DPAD) {
                val layout = com.mapo.service.input.modes.DirectionalPadLayout.parse(input.modeSettingsJson)
                if (!layout.isDigitalPassthrough) {
                    handleDigitalDpad(address, isDown, layout)
                    return true
                }
            }
        }
        // Device-default digital passthrough → MVG (only while grabbed). When the
        // controller is grabbed the game reads the MVG, not the physical pad — so a
        // digital button that would otherwise pass through physically (a device-default
        // source with no compiled input, or an Unbound binding) lands on the physical
        // controller, which the game ignores. Forward it to the MVG's matching button so
        // "[Device default]" genuinely passes through. handleRawKeyReading already does
        // this for buttons on the GRABBED node before reaching handleDigital; this covers
        // buttons on a SEPARATE un-grabbed node (e.g. the AYN Thor's face buttons) that
        // arrive via onKeyEvent. Remapped buttons (a real, non-Unbound binding) fall
        // through to onPress and fire their remap as before.
        if (physicalPassthroughEnabled.get()) {
            val btn = ADDRESS_TO_GAMEPAD_BUTTON[address]
            if (btn != null && wouldPassThrough(address)) {
                gamepadEmitter.setButton(btn, isDown)
                Log.d(TAG, "handleDigital: device-default ${address.source}.${address.inputKey} → MVG btn passthrough (grabbed)")
                return true
            }
        }
        return if (isDown) onPress(address) else onRelease(address)
    }

    /**
     * True if [address] has no real remap to fire — either its source is device-default
     * (no compiled input) or every binding on it is [BindingOutput.Unbound]. Used to
     * decide whether a grabbed digital button should be forwarded to the MVG as a
     * passthrough rather than firing a remap.
     */
    private fun wouldPassThrough(address: InputAddress): Boolean {
        val input = lookupActive(address) ?: return true
        return input.activators.all { a -> a.bindings.all { it is BindingOutput.Unbound } }
    }

    /** Press-ordered held directions per source, for single-active dpad layouts (4-way/cross-gate). */
    private val dpadHeldOrder = HashMap<InputSource, MutableList<String>>()

    /** Currently-active (pressed-through or pulsing) dpad sub-inputs per source. */
    private val dpadActiveInputs = HashMap<InputSource, MutableSet<String>>()

    /** Analog-emulation PWM jobs, keyed by the directional sub-input address. */
    private val dpadPulseJobs = HashMap<InputAddress, Job>()

    /**
     * Directional-pad gate for digital clusters. Folds the just-changed direction into the
     * press-ordered held list, resolves which sub-input(s) should be active for [layout]
     * (4-way = latest press; cross-gate = first press; analog-emulation = all held), then:
     *  - single-active layouts drive the activator engine via [onPress]/[onRelease],
     *    suppressing the rest so a diagonal collapses to one command;
     *  - analog-emulation PWM-pulses each active direction to fake an analog stick.
     */
    private fun handleDigitalDpad(
        address: InputAddress,
        isDown: Boolean,
        layout: com.mapo.service.input.modes.DirectionalPadLayout,
    ) {
        val source = address.source
        val order = dpadHeldOrder.getOrPut(source) { mutableListOf() }
        if (isDown) {
            if (address.inputKey !in order) order.add(address.inputKey)
        } else {
            order.remove(address.inputKey)
        }
        val desired = com.mapo.service.input.modes.activeDirectionalInputs(layout, order)
        val active = dpadActiveInputs.getOrPut(source) { mutableSetOf() }
        val pulsing = layout == com.mapo.service.input.modes.DirectionalPadLayout.ANALOG_EMULATION
        for (k in active - desired) {
            val a = InputAddress(source, k)
            if (pulsing) stopDpadPulse(a) else onRelease(a)
        }
        for (k in desired - active) {
            val a = InputAddress(source, k)
            if (pulsing) startDpadPulse(a) else onPress(a)
        }
        dpadActiveInputs[source] = desired.toMutableSet()
        Log.d(TAG, "digitalDpad $source $layout held=$order active=$desired pulsing=$pulsing")
    }

    /** Bindings to PWM for analog emulation — every binding on the sub-input's activators. */
    private fun dpadPulseBindings(addr: InputAddress): List<BindingOutput> =
        lookupActive(addr)?.activators?.flatMap { it.bindings }.orEmpty()

    /**
     * Analog emulation: PWM the direction's command(s) at a fixed duty cycle so a digital
     * full-press reads as analog-ish movement. Pulses at the binding/emitter level (like
     * turbo) rather than re-entering the activator engine, so it stays off the evaluator's
     * shared state from the coroutine. Duty/rate are tunable constants.
     */
    private fun startDpadPulse(addr: InputAddress) {
        dpadPulseJobs[addr]?.cancel()
        val bindings = dpadPulseBindings(addr)
        if (bindings.isEmpty()) return
        dpadPulseJobs[addr] = scope.launch {
            while (isActive) {
                bindings.forEach { emitter.emitPress(it) }
                delay(DPAD_PULSE_ON_MS)
                bindings.forEach { emitter.emitRelease(it) }
                delay(DPAD_PULSE_OFF_MS)
            }
        }
    }

    private fun stopDpadPulse(addr: InputAddress) {
        dpadPulseJobs.remove(addr)?.cancel()
        // Ensure nothing's left held if cancelled mid-pulse.
        dpadPulseBindings(addr).forEach { emitter.emitRelease(it) }
    }

    /** Held digital direction sub-inputs per source, for Button-Pad → joystick synthesis. */
    private val digitalStickHeld = HashMap<InputSource, MutableSet<String>>()

    /**
     * Last hat-derived held directions per source, for `ABS_HAT0` → digital edge
     * synthesis (see [dispatchHatAsDigital]). Mapo's target hardware reports the
     * physical D-Pad as a hat axis, not `KEYCODE_DPAD_*`, so the grabbed hat reading
     * is translated here into the same `dpad_*` press/release edges a synthesized
     * keycode would have produced.
     */
    private val hatHeldDirections = HashMap<InputSource, MutableSet<String>>()

    /**
     * Bridge a hat-axis reading (`ABS_HAT0X` / `ABS_HAT0Y`, values ≈ −1 / 0 / +1)
     * into the digital `dpad_*` edge pipeline. Mapo's targets report the physical
     * D-Pad as a hat — no `KEYCODE_DPAD_*` reaches `onKeyEvent` — so while grabbed
     * the hat surfaces here as an [AnalogEvent]. We diff the desired direction set
     * against what was last held and feed [handleDigital] one edge per change, so
     * every D-Pad mode (Directional Pad gate / Button Pad bindings / Joystick
     * synthesis / None silence) runs through the exact same path a real keycode
     * would. The two hat axes are independent, so diagonals (x=±1 AND y=±1) emit
     * two simultaneous directions, matching dual-keycode behavior.
     *
     * Y convention: `ABS_HAT0Y < 0` is up (kernel hat-up = −1), matching
     * [AnalogEvent]'s "y > 0 is screen-down" — so `y < 0` → `dpad_up`.
     */
    private fun dispatchHatAsDigital(reading: AnalogEvent) {
        val want = HashSet<String>(4)
        if (reading.y < -HAT_DIRECTION_THRESHOLD) want += "dpad_up"
        if (reading.y > HAT_DIRECTION_THRESHOLD) want += "dpad_down"
        if (reading.x < -HAT_DIRECTION_THRESHOLD) want += "dpad_left"
        if (reading.x > HAT_DIRECTION_THRESHOLD) want += "dpad_right"
        val held = hatHeldDirections.getOrPut(reading.source) { mutableSetOf() }
        if (want == held) return
        for (k in held - want) handleDigital(InputAddress(reading.source, k), false)
        for (k in want - held) handleDigital(InputAddress(reading.source, k), true)
        hatHeldDirections[reading.source] = want.toMutableSet()
    }

    /**
     * Update the synthesized virtual stick for a digital source in JOYSTICK_MOVE mode:
     * fold the just-changed button into the held set, rebuild the unit-ish vector,
     * apply the output settings, and emit to the configured gamepad stick. Emits (0,0)
     * when the last direction releases, re-centering the stick.
     */
    private fun updateDigitalStick(address: InputAddress, isDown: Boolean, settingsJson: String) {
        val held = digitalStickHeld.getOrPut(address.source) { mutableSetOf() }
        if (isDown) held.add(address.inputKey) else held.remove(address.inputKey)
        val (rx, ry) = com.mapo.service.input.modes.JoystickOutputSettings.directionalVector(held)
        val settings = com.mapo.service.input.modes.JoystickOutputSettings.parse(settingsJson)
        val (x, y) = settings.apply(rx, ry)
        // Synthesis math is intuitive (+Y = up); the virtual gamepad's Y axis is
        // +down, so negate at the emit boundary. (Same convention for the diamond
        // D-Pad source when that menu wires up.)
        val emitY = -y
        Log.d(TAG, "digitalStick ${address.source} held=$held -> (${"%.2f".format(x)},${"%.2f".format(emitY)}) stick=${settings.outputStick}")
        when (settings.outputStick) {
            com.mapo.service.input.modes.JoystickOutputSettings.OutputStick.LEFT ->
                gamepadEmitter.setLeftStick(address.source, x, emitY)
            com.mapo.service.input.modes.JoystickOutputSettings.OutputStick.RIGHT ->
                gamepadEmitter.setRightStick(address.source, x, emitY)
        }
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
     * **Brick D (Shizuku pivot):** thin adapter around [dispatchReadings] now. The
     * production analog source post-Brick-H is [handleAnalogReadings], driven by
     * Shizuku raw events. This entry stays for `MotionEvent`-shaped tests and any
     * legacy code path that still feeds the platform analog stream.
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
        dispatchReadings(readings)
        return false
    }

    /**
     * Brick D: Shizuku-driven entry point. Each [AnalogEvent] in [readings] is the
     * current value of one [InputSource] as observed by `:shizuku-service`'s
     * `/dev/input/event*` reader, already normalized to the same coordinate
     * conventions [AnalogEvent] documents.
     *
     * Unlike [handleMotion] there's no `MotionEvent` to consume / pass through —
     * the call comes from `ShizukuMotionStream` collecting AIDL callbacks, not
     * from the platform input pipeline.
     */
    fun handleAnalogReadings(readings: List<AnalogEvent>) {
        if (readings.isEmpty()) return
        val summary = readings.joinToString(" ") { r ->
            "${r.source}(${"%.3f".format(r.x)},${"%.3f".format(r.y)})"
        }
        Log.d(TAG_MOTION, "handleAnalogReadings $summary")
        dispatchReadings(readings)
    }

    /**
     * **Brick D.2** — gyro entry point. Parallel to [handleAnalogReadings];
     * driven by [com.mapo.service.input.GyroSensorStream] collecting
     * `SensorEvent`s from Android's `SensorManager`. Wraps a [GyroEvent] in
     * an [AnalogEvent] with `source = GYRO`, then dispatches through the
     * standard mode-resolution path.
     *
     * **Axis packing convention (Thor-calibrated landscape-natural).** Gyro
     * events carry rotation rate around three device-fixed axes; gyro modes
     * shipping in D.3+ only use two. The mapping was empirically verified on
     * AYN Thor (2026-05-31):
     *  - `AnalogEvent.x` ← `+GyroEvent.xRadPerSec` — rolling the device
     *    (one side dipping) drives cursor horizontal motion.
     *  - `AnalogEvent.y` ← `−GyroEvent.yRadPerSec` — pitching the device
     *    (top edge toward/away) drives cursor vertical motion. Sign
     *    inverted so pitching the device's top edge AWAY moves the cursor
     *    UP (matches natural "aim where you're pointing" feel).
     *  - `zRadPerSec` is dropped. Add a parallel entry point if a future
     *    mode needs it.
     *
     * **Why not Android's "X = pitch, Y = yaw"?** That convention applies to
     * portrait-natural devices. Mapo's targets (AYN Thor / Odin 2 Mini /
     * Anbernic / Retroid) are landscape-natural handhelds — the sensor's X
     * axis runs along the device's long horizontal edge in landscape, so
     * rotation around X reads as roll, not pitch. The pre-2026-05-31 code
     * assumed portrait convention and felt 90°-off on every Mapo target.
     *
     * Per-mode `invert_x` / `invert_y` settings handle user-preference sign
     * flips on top of this base mapping. Generic-Android calibration support
     * (e.g. devices with non-standard sensor frames) is deferred until a
     * device-spread test reveals one.
     *
     * **Values are rad/s, not [-1, 1].** Stick `AnalogEvent` readings are
     * normalized; gyro readings are raw angular velocity. Mode handlers
     * branch on `reading.source` and interpret accordingly — same idiom
     * `DpadMode` already uses to differentiate stick vs. dpad sources.
     */
    /**
     * Raw digital key event from the Shizuku UserService's `/dev/input`
     * EV_KEY pipeline. Fires only while the UserService has EVIOCGRAB held
     * on the physical controller, so the OS InputReader is no longer
     * dispatching these as Android KeyEvents through the accessibility
     * service's [com.mapo.service.InputAccessibilityService.onKeyEvent].
     *
     * Routing:
     *  - If [physicalPassthroughEnabled] is on AND the source's mode is
     *    `DEVICE_DEFAULT` (Mapo doesn't intercept), write the key
     *    verbatim to the virtual gamepad's matching button. Game sees a
     *    normal button press on the virtual XInput controller.
     *  - Otherwise: dispatch through [handleDigital], the same path
     *    `onKeyEvent` uses. Mode handler + activator engine + binding
     *    emit fire normally.
     */
    fun handleRawKeyReading(linuxKeyCode: Int, pressed: Boolean, @Suppress("UNUSED_PARAMETER") timestampNs: Long) {
        val address = LINUX_KEY_TO_ADDRESS[linuxKeyCode]
        if (address == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "handleRawKeyReading: ignoring unmapped linuxKeyCode=0x${linuxKeyCode.toString(16)}")
            }
            return
        }
        // Resolve mode at this address to decide passthrough vs. engine.
        // Same lookup the analog path uses — keeps the two paths aligned
        // on mode-shift / layer-override precedence.
        val set = resolveActiveSet()
        val resolved = set?.let { findSourceModeFor(it, address.source) }
        val isDeviceDefault = resolved?.mode == com.mapo.data.model.steam.BindingMode.DEVICE_DEFAULT
        // Brick C.5 follow-up: NONE-mode silencing for digital sources under
        // EVIOCGRAB. Like the analog path, NONE sources are excluded from
        // `inputs`, so `resolved == null` for them. Without this check they'd
        // fall straight into the DEVICE_DEFAULT passthrough below, writing
        // the physical button verbatim to the virtual gamepad and defeating
        // NONE's "Mapo intercepts and silences" semantic. Route them through
        // handleDigital instead — handleDigital's own NONE check returns
        // `true` to consume the event without dispatching any binding.
        val isNoneSource = set != null && address.source in set.noneModeSources
        if (physicalPassthroughEnabled.get() && !isNoneSource &&
            (isDeviceDefault || resolved == null)
        ) {
            val btn = LINUX_KEY_TO_GAMEPAD_BUTTON[linuxKeyCode]
            if (btn != null) {
                gamepadEmitter.setButton(btn, pressed)
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "rawKey passthrough: linuxKey=0x${linuxKeyCode.toString(16)} → btn=0x${btn.toString(16)} pressed=$pressed")
                }
            }
            return
        }
        // Engine path: same as a real onKeyEvent dispatch would do.
        handleDigital(address, pressed)
    }

    fun handleGyroReading(reading: GyroEvent) {
        val analog = AnalogEvent(
            source = InputSource.GYRO,
            x = reading.xRadPerSec,
            y = -reading.yRadPerSec,
            timestampMs = reading.timestampNs / 1_000_000L,
            // Yaw — only used by Directional Swipe today. Other gyro modes
            // (Mouse / Camera / Deflection / Dpad-on-gyro) interpret roll
            // (x) + pitch (y) and ignore z. Sign left raw on the empirical
            // expectation that positive z = CCW around the screen-normal
            // axis = top-edge-rotates-left = "left swipe" intent. Will flip
            // if device testing shows otherwise.
            z = reading.zRadPerSec,
            // Absolute orientation for tilt-based modes (Joystick Deflection).
            // Sourced from a parallel TYPE_GAME_ROTATION_VECTOR subscription
            // and cached on GyroSensorStream; populated on every gyro event
            // even when the rotation-vector cadence is slightly behind. Modes
            // that don't need orientation simply leave these unread.
            tiltRollRad = reading.rollRad,
            tiltPitchRad = reading.pitchRad,
        )
        if (Log.isLoggable(TAG_MOTION, Log.VERBOSE)) {
            Log.v(TAG_MOTION, "handleGyroReading GYRO(${"%.3f".format(analog.x)},${"%.3f".format(analog.y)})")
        }
        dispatchReadings(listOf(analog))
    }

    /**
     * Shared dispatch loop for both motion entry points. Walks each reading,
     * resolves its source's mode (with the active layer stack overlaid), and
     * lets the mode emit synthetic edges / continuous output. The mode handler
     * call is the only place that sees the reading — everything else is bookkeeping
     * around the active set + latched-edge priors.
     */
    private fun dispatchReadings(readings: List<AnalogEvent>) {
        val set = resolveActiveSet() ?: return
        for (reading in readings) {
            val resolved = findSourceModeFor(set, reading.source)
            val isNoneSource = reading.source in set.noneModeSources
            // Brick C.5: NONE-mode silencing for analog sources under
            // EVIOCGRAB. The compile path excludes NONE sources from
            // `inputs` (they have no bindable sub-inputs), so without this
            // branch findSourceModeFor returns null and the reading would
            // fall straight into the DEVICE_DEFAULT passthrough below —
            // writing the physical stick verbatim to the virtual gamepad
            // and defeating NONE's "Mapo intercepts and silences" semantic.
            //
            // Counter-inject zero so any prior contribution (e.g. from a
            // Mode Shift target that briefly wrote this source) doesn't
            // bleed through. handleConfigChange already clears the slot on
            // mode transitions; this is belt-and-suspenders for the steady-
            // state path. Branch is no-op when not grabbed because the
            // physical event still flows OS→game directly — NONE-mode
            // analog silencing only works while EVIOCGRAB is held, which
            // ShizukuMotionCoordinator gates on this same noneModeSources
            // walk.
            if (physicalPassthroughEnabled.get() && isNoneSource) {
                zeroPassthroughForSource(reading.source)
                continue
            }
            // Physical passthrough — when Mapo has EVIOCGRAB held on the
            // physical controller (Brick D EVIOCGRAB follow-up), the OS
            // can't dispatch the controller's events natively. To preserve
            // DEVICE_DEFAULT behavior, route the analog reading directly to
            // the virtual gamepad as a verbatim passthrough.
            //
            // `resolved == null` is treated as DEVICE_DEFAULT here too:
            // the compile path deliberately omits DEVICE_DEFAULT sources
            // from the inputs map (per CompiledConfig.compileInputs: "no
            // entry needed for the 'Mapo doesn't intercept' case"), so a
            // null resolve IS the DEVICE_DEFAULT signal for analog sources.
            // Without this branch, triggers — which default to
            // DEVICE_DEFAULT and are therefore absent from the inputs
            // map — would have their analog axis values dropped on the
            // floor under grab, leaving the game with no trigger input.
            //
            // Skipped when not grabbed because in normal operation the OS
            // already handles DEVICE_DEFAULT pass-through; double-writing
            // to the virtual gamepad would just compete with the OS.
            if (physicalPassthroughEnabled.get() &&
                (resolved == null || resolved.mode == com.mapo.data.model.steam.BindingMode.DEVICE_DEFAULT)
            ) {
                passthroughAnalogToGamepad(reading)
                continue
            }
            // DPAD bridge: Mapo's target hardware reports the physical D-Pad as an
            // ABS_HAT0 hat axis (no KEYCODE_DPAD_* reaches onKeyEvent), so the grabbed
            // hat arrives here as an analog reading. Translate it into digital dpad_*
            // edges through handleDigital, the same path a synthesized keycode would
            // take, so every emit mode (Directional Pad / Button Pad / Joystick) works.
            // None + Device Default for DPAD are already handled by the branches above.
            if (reading.source == InputSource.DPAD) {
                dispatchHatAsDigital(reading)
                continue
            }
            if (resolved == null) continue
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
                gamepad = gamepadEmitter,
                haptic = { intensity -> haptics.buzz(intensity) },
            )
            handler.evaluate(
                reading,
                ctx,
                digitalEmit = { subInput, isDown ->
                    dispatchSyntheticEdge(reading.source, subInput, isDown)
                },
                mouse = mouseEmitter,
            )
        }
    }

    /**
     * Write [reading] verbatim to the virtual gamepad's axis matching the
     * reading's source. Called from [dispatchReadings] when the OS-level
     * controller dispatch has been suppressed via EVIOCGRAB but the user
     * has the source in DEVICE_DEFAULT (Mapo doesn't intercept). Keeps
     * those axes flowing to the game even while the physical controller
     * is grabbed.
     *
     * Dpad triggers and joysticks use the analog hat / int16 stick axes;
     * triggers use the 0..1 single-axis convention.
     */
    private fun passthroughAnalogToGamepad(reading: AnalogEvent) {
        when (reading.source) {
            InputSource.LEFT_JOYSTICK -> gamepadEmitter.setLeftStick(reading.source, reading.x, reading.y)
            InputSource.RIGHT_JOYSTICK -> gamepadEmitter.setRightStick(reading.source, reading.x, reading.y)
            InputSource.LEFT_TRIGGER -> gamepadEmitter.setLeftTrigger(reading.source, reading.x)
            InputSource.RIGHT_TRIGGER -> gamepadEmitter.setRightTrigger(reading.source, reading.x)
            InputSource.DPAD -> gamepadEmitter.setDpadHat(
                reading.source,
                reading.x.roundedInt(),
                reading.y.roundedInt(),
            )
            else -> Unit
        }
    }

    /**
     * Brick C.5: write 0 to [source]'s slot in the virtual gamepad. Mirrors
     * [passthroughAnalogToGamepad]'s axis routing but discards the reading
     * value. Used for NONE-mode analog sources under EVIOCGRAB to enforce
     * silence — the game sees a centered stick / released trigger / no dpad
     * regardless of physical state.
     */
    private fun zeroPassthroughForSource(source: InputSource) {
        when (source) {
            InputSource.LEFT_JOYSTICK -> gamepadEmitter.setLeftStick(source, 0f, 0f)
            InputSource.RIGHT_JOYSTICK -> gamepadEmitter.setRightStick(source, 0f, 0f)
            InputSource.LEFT_TRIGGER -> gamepadEmitter.setLeftTrigger(source, 0f)
            InputSource.RIGHT_TRIGGER -> gamepadEmitter.setRightTrigger(source, 0f)
            InputSource.DPAD -> gamepadEmitter.setDpadHat(source, 0, 0)
            else -> Unit
        }
    }

    private fun Float.roundedInt(): Int = when {
        this > 0.5f -> 1
        this < -0.5f -> -1
        else -> 0
    }

    /**
     * Resolve which mode + settings apply to [source] given the active set + layer
     * stack + mode shifts. Precedence (highest first):
     *  1. Mode-shift override targeting this source — most recent wins.
     *  2. Action layer stack (top-down).
     *  3. Base set.
     *
     * Returns null when no binding_group covers the source (e.g. the source is
     * DEVICE_DEFAULT or never seeded).
     */
    private fun findSourceModeFor(set: CompiledActionSet, source: InputSource): SourceModeView? {
        // 1) Mode-shift override
        if (activeModeShifts.isNotEmpty()) {
            val cfg = dispatcher.compiledConfig.value
            var winner: ActiveModeShift? = null
            for (shift in activeModeShifts) {
                if (shift.targetSource == source) winner = shift
            }
            if (winner != null) {
                cfg.compiledGroups[winner.targetGroupId]?.let { group ->
                    return SourceModeView(group.mode, group.modeSettingsJson)
                }
            }
        }
        // 2) Action layer stack
        if (activeLayers.isNotEmpty()) {
            for (i in activeLayers.indices.reversed()) {
                val layer = set.layers[activeLayers[i]] ?: continue
                val match = layer.inputs.entries.firstOrNull { it.key.source == source }?.value
                if (match != null) return SourceModeView(match.mode, match.modeSettingsJson)
            }
        }
        // 3) Base set
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

        // Phase 7 Brick B.5: activate any mode shifts whose trigger == this address.
        // Done BEFORE binding evaluation so the trigger's own bindings (if any) still
        // fire normally — Steam-faithful additive semantics: a trigger button can
        // both emit its bindings AND drive a mode shift simultaneously.
        activateModeShiftsFor(address)

        // Second-tap path: an active DOUBLE_PRESS window on this address means this DOWN
        // is the second tap. Cancel the window, fire DOUBLE_PRESS (the deferred Regular is
        // dropped — that's the suppression behavior).
        val activeWindow = doubleTapWindows.remove(address)
        if (activeWindow != null) {
            activeWindow.timerJob?.cancel()
            Log.d(TAG, "double-tap detected at $address — firing DOUBLE_PRESS")
            firePressBindings(address, activeWindow.doubleActivator)
            moreSpecificFiredFor += address
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
        val chordActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.CHORDED_PRESS }
        // Chord fires synchronously at DOWN when its partner is already physically held
        // — unlike LONG/DOUBLE which "might fire later," chord's success or failure is
        // known the instant we arrive in onPress. We use this to decide chord-driven
        // FULL_PRESS suppression below.
        val chordWillFire = chordActivator != null && run {
            val partner = chordActivator.settings.chordPartner
            partner != null && partner != address && partner in physicallyHeld
        }
        // 3.3: interruptable now configurable. Steam default is true. A Regular Press is
        // deferred whenever it's interruptable AND a longer-duration activator (LONG or
        // DOUBLE) coexists — the longer activator gets first claim and suppresses Regular
        // if it fires. If it doesn't fire (user releases too early), the deferred Regular
        // fires retroactively as a tap.
        val deferRegular = regularActivator != null
            && regularActivator.settings.interruptable
            && (doubleActivator != null || longActivator != null)
        // Phase 7 follow-up: chord is the most specific activator on the address.
        // When it's about to fire AND Regular is interruptable, suppress Regular
        // outright — chord-firing is instant, so this is *not* a deferral (no
        // longPressDeferrals storage, no UP-side retroactive tap). Distinct from
        // [deferRegular] because that path optionally re-fires on UP.
        val suppressRegularForChord = regularActivator != null
            && regularActivator.settings.interruptable
            && chordWillFire

        var consumed = false
        for (activator in compiledInput.activators) {
            when (activator.type) {
                ActivatorType.FULL_PRESS -> {
                    if (suppressRegularForChord) {
                        // Chord wins; Regular is silenced entirely. No deferral storage
                        // (we don't want a retroactive fire on UP), no emit. We still
                        // mark consumed so the underlying input isn't forwarded to the
                        // app — the chord activator already owns this event.
                        Log.d(TAG, "FULL_PRESS suppressed by CHORDED_PRESS on $address")
                        consumed = true
                    } else if (deferRegular) {
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
                        // firePressBindings returns true when a holdable binding was
                        // emitted (KEY_PRESS / XINPUT_BUTTON). For fire-and-done outputs
                        // (MOUSE_BUTTON / MOUSE_WHEEL) emitter.emitPress returns false
                        // even though the activator effectively fired — fall through to
                        // mark consumed if the activator has any *bindable* output.
                        // Phase 7 Brick A: tightened the fallback to exclude all-Unbound
                        // activators so face buttons in `[Device Default]` (with seeded
                        // FULL_PRESS + UNBOUND placeholder bindings) correctly pass
                        // through to hardware-native behavior.
                        if (firePressBindings(address, activator)) consumed = true
                        else if (activator.bindings.any { it !is BindingOutput.Unbound }) consumed = true
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
                    // Phase 7 follow-up: chord's own interruptable=true defers it
                    // when a coexisting LONG_PRESS would later fire on the same
                    // input. The chord fires retroactively in onRelease if the
                    // user releases before LONG threshold AND partner is still held.
                    // DOUBLE+CHORD intentionally skipped (see [pendingChords] KDoc).
                    val deferForLong = activator.settings.interruptable && longActivator != null
                    when {
                        partner == null -> {
                            Log.d(TAG, "chord at $address ignored: no partner configured")
                        }
                        partner == address -> {
                            // Defensive — a chord with itself as the partner can never satisfy.
                            Log.d(TAG, "chord at $address ignored: partner equals chord address")
                        }
                        partner in physicallyHeld && deferForLong -> {
                            pendingChords[address] = activator
                            Log.d(TAG, "chord deferred at $address: LONG_PRESS coexists + interruptable")
                        }
                        partner in physicallyHeld -> {
                            if (firePressBindings(address, activator)) {
                                activeChords += ChordLink(
                                    chordAddress = address,
                                    partnerAddress = partner,
                                    activatorId = activator.activatorId,
                                )
                                moreSpecificFiredFor += address
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
        // Phase 7 follow-up: same retroactive-fire path for a CHORDED_PRESS that was
        // deferred at DOWN by a coexisting LONG_PRESS. Only fires if the chord's
        // partner is still physically held — chord semantics require both held.
        // Note: physicallyHeld.remove(address) ran at top of onRelease, so the
        // partner check here sees the user's current state minus this address.
        val deferredChord = pendingChords.remove(address)
        if (deferredChord != null) {
            val partner = deferredChord.settings.chordPartner
            if (partner != null && partner in physicallyHeld) {
                Log.d(TAG, "UP before LONG threshold on $address; firing deferred CHORDED_PRESS as tap")
                emitTap(deferredChord)
                moreSpecificFiredFor += address
            } else {
                Log.d(TAG, "deferred CHORDED_PRESS dropped on $address: partner $partner no longer held")
            }
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

        // Phase 7 Brick B: mode_shift triggered by this address releases here too,
        // before the RELEASE_PRESS path — same reasoning as hold_layer (any release
        // binding sees the mode-shift-off state).
        releaseModeShiftFor(address)

        // If a DOUBLE_PRESS window is still active for this address, this UP belongs to the
        // first tap. Note the physical state — the window timer uses it later to decide
        // whether the deferred Regular fires as a tap or as held.
        doubleTapWindows[address]?.physicallyHeld = false

        val compiledInput = lookupActive(address)
        // Phase 7 follow-up: snapshot whether a more-specific activator (LONG, DOUBLE,
        // CHORD) fired during this press cycle. Consulted by RELEASE_PRESS below.
        // Cleared at end of onRelease so the next cycle starts clean.
        val moreSpecificFired = address in moreSpecificFiredFor
        compiledInput?.activators?.forEach { activator ->
            // Cancel any in-flight fire_start_delay timer — user released before it fired.
            if (activator.settings.fireStartDelayMs > 0) {
                stateFor(activator.activatorId).startDelayJob?.cancel()
            }
            if (activator.type == ActivatorType.RELEASE_PRESS) {
                if (activator.settings.interruptable && moreSpecificFired) {
                    Log.d(TAG, "RELEASE_PRESS suppressed at $address by a more-specific activator")
                } else {
                    emitTap(activator)
                }
            }
        }
        moreSpecificFiredFor -= address

        val records = held.remove(address)
        if (records == null) {
            // Phase 7 Brick A fix: an all-Unbound CompiledInput (the seed-default
            // shape for face buttons in [Device Default] mode) must pass UP
            // through to match its pass-through DOWN. Without this, the game
            // sees DOWN-without-UP and interprets the tap as a long-press.
            // Mirror the onPress fallback: consume only if at least one
            // activator has a bindable (non-Unbound) output.
            val hasRealOutput = compiledInput?.activators?.any { activator ->
                activator.bindings.any { it !is BindingOutput.Unbound }
            } == true
            return hasRealOutput
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
            // Phase 7 follow-up: same suppression for deferred CHORDED_PRESS.
            val suppressedChord = pendingChords.remove(address)
            if (suppressedChord != null) {
                Log.d(TAG, "LONG_PRESS firing; suppressing deferred CHORDED_PRESS on $address")
            }
            moreSpecificFiredFor += address
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
            if (emitter.emitPress(binding, activator.settings.sendAsGesture)) holdable += binding
        }
        // Haptic on activation — fired after the inject so it stays off the critical path.
        haptics.buzz(activator.effectiveHaptic)

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
        // Split the repeat period into a real ON phase + OFF phase. A 0ms press→release
        // (the old behavior) is dropped by POLLED consumers — notably the virtual gamepad:
        // a game samples the pad at 60-250Hz and never sees a sub-frame button press, so
        // turbo on any MVG-routed output (gamepad button / dpad / trigger) registered as
        // nothing. Holding for onMs guarantees the press straddles at least one poll.
        val rate = activator.settings.repeatRateMs.coerceAtLeast(MIN_TURBO_PERIOD_MS)
        val onMs = (rate / 2).coerceAtLeast(TURBO_MIN_PHASE_MS)
        val offMs = (rate - onMs).coerceAtLeast(TURBO_MIN_PHASE_MS)
        val bindings = activator.bindings
        val sendAsGesture = activator.settings.sendAsGesture
        Log.d(TAG, "startRepeatJob: turbo activator=${activator.activatorId} rate=${rate}ms (on=$onMs/off=$offMs) bindings=${bindings.size}")
        st.repeatJob = scope.launch {
            while (isActive) {
                // ON: press (the initial doEmitPress press overlaps the first ON harmlessly).
                val held = ArrayList<BindingOutput>(bindings.size)
                for (binding in bindings) {
                    // Repeat path: no owning address. `hold_layer` skips with a warning;
                    // `add_layer` / `remove_layer` repeat harmlessly (already-active is a no-op).
                    if (tryHandleControllerAction(binding, address = null)) continue
                    if (emitter.emitPress(binding, sendAsGesture)) held += binding
                }
                delay(onMs)
                // OFF: release the held outputs, then wait out the rest of the period.
                for (binding in held) emitter.emitRelease(binding)
                delay(offMs)
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
            val holdable = emitter.emitPress(binding, activator.settings.sendAsGesture)
            if (holdable) emitter.emitRelease(binding)
        }
        // Haptic on this tap activation (START_PRESS / RELEASE_PRESS / expired-double).
        haptics.buzz(activator.effectiveHaptic)
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
     * Phase 7 Brick B.5 — append every mode shift whose trigger matches [address]
     * to [activeModeShifts]. Walks both the active set's shifts and every
     * currently-active layer's shifts. Idempotent on duplicate DOWN — the
     * caller's [onPress] doesn't suppress duplicates here because most paths
     * are already protected by [forceReleaseAddress] when a stale held entry
     * exists; the duplicate-shift scenario is rare (flaky controllers) and
     * the worst case is two list entries that both release on the same UP.
     */
    private fun activateModeShiftsFor(address: InputAddress) {
        val set = resolveActiveSet() ?: return
        // Active set's set-owned shifts: always candidate.
        for (def in set.modeShifts) {
            if (def.triggerAddress == address) {
                activeModeShifts += ActiveModeShift(
                    triggerAddress = address,
                    targetSource = def.ownerSource,
                    targetGroupId = def.targetGroupId,
                )
                Log.d(TAG, "mode_shift activated: trigger=$address target=${def.ownerSource} group=${def.targetGroupId}")
            }
        }
        // Layer-owned shifts: only candidate while the owning layer is in the
        // active stack. If a layer is removed mid-press, any shift it activated
        // stays in activeModeShifts until the trigger UP — Steam-faithful
        // "while held" semantics, matches hold_layer behavior.
        if (activeLayers.isNotEmpty()) {
            for (layerId in activeLayers) {
                val layer = set.layers[layerId] ?: continue
                for (def in layer.modeShifts) {
                    if (def.triggerAddress == address) {
                        activeModeShifts += ActiveModeShift(
                            triggerAddress = address,
                            targetSource = def.ownerSource,
                            targetGroupId = def.targetGroupId,
                        )
                        Log.d(TAG, "mode_shift activated (layer $layerId): trigger=$address target=${def.ownerSource} group=${def.targetGroupId}")
                    }
                }
            }
        }
    }

    /**
     * Phase 7 Brick B.5 — release any mode shifts triggered by [address]'s DOWN. Called
     * from [onRelease] before the held-binding release path; mode shifts have no
     * binding-release side effects, just a runtime state cleanup. No-op if no
     * mode shift is active for this trigger address. Multiple shifts can release
     * simultaneously (e.g. one trigger that overlays two different sources).
     */
    private fun releaseModeShiftFor(address: InputAddress) {
        if (activeModeShifts.isEmpty()) return
        val it = activeModeShifts.iterator()
        while (it.hasNext()) {
            val shift = it.next()
            if (shift.triggerAddress == address) {
                Log.d(TAG, "mode_shift released: trigger=$address target=${shift.targetSource} group=${shift.targetGroupId}")
                it.remove()
            }
        }
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
        pendingChords.clear()
        moreSpecificFiredFor.clear()
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
        if (activeModeShifts.isNotEmpty()) {
            Log.d(TAG, "flushAllRuntime: clearing ${activeModeShifts.size} active mode-shift(s)")
            activeModeShifts.clear()
        }
        flushAnalog()
    }

    /**
     * Brick 4: release any analog-mode runtime state. Called from [flushAllRuntime]
     * (set-switch path) and from `ShizukuMotionCoordinator` on profile transitions
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
        // Brick J: zero continuous-mode velocities first so the MouseEmitter's
        // integration loop sees an empty slot set on its next step and exits.
        // Done before the synthetic-edge sweep because the edge sweep can
        // re-fire bindings (release path), whereas the velocity flush is a
        // pure clear with no downstream emit.
        mouseEmitter.clearAllVelocities()
        // Brick C: zero every gamepad-emitting source so a deflected stick or
        // pulled trigger doesn't leak into the next set/profile. Clearing one
        // source at a time keeps the cached state correct without resetting
        // sources owned by a still-active mode.
        for (source in GAMEPAD_EMITTING_SOURCES) gamepadEmitter.clearSource(source)
        // DpadMode's gyro path is angle-integrated (tilt-and-hold = held
        // dpad direction); reset its per-source state so accumulated tilt
        // doesn't leak across set/profile boundaries.
        com.mapo.service.input.modes.DpadMode.resetState()
        // GyroToJoystickDeflectionMode is tilt-based and caches a
        // per-source reference orientation. Reset so the next event
        // recalibrates against the user's new natural holding angle.
        com.mapo.service.input.modes.GyroToJoystickDeflectionMode.resetState()
        // TriggerMode caches per-pull threshold-style state (hip-fire defer window,
        // exclusive lock); reset so it can't leak across a profile / action-set switch.
        com.mapo.service.input.modes.TriggerMode.resetState()
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

        // Analog-emulation PWM (digital dpad → analog stick): ~50% duty at ~25 Hz.
        // Tunable; true variable magnitude isn't possible from a single digital full-press.
        private const val DPAD_PULSE_ON_MS = 20L
        private const val DPAD_PULSE_OFF_MS = 20L

        // Hold-to-repeat (turbo) duty cycle. The period (repeat_rate_ms) is split into an
        // ON phase (press held) + OFF phase, each ≥ TURBO_MIN_PHASE_MS, so a polled
        // consumer (the virtual gamepad) reliably samples the press — a 0ms press→release
        // would fall between gamepad polls and register as nothing.
        private const val MIN_TURBO_PERIOD_MS = 40L
        private const val TURBO_MIN_PHASE_MS = 20L

        /**
         * Magnitude floor for treating a hat-axis reading as a held direction
         * (see [dispatchHatAsDigital]). Hat values are ≈ −1 / 0 / +1, so 0.5
         * cleanly separates "pressed" from "centered" regardless of calibration.
         */
        private const val HAT_DIRECTION_THRESHOLD = 0.5f

        /**
         * Linux EV_KEY → (InputSource, sub_input) mapping for the raw-key
         * pipeline that fires when EVIOCGRAB is held on the physical
         * controller. Codes are from `linux/input-event-codes.h`; matches
         * what Android's InputReader would otherwise dispatch as
         * KeyEvents via the accessibility service.
         *
         * Notes:
         *  - BTN_C (0x132) and BTN_Z (0x135) deliberately omitted — Mapo's
         *    button source vocabulary doesn't expose them.
         *  - BTN_MODE (0x13c, Xbox/PS guide) has no Mapo InputSource yet;
         *    listed in [LINUX_KEY_TO_GAMEPAD_BUTTON] for passthrough but
         *    not here so the engine path can't bind to it.
         *  - BTN_TL2 / BTN_TR2 map to LEFT/RIGHT_TRIGGER's `full_pull`
         *    sub-input — the digital trigger click is the same edge the
         *    analog Trigger mode's `full_pull` activator fires on.
         */
        private val LINUX_KEY_TO_ADDRESS: Map<Int, InputAddress> = mapOf(
            0x130 to InputAddress(InputSource.BUTTON_DIAMOND, "button_a"),
            0x131 to InputAddress(InputSource.BUTTON_DIAMOND, "button_b"),
            0x133 to InputAddress(InputSource.BUTTON_DIAMOND, "button_x"),
            0x134 to InputAddress(InputSource.BUTTON_DIAMOND, "button_y"),
            0x136 to InputAddress(InputSource.LEFT_BUMPER, "click"),
            0x137 to InputAddress(InputSource.RIGHT_BUMPER, "click"),
            0x138 to InputAddress(InputSource.LEFT_TRIGGER, "full_pull"),
            0x139 to InputAddress(InputSource.RIGHT_TRIGGER, "full_pull"),
            0x13a to InputAddress(InputSource.SWITCH_SELECT, "click"),
            0x13b to InputAddress(InputSource.SWITCH_START, "click"),
            0x13d to InputAddress(InputSource.LEFT_JOYSTICK, "click"),
            0x13e to InputAddress(InputSource.RIGHT_JOYSTICK, "click"),
            0x220 to InputAddress(InputSource.DPAD, "dpad_up"),
            0x221 to InputAddress(InputSource.DPAD, "dpad_down"),
            0x222 to InputAddress(InputSource.DPAD, "dpad_left"),
            0x223 to InputAddress(InputSource.DPAD, "dpad_right"),
        )

        /**
         * Linux EV_KEY → virtual-gamepad BTN_* code for the DEVICE_DEFAULT
         * passthrough path. Most codes are identity (Linux's BTN_* values
         * are exactly what uinput expects), but the table is explicit so
         * future divergences (or selective subsets) are obvious.
         */
        private val LINUX_KEY_TO_GAMEPAD_BUTTON: Map<Int, Int> = mapOf(
            0x130 to 0x130,  // BTN_A
            0x131 to 0x131,  // BTN_B
            0x133 to 0x133,  // BTN_X
            0x134 to 0x134,  // BTN_Y
            0x136 to 0x136,  // BTN_TL (LB)
            0x137 to 0x137,  // BTN_TR (RB)
            0x138 to 0x138,  // BTN_TL2 (LT digital)
            0x139 to 0x139,  // BTN_TR2 (RT digital)
            0x13a to 0x13a,  // BTN_SELECT
            0x13b to 0x13b,  // BTN_START
            0x13c to 0x13c,  // BTN_MODE (Xbox guide)
            0x13d to 0x13d,  // BTN_THUMBL (L3)
            0x13e to 0x13e,  // BTN_THUMBR (R3)
            // DPAD codes (0x220-0x223) deliberately omitted — dpad goes
            // through the HAT axis, not BTN_*. The analog passthrough path
            // already handles ABS_HAT0X/Y for the dpad.
        )

        /**
         * [InputAddress] → virtual-gamepad BTN_* code for the DEVICE_DEFAULT digital
         * passthrough on the `onKeyEvent` side (see handleDigital). Mirrors
         * [LINUX_KEY_TO_GAMEPAD_BUTTON] but keyed by the resolved address rather than the
         * raw Linux code, since buttons that arrive via the accessibility key filter
         * (un-grabbed node) are already classified to (source, sub-input). Triggers + dpad
         * are intentionally absent — those are analog (hat / ABS_Z/RZ) and pass through via
         * [passthroughAnalogToGamepad], not as buttons.
         */
        private val ADDRESS_TO_GAMEPAD_BUTTON: Map<InputAddress, Int> = mapOf(
            InputAddress(InputSource.BUTTON_DIAMOND, "button_a") to 0x130,
            InputAddress(InputSource.BUTTON_DIAMOND, "button_b") to 0x131,
            InputAddress(InputSource.BUTTON_DIAMOND, "button_x") to 0x133,
            InputAddress(InputSource.BUTTON_DIAMOND, "button_y") to 0x134,
            InputAddress(InputSource.LEFT_BUMPER, "click") to 0x136,
            InputAddress(InputSource.RIGHT_BUMPER, "click") to 0x137,
            InputAddress(InputSource.SWITCH_SELECT, "click") to 0x13a,
            InputAddress(InputSource.SWITCH_START, "click") to 0x13b,
            InputAddress(InputSource.LEFT_JOYSTICK, "click") to 0x13d,
            InputAddress(InputSource.RIGHT_JOYSTICK, "click") to 0x13e,
        )

        /** Sources that can contribute to the virtual XInput gamepad — used by flushAnalog. */
        private val GAMEPAD_EMITTING_SOURCES = listOf(
            InputSource.LEFT_JOYSTICK,
            InputSource.RIGHT_JOYSTICK,
            InputSource.LEFT_TRIGGER,
            InputSource.RIGHT_TRIGGER,
            InputSource.DPAD,
            // GYRO contributes to left or right stick via the gyro→stick
            // modes (Camera / Deflection). Without inclusion here, a
            // deflected stick from a prior gyro session persists in the
            // virtual gamepad after profile/set switch (or any other
            // flushAnalog trigger) until something else writes to that
            // stick, which never happens if the new config has gyro back
            // on DEVICE_DEFAULT / NONE.
            InputSource.GYRO,
        )
    }
}
