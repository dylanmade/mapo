package com.mappo.ui.screen.keyboard

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.mappo.data.model.GridButton
import com.mappo.data.model.GridLayout
import com.mappo.data.model.TrackpadGesture
import com.mappo.ui.theme.MappoTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick 3 smoke check for [KeyboardHost] — confirms the same composable renders
 * cleanly in [KeyboardHostMode.Activity]. The
 * goal isn't exhaustive verification of every edit-mode interaction (those live
 * with the underlying composables in `MainScreen.kt`); it's "does the host wire
 * up against both modes without crashing or losing the leaf affordances."
 *
 * Robolectric: Mappo's `app/src/test/` test pattern — on-device runs background
 * test-launched activities on the AYN Thor before Compose can settle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardHostTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun activityMode_rendersWithoutCrashing_andBottomBarSaysQuit() {
        val state = FakeKeyboardHostState(listOf(sampleLayout("Tab-A")))

        composeRule.setContent {
            MappoTheme {
                KeyboardHost(
                    state = state,
                    mode = activityMode(),
                    modifier = Modifier.size(width = 480.dp, height = 360.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Quit").assertCountEquals(1)
    }

    @Test
    fun selectLayout_updatesSelectedIndex_throughHostStateInterface() {
        val state = FakeKeyboardHostState(
            listOf(sampleLayout("Tab-A"), sampleLayout("Tab-B")),
        )
        // Sanity check that the interface contract is followed.
        state.selectLayout(1)
        org.junit.Assert.assertEquals(1, state.selectedIndex.value)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sampleLayout(name: String): GridLayout =
        GridLayout(id = name.hashCode().toLong(), name = name, columns = 4, rows = 3, buttons = emptyList())

    private fun activityMode() = KeyboardHostMode.Activity(
        isEditMode = MutableStateFlow(false),
        selectedButtonId = MutableStateFlow<String?>(null),
        tabContextMenuFor = MutableStateFlow<Long?>(null),
        onOpenTabMenu = {},
        onCloseTabMenu = {},
        onReorderTabs = { _, _ -> },
        onMenuEditButtons = {},
        onToggleEditMode = {},
        onMenuConfigure = {},
        onMenuDuplicate = {},
        onMenuRemove = {},
        onMenuSaveTemplate = {},
        onOpenDrawer = {},
        onAddKeyboard = {},
        onSelectButton = {},
        onMoveButton = { _, _, _ -> },
        onResizeButton = { _, _, _, _, _ -> },
        onConfigureButton = {},
        onDuplicateButton = {},
        onRemoveButton = {},
        onAddAtCell = { _, _ -> },
        onLongPressEmptyArea = {},
        onQuit = {},
    )

    private class FakeKeyboardHostState(
        initialLayouts: List<GridLayout>,
    ) : KeyboardHostState {
        private val _layouts: MutableStateFlow<ImmutableList<GridLayout>> =
            MutableStateFlow(initialLayouts.toPersistentList())
        private val _selectedIndex = MutableStateFlow(0)
        private val _remapEnabled = MutableStateFlow(false)
        private val _displayLayout = MutableStateFlow(
            initialLayouts.firstOrNull()
                ?: GridLayout(name = "", columns = 1, rows = 1, buttons = emptyList())
        )

        override val layouts: StateFlow<ImmutableList<GridLayout>> = _layouts
        override val selectedIndex: StateFlow<Int> = _selectedIndex
        override val displayLayout: StateFlow<GridLayout> = _displayLayout
        override val remapEnabled: StateFlow<Boolean> = _remapEnabled

        override fun selectLayout(index: Int) { _selectedIndex.value = index }
        override fun toggleRemap() { _remapEnabled.value = !_remapEnabled.value }
        override fun onButtonTap(button: GridButton) {}
        override fun onButtonDoubleTap(button: GridButton) {}
        override fun onButtonHold(button: GridButton) {}
        override fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture) {}
        override fun onDragStart() {}
        override fun onMouseMove(dx: Float, dy: Float) {}
        override fun onDragEnd() {}
    }
}
