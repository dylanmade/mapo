package com.mapo.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ActionSet
import com.mapo.data.model.steam.ActionSetGraph
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.ActivatorGraph
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.BindingGroupGraph
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.ControllerProfile
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.model.steam.GroupInputGraph
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.PresetEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InputEditorScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_inputLabel_andCurrentBindingLabel() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    InputEditorScreen(
                        inputLabel = "A",
                        inputSource = InputSource.BUTTON_DIAMOND,
                        groupInputKey = "button_a",
                        config = sampleConfig(BindingOutput.KeyPress("ENTER"), ActivatorType.FULL_PRESS),
                        pickerResult = null,
                        onConsumePickerResult = {},
                        onPickResult = { _, _ -> },
                        onOpenPicker = { _, _ -> },
                        onAddActivator = { _, _ -> },
                        onRemoveActivator = {},
                        onSetActivatorType = { _, _ -> },
                        onOpenActivatorSettings = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("A").assertIsDisplayed()
        composeRule.onNodeWithText("KB: ENTER", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Regular Press", useUnmergedTree = true).assertExists()
    }

    @Test
    fun addActivatorButton_opensDropdown_andInvokesAdd() {
        var addedFor: Long? = null
        var addedType: ActivatorType? = null
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    InputEditorScreen(
                        inputLabel = "A",
                        inputSource = InputSource.BUTTON_DIAMOND,
                        groupInputKey = "button_a",
                        config = sampleConfig(BindingOutput.Unbound, ActivatorType.FULL_PRESS),
                        pickerResult = null,
                        onConsumePickerResult = {},
                        onPickResult = { _, _ -> },
                        onOpenPicker = { _, _ -> },
                        onAddActivator = { groupInputId, type ->
                            addedFor = groupInputId
                            addedType = type
                        },
                        onRemoveActivator = {},
                        onSetActivatorType = { _, _ -> },
                        onOpenActivatorSettings = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Add Activator", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        // The dropdown lists every activator type; tapping "Long Press" should fire onAddActivator.
        composeRule.onNodeWithText("Long Press", useUnmergedTree = true).performClick()

        assert(addedFor == 10L && addedType == ActivatorType.LONG_PRESS) {
            "Expected addActivator(10L, LONG_PRESS) but got ($addedFor, $addedType)"
        }
    }

    @Test
    fun unconfiguredInput_showsPlaceholder() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    InputEditorScreen(
                        inputLabel = "Mystery",
                        inputSource = InputSource.GYRO,  // not present in sample config
                        groupInputKey = "gyro_unused",
                        config = sampleConfig(BindingOutput.Unbound, ActivatorType.FULL_PRESS),
                        pickerResult = null,
                        onConsumePickerResult = {},
                        onPickResult = { _, _ -> },
                        onOpenPicker = { _, _ -> },
                        onAddActivator = { _, _ -> },
                        onRemoveActivator = {},
                        onSetActivatorType = { _, _ -> },
                        onOpenActivatorSettings = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("This input isn't part of the active config.", useUnmergedTree = true)
            .assertExists()
    }

    /**
     * One BUTTON_DIAMOND group with one input (button_a) carrying one activator of [type]
     * whose primary binding is [output]. groupInputId = 10L so tests can assert against it.
     */
    private fun sampleConfig(output: BindingOutput, type: ActivatorType): ControllerConfig {
        val activator = Activator(id = 100L, groupInputId = 10L, type = type, orderIndex = 0)
        val binding = output.toEntity().let { (t, args) ->
            Binding(id = 1000L, activatorId = activator.id, outputType = t, args = args, orderIndex = 0)
        }
        val buttonAInput = GroupInputGraph(
            input = GroupInput(id = 10L, bindingGroupId = 1L, inputKey = "button_a", orderIndex = 0),
            activators = listOf(ActivatorGraph(activator, listOf(binding))),
        )
        val group = BindingGroupGraph(
            group = BindingGroup(id = 1L, actionSetId = 1L, name = "face_buttons", mode = BindingMode.BUTTON_PAD),
            inputs = listOf(buttonAInput),
        )
        val preset = PresetEntry(InputSource.BUTTON_DIAMOND, "active", group)
        val actionSet = ActionSetGraph(
            actionSet = ActionSet(id = 1L, controllerProfileId = 1L, name = "default", title = "Default"),
            layers = emptyList(),
            preset = listOf(preset),
        )
        return ControllerConfig(
            controllerProfile = ControllerProfile(
                id = 1L, profileId = 1L,
                controllerType = ControllerType.GENERIC_ANDROID,
                name = "Default",
            ),
            actionSets = listOf(actionSet),
        )
    }
}
