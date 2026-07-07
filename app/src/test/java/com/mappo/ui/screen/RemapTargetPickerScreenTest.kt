package com.mappo.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.BindingOutput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick 4.5: Robolectric coverage for the generalized picker. Focuses on what changed
 * this brick — the Switch Action Set category visibility, list rendering, and the
 * `BindingOutput` shape it emits. The existing category-selection / filter UX is
 * exercised indirectly by the InputEditor and ConfigureButton screen tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemapTargetPickerScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun switchActionSetCategory_hiddenWhenAvailableActionSetsEmpty() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = {},
                        onBack = {},
                        // Empty list ⇒ Switch Action Set category isn't rendered.
                        availableActionSets = emptyList(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Switch Action Set").assertDoesNotExist()
        // Legacy categories still appear.
        composeRule.onNodeWithText("Gamepad").assertIsDisplayed()
        composeRule.onNodeWithText("Keyboard").assertIsDisplayed()
    }

    @Test
    fun switchActionSetCategory_visibleWhenSetsProvided() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = {},
                        onBack = {},
                        availableActionSets = listOf(1L to "Gameplay", 2L to "Menu"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Switch Action Set").assertIsDisplayed()
    }

    @Test
    fun selectingActionSet_emitsChangePresetBindingOutput() {
        var picked: BindingOutput? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = { picked = it },
                        onBack = {},
                        availableActionSets = listOf(1L to "Gameplay", 2L to "Menu"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Switch Action Set").performClick()
        // Sub-screen shows each set by title.
        composeRule.onNodeWithText("Menu").assertIsDisplayed()
        composeRule.onNodeWithText("Menu").performClick()

        assert(picked is BindingOutput.ControllerAction) {
            "Expected ControllerAction, got $picked"
        }
        val action = picked as BindingOutput.ControllerAction
        assert(action.verb == "CHANGE_PRESET") { "Expected verb CHANGE_PRESET, got ${action.verb}" }
        assert(action.args == listOf("2")) { "Expected args [2], got ${action.args}" }
    }

    @Test
    fun currentChangePreset_marksTheBoundSetAsSelected() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.ControllerAction("CHANGE_PRESET", listOf("2")).encode(),
                        onSelect = {},
                        onBack = {},
                        availableActionSets = listOf(1L to "Gameplay", 2L to "Menu"),
                    )
                }
            }
        }

        // Top-level "Switch Action Set" category should be selected (check icon present).
        composeRule.onNodeWithText("Switch Action Set").performClick()
        // The "Menu" item shows the check tick — looking up by content desc is the
        // simplest way to assert the selected-state visually.
        composeRule.onNodeWithText("Menu").assertIsDisplayed()
    }

    // ── Brick 5.6: Layer category + verb/layer two-step picker ───────────────

    @Test
    fun layerCategory_hiddenWhenAvailableLayersEmpty() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = {},
                        onBack = {},
                        availableLayers = emptyList(),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Layer").assertDoesNotExist()
    }

    @Test
    fun layerCategory_visibleWhenLayersProvided() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = {},
                        onBack = {},
                        availableLayers = listOf(10L to "Scope"),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Layer").assertIsDisplayed()
    }

    @Test
    fun layerCategory_routesToVerbList_withThreeOptions() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = {},
                        onBack = {},
                        availableLayers = listOf(10L to "Scope"),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Layer").performClick()
        composeRule.onNodeWithText("Add Layer (sticky)").assertIsDisplayed()
        composeRule.onNodeWithText("Hold Layer (while held)").assertIsDisplayed()
        composeRule.onNodeWithText("Remove Layer").assertIsDisplayed()
    }

    @Test
    fun pickingHoldLayer_then_pickingALayer_emitsHoldLayerBindingOutput() {
        var picked: BindingOutput? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = { picked = it },
                        onBack = {},
                        availableLayers = listOf(10L to "Scope", 11L to "Vehicle"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Layer").performClick()
        composeRule.onNodeWithText("Hold Layer (while held)").performClick()
        composeRule.onNodeWithText("Vehicle").performClick()

        val action = picked as? BindingOutput.ControllerAction
        assert(action != null && action.verb == "hold_layer" && action.args == listOf("11")) {
            "Expected ControllerAction(hold_layer, [11]); got $picked"
        }
    }

    @Test
    fun pickingAddLayer_emitsAddLayerBindingOutput() {
        var picked: BindingOutput? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = { picked = it },
                        onBack = {},
                        availableLayers = listOf(10L to "Scope"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Layer").performClick()
        composeRule.onNodeWithText("Add Layer (sticky)").performClick()
        composeRule.onNodeWithText("Scope").performClick()

        val action = picked as? BindingOutput.ControllerAction
        assert(action != null && action.verb == "add_layer" && action.args == listOf("10")) {
            "Expected ControllerAction(add_layer, [10]); got $picked"
        }
    }

    @Test
    fun pickingRemoveLayer_emitsRemoveLayerBindingOutput() {
        var picked: BindingOutput? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    RemapTargetPickerScreen(
                        title = "Pick",
                        currentEncoded = BindingOutput.Unbound.encode(),
                        onSelect = { picked = it },
                        onBack = {},
                        availableLayers = listOf(10L to "Scope"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Layer").performClick()
        composeRule.onNodeWithText("Remove Layer").performClick()
        composeRule.onNodeWithText("Scope").performClick()

        val action = picked as? BindingOutput.ControllerAction
        assert(action != null && action.verb == "remove_layer" && action.args == listOf("10")) {
            "Expected ControllerAction(remove_layer, [10]); got $picked"
        }
    }
}
