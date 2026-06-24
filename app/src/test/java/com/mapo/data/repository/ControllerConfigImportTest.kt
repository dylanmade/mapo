package com.mapo.data.repository

import com.mapo.data.db.steam.FakeActionLayerDao
import com.mapo.data.db.steam.FakeActionSetDao
import com.mapo.data.db.steam.FakeActivatorDao
import com.mapo.data.db.steam.FakeBindingDao
import com.mapo.data.db.steam.FakeBindingGroupDao
import com.mapo.data.db.steam.FakeControllerProfileDao
import com.mapo.data.db.steam.FakeGameActionDao
import com.mapo.data.db.steam.FakeGroupInputDao
import com.mapo.data.db.steam.FakeLayerPresetBindingDao
import com.mapo.data.db.steam.FakePresetBindingDao
import com.mapo.data.db.steam.FakeSourceModeShiftDao
import com.mapo.data.io.vdf.VdfImporter
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 8a persistence: [ControllerConfigRepository.importConfig] turns a translated
 * [com.mapo.data.io.vdf.ImportedConfig] into the Room binding graph. Uses the same
 * fake-DAO harness as [ControllerConfigRepositoryTest] (no Robolectric needed).
 */
class ControllerConfigImportTest {

    private lateinit var controllerProfileDao: FakeControllerProfileDao
    private lateinit var actionSetDao: FakeActionSetDao
    private lateinit var actionLayerDao: FakeActionLayerDao
    private lateinit var bindingGroupDao: FakeBindingGroupDao
    private lateinit var groupInputDao: FakeGroupInputDao
    private lateinit var activatorDao: FakeActivatorDao
    private lateinit var bindingDao: FakeBindingDao
    private lateinit var presetBindingDao: FakePresetBindingDao
    private lateinit var layerPresetBindingDao: FakeLayerPresetBindingDao
    private lateinit var sourceModeShiftDao: FakeSourceModeShiftDao
    private lateinit var subject: ControllerConfigRepository

    @Before
    fun setUp() {
        controllerProfileDao = FakeControllerProfileDao()
        actionSetDao = FakeActionSetDao()
        actionLayerDao = FakeActionLayerDao()
        bindingGroupDao = FakeBindingGroupDao()
        groupInputDao = FakeGroupInputDao()
        activatorDao = FakeActivatorDao()
        bindingDao = FakeBindingDao()
        presetBindingDao = FakePresetBindingDao()
        layerPresetBindingDao = FakeLayerPresetBindingDao()
        sourceModeShiftDao = FakeSourceModeShiftDao()
        subject = ControllerConfigRepository(
            controllerProfileDao,
            actionSetDao,
            actionLayerDao,
            FakeGameActionDao(),
            bindingGroupDao,
            groupInputDao,
            activatorDao,
            bindingDao,
            presetBindingDao,
            layerPresetBindingDao,
            sourceModeShiftDao,
        )
    }

    private val sample = """
        "controller_mappings"
        {
            "title" "gg"
            "controller_type" "controller_neptune"
            "actions" { "Default" { "title" "Default" "legacy_set" "1" } }
            "action_layers" { "Combat" { "title" "Combat" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" } }
            "group"
            {
                "id" "0" "mode" "four_buttons"
                "inputs"
                {
                    "button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press RETURN, , " } } } }
                    "button_y" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button SHOULDER_LEFT, , " } } } }
                }
            }
            "group"
            {
                "id" "9" "mode" "dpad"
                "inputs" { "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DPAD_UP, , " } } } } }
            }
            "group"
            {
                "id" "4" "mode" "trigger"
                "inputs"
                {
                    "click"
                    {
                        "activators"
                        {
                            "Full_Press" { "bindings" { "binding" "mouse_button LEFT, , " } }
                            "Soft_Press" { "bindings" { "binding" "key_press SPACE, , " } }
                        }
                    }
                }
                "settings" { "output_trigger" "1" }
            }
            "group"
            {
                "id" "17" "mode" "joystick_mouse"
                "inputs" { "click" { "activators" { "Full_Press" { "bindings" { "binding" "key_press M, , " } } } } }
            }
            "group"
            {
                "id" "20" "mode" "single_button"
                "inputs" { "click" { "activators" { "Full_Press" { "bindings" { "binding" "mode_shift right_joystick 17, , " } } } } }
            }
            "group"
            {
                "id" "50" "mode" "dpad"
                "inputs" { "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "key_press K, , " } } } } }
            }
            "preset"
            {
                "id" "0" "name" "Default"
                "group_source_bindings"
                {
                    "0" "button_diamond active"
                    "9" "dpad active"
                    "4" "left_trigger active"
                    "17" "right_joystick active modeshift"
                    "20" "right_bumper active"
                }
            }
            "preset"
            {
                "id" "1" "name" "Combat"
                "group_source_bindings" { "50" "dpad active" }
            }
        }
    """.trimIndent()

    @Test
    fun importsFullGraph() = runTest {
        val cpId = subject.importConfig(profileId = 7L, imported = VdfImporter.import(sample))

        // Controller profile.
        val cp = controllerProfileDao.getById(cpId)!!
        assertEquals(ControllerType.STEAM_DECK, cp.controllerType)
        assertEquals("gg", cp.name)
        assertTrue(cp.legacySet)

        // Action set + layer.
        val sets = actionSetDao.getByControllerProfile(cpId)
        assertEquals(1, sets.size)
        val setId = sets.single().id
        assertEquals("Default", sets.single().name)
        val layers = actionLayerDao.getByActionSets(listOf(setId))
        assertEquals(1, layers.size)
        assertEquals("Combat", layers.single().name)

        // Binding groups: 5 under the set + 1 under the layer.
        val setGroups = bindingGroupDao.getByActionSets(listOf(setId))
        assertEquals(5, setGroups.size)
        val layerGroups = bindingGroupDao.getByActionLayers(listOf(layers.single().id))
        assertEquals(1, layerGroups.size)
        assertEquals(BindingMode.DPAD, layerGroups.single().mode)
    }

    @Test
    fun presetBindingsSkipModeshiftGroup() = runTest {
        val cpId = subject.importConfig(7L, VdfImporter.import(sample))
        val setId = actionSetDao.getByControllerProfile(cpId).single().id

        val presets = presetBindingDao.getByActionSets(listOf(setId))
        // button_diamond, dpad, left_trigger, right_bumper — but NOT the modeshift right_joystick.
        assertEquals(
            setOf(
                InputSource.BUTTON_DIAMOND,
                InputSource.DPAD,
                InputSource.LEFT_TRIGGER,
                InputSource.RIGHT_BUMPER,
            ),
            presets.map { it.inputSource }.toSet(),
        )
        assertTrue(presets.all { it.state == "active" })
        assertNull(presets.firstOrNull { it.inputSource == InputSource.RIGHT_JOYSTICK })
    }

    @Test
    fun renamedKeyAndButtonBindingsPersist() = runTest {
        val cpId = subject.importConfig(7L, VdfImporter.import(sample))
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val faceGroup = presetBindingDao.getByActionSets(listOf(setId))
            .single { it.inputSource == InputSource.BUTTON_DIAMOND }.bindingGroupId

        val inputs = groupInputDao.getByGroups(listOf(faceGroup))
        val buttonA = inputs.single { it.inputKey == "button_a" }
        val aBinding = bindingDao.getByActivators(activatorDao.getByGroupInputs(listOf(buttonA.id)).map { it.id }).single()
        assertEquals(BindingOutputType.KEY_PRESS, aBinding.outputType)
        assertEquals("ENTER", aBinding.args) // RETURN → ENTER rename survived to the row

        val buttonY = inputs.single { it.inputKey == "button_y" }
        val yBinding = bindingDao.getByActivators(activatorDao.getByGroupInputs(listOf(buttonY.id)).map { it.id }).single()
        assertEquals(BindingOutputType.XINPUT_BUTTON, yBinding.outputType)
        assertEquals("BUTTON_L1", yBinding.args) // SHOULDER_LEFT → BUTTON_L1
    }

    @Test
    fun triggerSoftPullSplitPersists() = runTest {
        val cpId = subject.importConfig(7L, VdfImporter.import(sample))
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val triggerGroup = presetBindingDao.getByActionSets(listOf(setId))
            .single { it.inputSource == InputSource.LEFT_TRIGGER }.bindingGroupId

        val keys = groupInputDao.getByGroups(listOf(triggerGroup)).map { it.inputKey }.toSet()
        assertEquals(setOf("full_pull", "soft_pull"), keys)
    }

    @Test
    fun groupSettingsCarriedUnderVdfNamespace() = runTest {
        val cpId = subject.importConfig(7L, VdfImporter.import(sample))
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val triggerGroup = bindingGroupDao.getByActionSets(listOf(setId))
            .single { it.mode == BindingMode.TRIGGER }
        assertEquals("""{"_vdf":{"output_trigger":"1"}}""", triggerGroup.settingsJson)
    }

    @Test
    fun modeShiftWiredToTargetGroup() = runTest {
        val cpId = subject.importConfig(7L, VdfImporter.import(sample))
        val setId = actionSetDao.getByControllerProfile(cpId).single().id

        val shifts = sourceModeShiftDao.getByActionSets(listOf(setId))
        assertEquals(1, shifts.size)
        val shift = shifts.single()
        assertEquals(InputSource.RIGHT_JOYSTICK, shift.ownerSource)        // mode_shift owner
        assertEquals(InputSource.RIGHT_BUMPER, shift.triggerSource)        // group 20's source
        assertEquals("click", shift.triggerSubInput)

        // Target group = the inserted joystick_mouse group (vdf id 17), which has no preset row.
        val target = bindingGroupDao.getById(shift.bindingGroupId)!!
        assertEquals(BindingMode.JOYSTICK_MOUSE, target.mode)
        assertEquals(setId, target.actionSetId)
    }
}
