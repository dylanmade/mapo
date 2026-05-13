package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *  - **Brick 3.1** — `LONG_PRESS` (schedule on DOWN, fire on threshold while still held);
 *    `START_PRESS` (tap on DOWN edge); `RELEASE_PRESS` (tap on UP edge).
 *  - **Brick 3.2** — `DOUBLE_PRESS` window state machine. Coexists with `FULL_PRESS` per
 *    Steam's default semantics: when both exist on one input, FULL_PRESS is deferred at
 *    the first DOWN; it fires on window expiration if no second tap arrives, or gets
 *    suppressed entirely if a second tap arrives in time. Hardcoded `interruptable=true`
 *    for now; 3.3 makes it configurable.
 *  - **Brick 3.3** — `CHORDED_PRESS` and universal settings.
 *
 * State held here:
 *  - [held] — per-activator press records keyed by [InputAddress]. Each record carries
 *    its activatorId so a UP edge releases the right activator's bindings even if the
 *    config swapped under us mid-press.
 *  - [pending] — coroutine [Job]s scheduled by activators that need a timer (e.g.
 *    `LONG_PRESS`). Keyed by `(InputAddress → activatorId)`. Cancelled on UP or on a
 *    duplicate DOWN for the same address.
 *  - [doubleTapWindows] — per-address state for in-flight DOUBLE_PRESS detection. Holds
 *    the window-expiration timer, the deferred FULL_PRESS activator (if any), and
 *    whether the first-tap button is still physically held.
 *
 * Config-change semantics: if the compiled config changes while a binding is held, the
 * original binding's release still fires because [held] is a snapshot of what was
 * emitted at press time — we do *not* re-resolve on release.
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
     * and a LONG_PRESS firing on the same A button).
     */
    private data class HeldEntry(val activatorId: Long, val bindings: List<BindingOutput>)

    /**
     * In-flight DOUBLE_PRESS detection state. Created on a first DOWN when DOUBLE_PRESS is
     * configured. Replaced (window job cancelled) on second DOWN, or expires on timeout.
     *
     * [deferredRegular] is the FULL_PRESS activator whose firing was held back because
     * DOUBLE_PRESS is configured on the same input (Steam's `interruptable=true` default).
     * Null when no FULL_PRESS exists or when 3.3 marks it non-interruptable.
     *
     * [physicallyHeld] tracks whether the physical button is still down. Updated by
     * [onRelease] so the window-expiration handler can decide whether to fire the deferred
     * Regular as a tap (button already up) or as a held press (still held — late binding).
     */
    private data class DoubleTapWindow(
        val doubleActivator: CompiledActivator,
        val deferredRegular: CompiledActivator?,
        var physicallyHeld: Boolean,
        var timerJob: Job? = null,
    )

    private val held = HashMap<InputAddress, MutableList<HeldEntry>>()
    private val pending = HashMap<InputAddress, MutableMap<Long, Job>>()
    private val doubleTapWindows = HashMap<InputAddress, DoubleTapWindow>()

    /**
     * Handle a digital press/release at [address]. Returns true if the event was consumed
     * (i.e. at least one configured activator acted on it), false if there's no binding
     * configured for the address and the physical event should pass through.
     */
    fun handleDigital(address: InputAddress, isDown: Boolean): Boolean {
        return if (isDown) onPress(address) else onRelease(address)
    }

    private fun onPress(address: InputAddress): Boolean {
        // Second-tap path: an active DOUBLE_PRESS window on this address means this DOWN
        // is the second tap. Cancel the window, fire DOUBLE_PRESS (the deferred Regular is
        // dropped — that's the suppression behavior).
        val activeWindow = doubleTapWindows.remove(address)
        if (activeWindow != null) {
            activeWindow.timerJob?.cancel()
            Log.d(TAG, "double-tap detected at $address — firing DOUBLE_PRESS, suppressing deferred Regular")
            firePressBindings(address, activeWindow.doubleActivator)
            return true
        }

        // Defensive: a duplicate DOWN without an intervening UP can happen on flaky
        // controllers. Treat it as a release+re-press so we don't leak held bindings or
        // orphan scheduled timers.
        if (held.containsKey(address) || pending.containsKey(address)) {
            Log.d(TAG, "onPress: address $address already in flight — releasing stale state first")
            forceReleaseAddress(address)
        }

        val compiledInput = dispatcher.compiledConfig.value.lookup(address.source, address.inputKey)
        if (compiledInput == null) {
            Log.d(TAG, "onPress: no compiled input for $address — passing through")
            return false
        }

        val doubleActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.DOUBLE_PRESS }
        val regularActivator = compiledInput.activators.firstOrNull { it.type == ActivatorType.FULL_PRESS }
        // 3.2 assumption: Regular is interruptable (Steam default) — defer when Double exists.
        // 3.3 will read this off the activator's universal settings.
        val deferRegular = doubleActivator != null && regularActivator != null

        var consumed = false
        for (activator in compiledInput.activators) {
            when (activator.type) {
                ActivatorType.FULL_PRESS -> {
                    if (deferRegular) {
                        // Don't fire yet. The DOUBLE_PRESS window's expiration handler will
                        // fire Regular if no second tap arrives, or drop it if one does.
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
                else -> {
                    Log.d(TAG, "onPress: skipping ${activator.type} activator (later brick)")
                    if (compiledInput.activators.isNotEmpty()) consumed = true
                }
            }
        }
        return consumed
    }

    private fun onRelease(address: InputAddress): Boolean {
        cancelPendingTimers(address)

        // If a DOUBLE_PRESS window is still active for this address, this UP belongs to the
        // first tap of a possible double-tap. Note the physical state — the window timer
        // uses it later to decide whether the deferred Regular fires as a tap or as held.
        doubleTapWindows[address]?.physicallyHeld = false

        // Fire any RELEASE_PRESS activators wired to this address. Only meaningful if the
        // address is currently configured AND we acknowledged the prior DOWN — without an
        // ack the address isn't ours to release.
        val compiledInput = dispatcher.compiledConfig.value.lookup(address.source, address.inputKey)
        compiledInput?.activators?.forEach { activator ->
            if (activator.type == ActivatorType.RELEASE_PRESS) {
                emitTap(activator)
            }
        }

        val records = held.remove(address)
        if (records == null) {
            // No holdable bindings to release; but we may still have consumed the event
            // because a RELEASE_PRESS fired, or a DOUBLE_PRESS window is still in flight.
            return compiledInput?.activators?.isNotEmpty() == true
        }
        records.forEach { entry -> entry.bindings.forEach(emitter::emitRelease) }
        Log.d(TAG, "onRelease: $address released ${records.sumOf { it.bindings.size }} binding(s)")
        return true
    }

    /**
     * Open a [ActivatorType.DOUBLE_PRESS] detection window on a first DOWN. While the
     * window is active, a second DOWN on the same address before timeout fires DOUBLE_PRESS
     * and (if [deferredRegular] is non-null) suppresses the Regular Press; otherwise the
     * window times out and the deferred Regular fires belatedly (as a tap or held DOWN
     * depending on whether the physical button is still down at expiration).
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
            // Window expired without a second tap arriving (second-tap path removes the
            // window before we ever resume here).
            val expired = doubleTapWindows.remove(address) ?: return@launch
            Log.d(TAG, "double-tap window expired at $address — no second tap")
            val regular = expired.deferredRegular ?: return@launch
            if (expired.physicallyHeld) {
                // Still being held by the user. Fire Regular as a held DOWN; its UP will
                // come through the normal onRelease path.
                firePressBindings(address, regular)
            } else {
                // User already let go. Fire as a tap — DOWN+UP back-to-back.
                emitTap(regular)
            }
        }
        Log.d(TAG, "opened double-tap window ($timerMs ms) at $address" +
            (if (deferredRegular != null) " with deferred Regular" else ""))
    }

    /**
     * Schedule a [ActivatorType.LONG_PRESS] timer. If the timer fires while the address
     * is still down (i.e. nothing has cleared it from [pending]), the bindings emit and
     * land in [held] for release on UP.
     */
    private fun scheduleLongPress(address: InputAddress, activator: CompiledActivator) {
        val delayMs = activator.settings.longPressTimeMs
        val job = scope.launch {
            delay(delayMs)
            // Once the delay elapses we're back here. The cancellation pathway covers UP
            // and duplicate-DOWN, so reaching this point means the button is still held.
            pending[address]?.remove(activator.activatorId)
            if (pending[address]?.isEmpty() == true) pending.remove(address)
            firePressBindings(address, activator)
            Log.d(TAG, "long-press fired for $address activator=${activator.activatorId}")
        }
        val bucket = pending.getOrPut(address) { HashMap() }
        bucket[activator.activatorId] = job
        Log.d(TAG, "scheduled long-press at $delayMs ms for $address activator=${activator.activatorId}")
    }

    /** Emit press for every binding on [activator]; record holdable ones in [held]. */
    private fun firePressBindings(address: InputAddress, activator: CompiledActivator): Boolean {
        val holdable = mutableListOf<BindingOutput>()
        for (binding in activator.bindings) {
            if (emitter.emitPress(binding)) holdable += binding
        }
        if (holdable.isNotEmpty()) {
            held.getOrPut(address) { mutableListOf() }.add(HeldEntry(activator.activatorId, holdable))
        }
        return holdable.isNotEmpty()
    }

    /**
     * Edge-triggered single tap: emit press + release back-to-back. Used by
     * [ActivatorType.START_PRESS] (on DOWN) and [ActivatorType.RELEASE_PRESS] (on UP).
     * Holdable bindings still get their UP edge so key codes don't stick.
     */
    private fun emitTap(activator: CompiledActivator) {
        for (binding in activator.bindings) {
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
     * second DOWN arrives.
     */
    private fun forceReleaseAddress(address: InputAddress) {
        cancelPendingTimers(address)
        doubleTapWindows.remove(address)?.timerJob?.cancel()
        held.remove(address)?.forEach { entry ->
            entry.bindings.forEach(emitter::emitRelease)
        }
    }

    /** Test seam: how many addresses are currently in the held set. */
    internal fun heldAddressCount(): Int = held.size

    /** Test seam: how many addresses currently have pending timers. */
    internal fun pendingAddressCount(): Int = pending.size

    /** Test seam: how many addresses have an in-flight DOUBLE_PRESS detection window. */
    internal fun doubleTapWindowCount(): Int = doubleTapWindows.size

    companion object {
        private const val TAG = "InputEvaluator"
    }
}
