package com.mappo.data.io.vdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural-reader tests against a representative `controller_mappings` config
 * modeled on the Guild Wars 2 Steam Deck example (the Phase 8a verify fixture):
 * legacy raw bindings, two action layers, several group modes, group + activator
 * settings, mode-shift source bindings, and localization.
 */
class VdfControllerConfigTest {

    private val sample = """
        "controller_mappings"
        {
            "version" "3"
            "revision" "111"
            "title" "gg"
            "description" "FFXIV-like mappings"
            "controller_type" "controller_neptune"
            "actions"
            {
                "Default" { "title" "Default" "legacy_set" "1" }
            }
            "action_layers"
            {
                "Preset_1000002" { "title" "Right Trigger - Spells" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" }
                "Preset_1000003" { "title" "Left Trigger - Mouse" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" }
            }
            "localization"
            {
                "english" { "title" "Gamepad" "description" "Built-in gamepad support" }
            }
            "group"
            {
                "id" "0"
                "mode" "four_buttons"
                "inputs"
                {
                    "button_a"
                    {
                        "activators"
                        {
                            "Full_Press" { "bindings" { "binding" "key_press SPACE, , " } }
                        }
                        "disabled_activators" { }
                    }
                    "button_y"
                    {
                        "activators"
                        {
                            "Full_Press" { "bindings" { "binding" "xinput_button Y, , " } "settings" { "haptic_intensity" "1" } }
                        }
                        "disabled_activators" { }
                    }
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
                            "Full_Press" { "bindings" { "binding" "controller_action add_layer 3 1 1, , " } }
                        }
                        "disabled_activators" { }
                    }
                }
                "settings" { "output_trigger" "1" }
            }
            "preset"
            {
                "id" "0"
                "name" "Default"
                "group_source_bindings"
                {
                    "0" "button_diamond active"
                    "17" "right_joystick active modeshift"
                    "3" "joystick inactive"
                }
            }
            "settings" { "left_trackpad_mode" "0" }
        }
    """.trimIndent()

    private val cfg = VdfControllerConfig.parse(sample)

    @Test
    fun readsTopLevelMetadata() {
        assertEquals("3", cfg.version)
        assertEquals("111", cfg.revision)
        assertEquals("gg", cfg.title)
        assertEquals("controller_neptune", cfg.controllerType)
    }

    @Test
    fun readsActionSetsAndLayers() {
        assertEquals(listOf("Default"), cfg.actionSets.map { it.name })
        assertEquals("1", cfg.actionSets.single().legacySet)

        assertEquals(2, cfg.actionLayers.size)
        val layer = cfg.actionLayers.first()
        assertEquals("Preset_1000002", layer.name)
        assertEquals("Default", layer.parentSetName)
        assertEquals("1", layer.setLayer)
    }

    @Test
    fun detectsLegacyRawBindings() {
        assertTrue(cfg.isLegacyRawBindings)
    }

    @Test
    fun detectsActionBasedConfig() {
        val actionBased = VdfControllerConfig.parse(
            """"controller_mappings" { "actions" { "Default" { "legacy_set" "0" } } }""",
        )
        assertFalse(actionBased.isLegacyRawBindings)
    }

    @Test
    fun readsGroupsModesAndSettings() {
        assertEquals(2, cfg.groups.size)
        val trigger = cfg.groups.single { it.id == "4" }
        assertEquals("trigger", trigger.mode)
        assertEquals("1", trigger.settings["output_trigger"])
    }

    @Test
    fun readsInputsActivatorsAndBindings() {
        val faceGroup = cfg.groups.single { it.id == "0" }
        assertEquals(setOf("button_a", "button_y"), faceGroup.inputs.map { it.name }.toSet())

        val buttonA = faceGroup.inputs.single { it.name == "button_a" }
        val activator = buttonA.activators.single()
        assertEquals("Full_Press", activator.type)
        val binding = activator.bindings.single()
        assertEquals("key_press", binding.verb)
        assertEquals(listOf("SPACE"), binding.args)
    }

    @Test
    fun readsActivatorSettings() {
        val buttonY = cfg.groups.single { it.id == "0" }.inputs.single { it.name == "button_y" }
        assertEquals("1", buttonY.activators.single().settings["haptic_intensity"])
    }

    @Test
    fun parsesControllerActionBinding() {
        val click = cfg.groups.single { it.id == "4" }.inputs.single { it.name == "click" }
        val binding = click.activators.single().bindings.single()
        assertEquals("controller_action", binding.verb)
        assertEquals(listOf("add_layer", "3", "1", "1"), binding.args)
    }

    @Test
    fun readsPresetSourceBindingsWithModeshift() {
        val preset = cfg.presets.single()
        assertEquals("Default", preset.name)

        val modeshift = preset.sourceBindings.single { it.groupId == "17" }
        assertEquals("right_joystick", modeshift.source)
        assertTrue(modeshift.active)
        assertTrue(modeshift.modeshift)

        val inactive = preset.sourceBindings.single { it.groupId == "3" }
        assertFalse(inactive.active)
        assertFalse(inactive.modeshift)
    }

    @Test
    fun resolvesLocalization() {
        assertEquals("Gamepad", cfg.localization["english"]?.get("title"))
    }

    @Test
    fun parsesBindingLabelAndIconFields() {
        val binding = VdfBinding.parse("key_press SPACE, Jump, icons/jump.png")
        assertEquals("key_press", binding.verb)
        assertEquals(listOf("SPACE"), binding.args)
        assertEquals("Jump", binding.label)
        assertEquals("icons/jump.png", binding.icon)
    }
}
