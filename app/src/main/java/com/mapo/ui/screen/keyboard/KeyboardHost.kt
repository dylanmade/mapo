package com.mapo.ui.screen.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapo.service.overlay.keyboard.overlayTouchable
import com.mapo.ui.screen.BottomBar
import com.mapo.ui.screen.KeyGrid
import com.mapo.ui.screen.KeyboardSurface
import com.mapo.ui.screen.KeyboardTopBar

/**
 * Host-agnostic mount point for the virtual keyboard. Brick 3 of the single-screen
 * refactor: the same composable renders inside `MainActivity` (Activity mode, full
 * edit features) and inside the system-overlay `WindowManager` window (Overlay
 * mode, run-only).
 *
 * Pulls run-mode state out of [state] (typically a [com.mapo.service.keyboard.KeyboardController]
 * adapter or `MainViewModel` directly) and routes mode-specific affordances through
 * [mode]. The internal sub-composables (`KeyboardTopBar`, `KeyboardSurface`,
 * `KeyGrid`, `BottomBar`) remain in `MainScreen.kt` with `internal` visibility —
 * physically extracting them was a 1000+ line shuffle without architectural payoff;
 * the seam this host establishes is the contract surface, not the file boundary.
 *
 * **Activity vs Overlay differences (handled internally by branching on [mode]):**
 *  - Top bar: Activity gets the full menu/add/edit-toggle row; Overlay gets a slim
 *    "Open Mapo" affordance (TODO: visual polish lands in Brick 4).
 *  - Grid: Activity passes through edit-mode state + CRUD callbacks; Overlay forces
 *    `isEditMode = false` and supplies no-op edit callbacks (never invoked at runtime).
 *  - Bottom bar: Activity left button = "Quit"; Overlay left button = "Hide".
 */
@Composable
fun KeyboardHost(
    state: KeyboardHostState,
    mode: KeyboardHostMode,
    modifier: Modifier = Modifier,
) {
    val layouts by state.layouts.collectAsStateWithLifecycle()
    val selectedIndex by state.selectedIndex.collectAsStateWithLifecycle()
    val displayLayout by state.displayLayout.collectAsStateWithLifecycle()
    val remapEnabled by state.remapEnabled.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(vertical = 4.dp)) {
        when (mode) {
            is KeyboardHostMode.Activity -> {
                val isEditMode by mode.isEditMode.collectAsStateWithLifecycle()
                val selectedButtonId by mode.selectedButtonId.collectAsStateWithLifecycle()
                val tabContextMenuFor by mode.tabContextMenuFor.collectAsStateWithLifecycle()

                KeyboardTopBar(
                    layouts = layouts,
                    selectedIndex = selectedIndex,
                    isEditMode = isEditMode,
                    tabContextMenuFor = tabContextMenuFor,
                    onSelectIndex = state::selectLayout,
                    onLongPressMenu = mode.onOpenTabMenu,
                    onReorder = mode.onReorderTabs,
                    onCloseMenu = mode.onCloseTabMenu,
                    onMenuEditButtons = mode.onMenuEditButtons,
                    onToggleEditMode = mode.onToggleEditMode,
                    onMenuConfigure = mode.onMenuConfigure,
                    onMenuDuplicate = mode.onMenuDuplicate,
                    onMenuRemove = mode.onMenuRemove,
                    onMenuSaveTemplate = mode.onMenuSaveTemplate,
                    onOpenDrawer = mode.onOpenDrawer,
                    onAddKeyboard = mode.onAddKeyboard,
                )

                KeyboardSurface(
                    layout = displayLayout,
                    themeFallback = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
                ) {
                    KeyGrid(
                        layout = displayLayout,
                        isEditMode = isEditMode,
                        selectedButtonId = selectedButtonId,
                        onButtonTap = state::onButtonTap,
                        onButtonDoubleTap = state::onButtonDoubleTap,
                        onButtonHold = state::onButtonHold,
                        onSelectButton = mode.onSelectButton,
                        onMoveButton = mode.onMoveButton,
                        onResizeButton = mode.onResizeButton,
                        onDragStart = state::onDragStart,
                        onMouseMove = state::onMouseMove,
                        onDragEnd = state::onDragEnd,
                        onTrackpadGesture = state::onTrackpadGesture,
                        onConfigureButton = mode.onConfigureButton,
                        onDuplicateButton = mode.onDuplicateButton,
                        onRemoveButton = mode.onRemoveButton,
                        onAddAtCell = mode.onAddAtCell,
                        onLongPressEmptyArea = mode.onLongPressEmptyArea,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                BottomBar(
                    remapEnabled = remapEnabled,
                    onToggleRemap = state::toggleRemap,
                    onLeftAction = mode.onQuit,
                    leftActionLabel = "Quit",
                )
            }

            is KeyboardHostMode.Overlay -> {
                // Overlay top bar: slim — tab selector + "Open Mapo" affordance.
                // For Brick 3 minimum-viable wiring, reuse `KeyboardTopBar` with all
                // edit-related callbacks as no-ops and `isEditMode = false`. Visual
                // polish (slim variant, hide unused buttons) is a Brick 4 refinement.
                // Top/bottom bars are always-touchable strips in overlay mode — gaps
                // between tabs / between bar controls should still feel "live", not
                // passthrough.
                Box(modifier = Modifier.overlayTouchable()) {
                    KeyboardTopBar(
                        layouts = layouts,
                        selectedIndex = selectedIndex,
                        isEditMode = false,
                        tabContextMenuFor = null,
                        onSelectIndex = state::selectLayout,
                        onLongPressMenu = {},
                        onReorder = { _, _ -> },
                        onCloseMenu = {},
                        onMenuEditButtons = {},
                        onToggleEditMode = {},
                        onMenuConfigure = {},
                        onMenuDuplicate = {},
                        onMenuRemove = {},
                        onMenuSaveTemplate = {},
                        onOpenDrawer = mode.onOpenMapoActivity,
                        onAddKeyboard = {},
                    )
                }

                KeyboardSurface(
                    layout = displayLayout,
                    themeFallback = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
                ) {
                    KeyGrid(
                        layout = displayLayout,
                        isEditMode = false,
                        selectedButtonId = null,
                        onButtonTap = state::onButtonTap,
                        onButtonDoubleTap = state::onButtonDoubleTap,
                        onButtonHold = state::onButtonHold,
                        onSelectButton = {},
                        onMoveButton = { _, _, _ -> },
                        onResizeButton = { _, _, _, _, _ -> },
                        onDragStart = state::onDragStart,
                        onMouseMove = state::onMouseMove,
                        onDragEnd = state::onDragEnd,
                        onTrackpadGesture = state::onTrackpadGesture,
                        onConfigureButton = {},
                        onDuplicateButton = {},
                        onRemoveButton = {},
                        onAddAtCell = { _, _ -> },
                        onLongPressEmptyArea = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(modifier = Modifier.overlayTouchable()) {
                    BottomBar(
                        remapEnabled = remapEnabled,
                        onToggleRemap = state::toggleRemap,
                        onLeftAction = mode.onHideOverlay,
                        leftActionLabel = "Hide",
                    )
                }
            }
        }
    }
}
