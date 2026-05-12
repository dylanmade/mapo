package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime state machine that turns physical input events into [BindingOutput] emissions.
 *
 * Event-driven: every call corresponds to a discrete `onKeyEvent` (Phase 2.2) or
 * normalized analog edge (Phase 2.3); no polling, no per-frame tick. The accessibility
 * service hands events here, the evaluator decides what to fire, [OutputEmitter] does
 * the actual injection.
 *
 * Phase 2.2 implements **FULL_PRESS only**. Non-FULL_PRESS activators are logged
 * and skipped — Phase 3 fleshes them out (Long / Double / Soft / Start / Release / Chord).
 *
 * State held here:
 *  - The latest [CompiledConfig] snapshot (pulled from [InputDispatcher.compiledConfig]
 *    at evaluation time, so config edits propagate without resubscribing).
 *  - [held] — bindings currently in their DOWN phase, keyed by the [InputAddress] that
 *    produced them. Needed to emit the right UPs when the physical key releases,
 *    even if the config changes mid-press.
 *
 * Config-change semantics: when the compiled config changes while a binding is held,
 * the *original* binding's release still fires correctly because we look it up in
 * [held] (snapshot of what we pressed) rather than re-resolving via the current config.
 */
@Singleton
class InputEvaluator @Inject constructor(
    private val dispatcher: InputDispatcher,
    private val emitter: OutputEmitter,
) {

    /**
     * Bindings currently in DOWN state, keyed by the [InputAddress] that pressed them.
     * Value is the list of bindings the emitter accepted (i.e. ones with a matching
     * release edge) — mouse/wheel/stub bindings aren't tracked here since they have no UP.
     */
    private val held = HashMap<InputAddress, List<BindingOutput>>()

    /**
     * Handle a digital press/release at [address]. Returns true if the event was consumed
     * (i.e. at least one binding fired), false if there's no binding configured and the
     * physical event should pass through to the foreground app.
     */
    fun handleDigital(address: InputAddress, isDown: Boolean): Boolean {
        return if (isDown) onPress(address) else onRelease(address)
    }

    private fun onPress(address: InputAddress): Boolean {
        // Already held? Shouldn't happen (Android coalesces repeats unless the user keeps
        // hammering) but guard anyway — release the prior bindings before re-pressing so
        // we don't leak DOWN state.
        held.remove(address)?.also { previouslyHeld ->
            Log.d(TAG, "onPress: address $address was already held — releasing stale bindings first")
            previouslyHeld.forEach(emitter::emitRelease)
        }

        val compiledInput = dispatcher.compiledConfig.value.lookup(address.source, address.inputKey)
        if (compiledInput == null) {
            Log.d(TAG, "onPress: no compiled input for $address — passing through")
            return false
        }

        val emitted = mutableListOf<BindingOutput>()
        for (activator in compiledInput.activators) {
            if (activator.type != ActivatorType.FULL_PRESS) {
                Log.d(TAG, "onPress: skipping ${activator.type} activator (Phase 3 territory)")
                continue
            }
            for (binding in activator.bindings) {
                if (emitter.emitPress(binding)) emitted += binding
            }
        }

        if (emitted.isEmpty()) {
            // The address has a compiled input but no FULL_PRESS activators produced
            // releasable output — the event was still consumed (we don't want a stub
            // binding to also pass through to the game), so return true.
            Log.d(TAG, "onPress: $address matched but produced no held bindings")
            return compiledInput.activators.isNotEmpty()
        }
        held[address] = emitted
        Log.d(TAG, "onPress: $address pressed ${emitted.size} binding(s): $emitted")
        return true
    }

    private fun onRelease(address: InputAddress): Boolean {
        val previouslyHeld = held.remove(address)
        if (previouslyHeld == null) {
            Log.d(TAG, "onRelease: $address not in held set — passing through")
            return false
        }
        previouslyHeld.forEach(emitter::emitRelease)
        Log.d(TAG, "onRelease: $address released ${previouslyHeld.size} binding(s)")
        return true
    }

    /** Test seam: how many addresses are currently in the held set. */
    internal fun heldAddressCount(): Int = held.size

    companion object {
        private const val TAG = "InputEvaluator"
    }
}
