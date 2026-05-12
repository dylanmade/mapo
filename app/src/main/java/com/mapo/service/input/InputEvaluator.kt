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
 *  - **Brick 3.2 / 3.3** — `DOUBLE_PRESS`, `CHORDED_PRESS`, and universal settings.
 *
 * State held here:
 *  - [held] — per-activator press records keyed by [InputAddress]. Each record carries
 *    its activatorId so a UP edge releases the right activator's bindings even if the
 *    config swapped under us mid-press.
 *  - [pending] — coroutine [Job]s scheduled by activators that need a timer (currently
 *    only `LONG_PRESS`). Keyed by `(InputAddress → activatorId)`. Cancelled on UP or on
 *    a duplicate DOWN for the same address.
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

    private val held = HashMap<InputAddress, MutableList<HeldEntry>>()
    private val pending = HashMap<InputAddress, MutableMap<Long, Job>>()

    /**
     * Handle a digital press/release at [address]. Returns true if the event was consumed
     * (i.e. at least one configured activator acted on it), false if there's no binding
     * configured for the address and the physical event should pass through.
     */
    fun handleDigital(address: InputAddress, isDown: Boolean): Boolean {
        return if (isDown) onPress(address) else onRelease(address)
    }

    private fun onPress(address: InputAddress): Boolean {
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

        var consumed = false
        for (activator in compiledInput.activators) {
            when (activator.type) {
                ActivatorType.FULL_PRESS -> {
                    if (firePressBindings(address, activator)) consumed = true
                    else if (activator.bindings.isNotEmpty()) consumed = true
                }
                ActivatorType.LONG_PRESS -> {
                    scheduleLongPress(address, activator)
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
            // because a RELEASE_PRESS fired. Return true if anything in the active config
            // for this address would have consumed the press.
            return compiledInput?.activators?.isNotEmpty() == true
        }
        records.forEach { entry -> entry.bindings.forEach(emitter::emitRelease) }
        Log.d(TAG, "onRelease: $address released ${records.sumOf { it.bindings.size }} binding(s)")
        return true
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
     * Release everything currently associated with [address] — bindings, timers, the lot.
     * Used by the duplicate-DOWN guard so we never leak held key codes when an unexpected
     * second DOWN arrives.
     */
    private fun forceReleaseAddress(address: InputAddress) {
        cancelPendingTimers(address)
        held.remove(address)?.forEach { entry ->
            entry.bindings.forEach(emitter::emitRelease)
        }
    }

    /** Test seam: how many addresses are currently in the held set. */
    internal fun heldAddressCount(): Int = held.size

    /** Test seam: how many addresses currently have pending timers. */
    internal fun pendingAddressCount(): Int = pending.size

    companion object {
        private const val TAG = "InputEvaluator"
    }
}
