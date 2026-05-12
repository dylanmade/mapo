package com.mapo.service.input

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource

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
 * One activator pre-resolved with its typed [BindingOutput]s.
 *
 * Brick 2.1 preserves the activator's [type] verbatim; the state machine in brick 2.2
 * decides what to do with each type (initially: only [ActivatorType.FULL_PRESS] fires,
 * everything else is logged-and-dropped until Phase 3).
 */
data class CompiledActivator(
    val activatorId: Long,
    val type: ActivatorType,
    val bindings: List<BindingOutput>,
)

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
                )
            }
            inputs[address] = CompiledInput(inputGraph.input.id, compiledActivators)
        }
    }
    return CompiledConfig(activeSet.actionSet.id, inputs)
}
