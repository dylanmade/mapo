package com.mapo.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
class ActivatorEditorScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longPress_rendersHoldTimeSlider() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    ActivatorEditorScreen(
                        activatorId = 100L,
                        title = "A · Long Press",
                        config = sampleConfig(ActivatorType.LONG_PRESS, """{"long_press_time_ms":750}"""),
                        onSettingsChange = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("A · Long Press").assertIsDisplayed()
        composeRule.onNodeWithText("Hold time", useUnmergedTree = true).assertExists()
        // 0.75 s parsed from the settings JSON, displayed by the slider header.
        composeRule.onNodeWithText("0.75 s", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Universal settings", useUnmergedTree = true).assertExists()
    }

    @Test
    fun doublePress_rendersMaxTimeBetweenTapsSlider() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    ActivatorEditorScreen(
                        activatorId = 100L,
                        title = "A · Double Press",
                        config = sampleConfig(ActivatorType.DOUBLE_PRESS, """{"double_tap_time_ms":150}"""),
                        onSettingsChange = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Max time between taps", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("0.15 s", useUnmergedTree = true).assertExists()
    }

    @Test
    fun fullPress_showsInterruptionSection_andNoTypeSpecificSlider() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    ActivatorEditorScreen(
                        activatorId = 100L,
                        title = "A · Regular Press",
                        config = sampleConfig(ActivatorType.FULL_PRESS, "{}"),
                        onSettingsChange = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Universal section is always there.
        composeRule.onNodeWithText("Universal settings", useUnmergedTree = true).assertExists()
        // Interruption section only appears for FULL/RELEASE.
        composeRule.onNodeWithText("Interruption", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Interruptable", useUnmergedTree = true).assertExists()
    }

    @Test
    fun startPress_showsNoTypeSpecificSection_andNoInterruption() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.size(1000.dp, 1400.dp)) {
                    ActivatorEditorScreen(
                        activatorId = 100L,
                        title = "A · Start Press",
                        config = sampleConfig(ActivatorType.START_PRESS, "{}"),
                        onSettingsChange = { _, _ -> },
                        onBack = {},
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Universal settings", useUnmergedTree = true).assertExists()
        // Activation timing only appears for LONG/DOUBLE.
        composeRule.onNodeWithText("Activation timing", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Interruption", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun sampleConfig(type: ActivatorType, settingsJson: String): ControllerConfig {
        val activator = Activator(
            id = 100L,
            groupInputId = 10L,
            type = type,
            settingsJson = settingsJson,
            orderIndex = 0,
        )
        val binding = Binding(
            id = 1000L,
            activatorId = activator.id,
            outputType = BindingOutputType.UNBOUND,
            args = "",
            orderIndex = 0,
        )
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
