package com.mapo.service.overlay.element

import android.util.Log
import com.mapo.data.model.RemapTarget
import com.mapo.service.input.InputDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared "emit this [RemapTarget]" path for the rebuilt overlay's buttons.
 *
 * Mirrors `KeyboardController.dispatchButtonTarget` but is decoupled from the keyboard
 * controller so the new overlay never depends on the legacy keyboard runtime. When
 * overlay buttons migrate onto the physical-remap `Binding`/`Activator` pipeline
 * (FC1), this is the single place that changes.
 */
@Singleton
class OverlayTargetDispatcher @Inject constructor(
    private val inputDispatcher: InputDispatcher,
) {

    val isReady: Boolean get() = inputDispatcher.isReady

    /**
     * Emit [target]. Returns false when the accessibility sink isn't connected (the
     * caller surfaces "Accessibility service not running"); an [RemapTarget.Unbound]
     * target is a successful no-op.
     */
    fun dispatch(target: RemapTarget): Boolean {
        // Always log input — see feedback_input_logging.
        Log.d(TAG, "dispatch target=$target ready=${inputDispatcher.isReady}")
        if (target is RemapTarget.Unbound) return true
        if (!inputDispatcher.isReady) return false
        when (target) {
            is RemapTarget.Unbound -> Unit // returned above; here for exhaustiveness
            is RemapTarget.Keyboard -> inputDispatcher.injectKey(target.code)
            is RemapTarget.Mouse, is RemapTarget.Gamepad ->
                inputDispatcher.dispatchTargetAsClick(target)
        }
        return true
    }

    companion object {
        private const val TAG = "MapoInput"
    }
}
