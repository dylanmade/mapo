package com.mapo.data.io.vdf

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end translation tests: parse VDF text → [ImportedConfig]. Asserts the
 * mapping decisions the persistence brick relies on, modeled on the Guild Wars 2
 * Steam Deck config (legacy raw, layers, trigger soft-pull, layer-add commands,
 * mode shifts).
 */
class VdfImporterTest {

    private val sample = """
        "controller_mappings"
        {
            "version" "3"
            "title" "gg"
            "controller_type" "controller_neptune"
            "actions" { "Default" { "title" "Default" "legacy_set" "1" } }
            "action_layers"
            {
                "Combat" { "title" "Combat" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" }
            }
            "group"
            {
                "id" "0"
                "mode" "four_buttons"
                "inputs"
                {
                    "button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press RETURN, , " } } } }
                    "button_y" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button SHOULDER_LEFT, , " } } } }
                }
            }
            "group"
            {
                "id" "9"
                "mode" "dpad"
                "inputs"
                {
                    "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DPAD_UP, , " } } } }
                }
            }
            "group"
            {
                "id" "4"
                "mode" "trigger"
                "inputs"
                {
                    "click"
                    {
                        "activators"
                        {
                            "Full_Press" { "bindings" { "binding" "mouse_button LEFT, , " } }
                            "Soft_Press" { "bindings" { "binding" "controller_action add_layer 3 1 1, , " } }
                        }
                    }
                }
                "settings" { "output_trigger" "1" }
            }
            "group"
            {
                "id" "20"
                "mode" "single_button"
                "inputs"
                {
                    "click" { "activators" { "Full_Press" { "bindings" { "binding" "mode_shift right_joystick 17, , " } } } }
                }
            }
            "preset"
            {
                "id" "0"
                "name" "Default"
                "group_source_bindings"
                {
                    "0" "button_diamond active"
                    "9" "dpad active"
                    "4" "left_trigger active"
                    "20" "right_bumper active"
                }
            }
            "preset"
            {
                "id" "1"
                "name" "Combat"
                "group_source_bindings"
                {
                    "9" "dpad active"
                }
            }
        }
    """.trimIndent()

    private val result = VdfImporter.import(sample)

    @Test
    fun mapsControllerTypeAndMetadata() {
        assertEquals(ControllerType.STEAM_DECK, result.controllerType)
        assertEquals("controller_neptune", result.sourceControllerToken)
        assertEquals("gg", result.title)
        assertTrue(result.isLegacyRawBindings)
    }

    @Test
    fun buildsSetWithLayer() {
        val set = result.sets.single()
        assertEquals("Default", set.name)
        assertEquals(0, set.orderIndex)
        val layer = set.layers.single()
        assertEquals("Combat", layer.name)
        assertEquals("Default", layer.parentSetName)
        assertEquals(1, layer.groups.size) // the dpad group bound under the Combat preset
    }

    @Test
    fun mapsSourcesAndModes() {
        val set = result.sets.single()
        val faces = set.groups.single { it.inputSource == InputSource.BUTTON_DIAMOND }
        assertEquals(BindingMode.BUTTON_PAD, faces.group.mode)
        val dpad = set.groups.single { it.inputSource == InputSource.DPAD }
        assertEquals(BindingMode.DPAD, dpad.group.mode)
    }

    @Test
    fun renamesKeyAndButtonTokens() {
        val faces = result.sets.single().groups.single { it.inputSource == InputSource.BUTTON_DIAMOND }
        val a = faces.group.inputs.single { it.inputKey == "button_a" }
        assertEquals(BindingOutput.KeyPress("ENTER"), (a.activators.single().commands.single() as ImportedCommand.Output).output)
        val y = faces.group.inputs.single { it.inputKey == "button_y" }
        assertEquals(BindingOutput.XInputButton("BUTTON_L1"), (y.activators.single().commands.single() as ImportedCommand.Output).output)
    }

    @Test
    fun renamesDpadSubInput() {
        val dpad = result.sets.single().groups.single { it.inputSource == InputSource.DPAD }
        assertEquals("dpad_up", dpad.group.inputs.single().inputKey)
    }

    @Test
    fun splitsTriggerSoftPullIntoSubInput() {
        val trigger = result.sets.single().groups.single { it.inputSource == InputSource.LEFT_TRIGGER }
        assertEquals(BindingMode.TRIGGER, trigger.group.mode)

        val keys = trigger.group.inputs.map { it.inputKey }.toSet()
        assertEquals(setOf("full_pull", "soft_pull"), keys)

        // Soft_Press activator re-homed onto soft_pull as a plain Full Press.
        val softPull = trigger.group.inputs.single { it.inputKey == "soft_pull" }
        assertEquals(ActivatorType.FULL_PRESS, softPull.activators.single().type)
        val cmd = softPull.activators.single().commands.single() as ImportedCommand.Output
        assertEquals(BindingOutput.ControllerAction("add_layer", listOf("3", "1", "1")), cmd.output)

        // Full_Press stays on full_pull.
        val fullPull = trigger.group.inputs.single { it.inputKey == "full_pull" }
        assertEquals(BindingOutput.MouseButton("MOUSE_LEFT"), (fullPull.activators.single().commands.single() as ImportedCommand.Output).output)
    }

    @Test
    fun carriesGroupSettingsAndWarns() {
        val trigger = result.sets.single().groups.single { it.inputSource == InputSource.LEFT_TRIGGER }
        assertEquals("""{"output_trigger":"1"}""", trigger.group.settingsJson)
        assertTrue(result.warnings.any { it.kind == ImportWarningKind.SETTINGS_NOT_TRANSLATED })
    }

    @Test
    fun recognizesModeShiftTrigger() {
        // The right_bumper single-button group carries a mode_shift command → ModeShiftTrigger.
        val shifter = result.sets.single().groups.first { pg ->
            pg.group.inputs.any { it.activators.any { a -> a.commands.any { c -> c is ImportedCommand.ModeShiftTrigger } } }
        }
        val cmd = shifter.group.inputs.flatMap { it.activators }.flatMap { it.commands }
            .filterIsInstance<ImportedCommand.ModeShiftTrigger>().single()
        assertEquals("right_joystick", cmd.ownerSourceToken)
        assertEquals("17", cmd.targetVdfGroupId)
        assertEquals(1, result.summary.modeShiftCount)
    }

    @Test
    fun summaryCounts() {
        val s = result.summary
        assertEquals(1, s.actionSetCount)
        assertEquals(1, s.actionLayerCount)
        // 4 groups in Default preset + 1 (dpad) under Combat layer preset = 5 materialized.
        assertEquals(5, s.groupCount)
        assertEquals(0, s.gameActionPlaceholderCount)
    }

    @Test
    fun actionBasedConfigWarnsAndPlaceholdersGameActions() {
        val actionBased = VdfImporter.import(
            """
            "controller_mappings"
            {
                "actions" { "Default" { "legacy_set" "0" } }
                "group"
                {
                    "id" "0" "mode" "four_buttons"
                    "inputs" { "button_a" { "activators" { "Full_Press" { "bindings" { "binding" "game_action InGame Jump, , " } } } } }
                }
                "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "button_diamond active" } }
            }
            """.trimIndent(),
        )
        assertTrue(actionBased.warnings.any { it.kind == ImportWarningKind.ACTION_BASED_CONFIG })
        assertTrue(actionBased.warnings.any { it.kind == ImportWarningKind.GAME_ACTION_PLACEHOLDER })
        assertEquals(1, actionBased.summary.gameActionPlaceholderCount)
        val a = actionBased.sets.single().groups.single().group.inputs.single()
        assertEquals(BindingOutput.GameAction("InGame", "Jump"), (a.activators.single().commands.single() as ImportedCommand.Output).output)
    }

    @Test
    fun unmappedModeDegradesToNoneWithWarning() {
        val cfg = VdfImporter.import(
            """
            "controller_mappings"
            {
                "actions" { "Default" { "legacy_set" "1" } }
                "group" { "id" "0" "mode" "2dscroll" "inputs" { } }
                "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "right_trackpad active" } }
            }
            """.trimIndent(),
        )
        val g = cfg.sets.single().groups.single()
        assertEquals(BindingMode.NONE, g.group.mode)
        assertTrue(cfg.warnings.any { it.kind == ImportWarningKind.UNMAPPED_MODE })
    }
}
