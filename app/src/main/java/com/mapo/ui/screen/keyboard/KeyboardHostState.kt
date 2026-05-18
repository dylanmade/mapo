package com.mapo.ui.screen.keyboard

import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.TrackpadGesture
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

/**
 * Run-mode contract every consumer of [KeyboardHost] supplies. Thin wrapper around
 * the data flowing out of [com.mapo.service.keyboard.KeyboardController], plus the
 * run-mode dispatch methods.
 *
 * Activity-mode edit affordances live separately in [KeyboardHostMode.Activity] so
 * the overlay can omit them entirely — keeping this interface narrow lets the
 * overlay's `KeyboardHostState` implementation be a tiny adapter over the bare
 * controller (no MainViewModel involvement).
 */
interface KeyboardHostState {
    val layouts: StateFlow<ImmutableList<GridLayout>>
    val selectedIndex: StateFlow<Int>
    val displayLayout: StateFlow<GridLayout>
    val remapEnabled: StateFlow<Boolean>

    fun selectLayout(index: Int)
    fun toggleRemap()

    fun onButtonTap(button: GridButton)
    fun onButtonDoubleTap(button: GridButton)
    fun onButtonHold(button: GridButton)
    fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture)

    fun onDragStart()
    fun onMouseMove(dx: Float, dy: Float)
    fun onDragEnd()
}
