package com.mapo.service.keyboard

import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.TrackpadGesture
import com.mapo.ui.screen.keyboard.KeyboardHostState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Adapter that lets the system-overlay window mount [com.mapo.ui.screen.keyboard.KeyboardHost]
 * directly off a [KeyboardController] — no `MainViewModel` involvement. The overlay
 * runs out-of-activity (in a `WindowManager`-attached `ComposeView`) so it can't reach
 * the activity-scoped VM; this adapter exposes the singleton controller through the
 * same [KeyboardHostState] interface the host expects.
 *
 * The non-null `displayLayout` fallback to [DefaultLayouts.all]`[0]` mirrors what
 * `MainViewModel.displayLayout` does at the same boundary — keeps the activity- and
 * overlay-side hosts behaviorally identical. The controller's actual surface
 * (`displayLayout: StateFlow<GridLayout?>`) remains the FC1 seam; the adapter's job
 * is just to bridge the non-null host contract.
 */
fun KeyboardController.asKeyboardHostState(): KeyboardHostState =
    KeyboardControllerHostState(this)

private class KeyboardControllerHostState(
    private val controller: KeyboardController,
) : KeyboardHostState {

    // Independent scope — the adapter's `displayLayout` projection has its own
    // collector; using GlobalScope-style here is acceptable because the controller
    // outlives every overlay attach and detach cycle.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val layouts: StateFlow<ImmutableList<GridLayout>> = controller.layouts
    override val selectedIndex: StateFlow<Int> = controller.selectedIndex
    override val remapEnabled: StateFlow<Boolean> = controller.remapEnabled

    override val displayLayout: StateFlow<GridLayout> = controller.displayLayout
        .map { it ?: DefaultLayouts.all[0] }
        .stateIn(scope, SharingStarted.Eagerly, DefaultLayouts.all[0])

    override fun selectLayout(index: Int) = controller.setSelectedIndex(index)
    override fun toggleRemap() = controller.toggleRemap()
    override fun onButtonTap(button: GridButton) = controller.onButtonTap(button)
    override fun onButtonDoubleTap(button: GridButton) = controller.onButtonDoubleTap(button)
    override fun onButtonHold(button: GridButton) = controller.onButtonHold(button)
    override fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture) =
        controller.onTrackpadGesture(button, gesture)
    override fun onDragStart() = controller.onDragStart()
    override fun onMouseMove(dx: Float, dy: Float) = controller.onMouseMove(dx, dy)
    override fun onDragEnd() = controller.onDragEnd()
}
