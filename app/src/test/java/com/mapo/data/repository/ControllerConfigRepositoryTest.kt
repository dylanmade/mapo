package com.mapo.data.repository

import com.mapo.data.db.steam.FakeActionLayerDao
import com.mapo.data.db.steam.FakeActionSetDao
import com.mapo.data.model.steam.ActionLayer
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.db.steam.FakeActivatorDao
import com.mapo.data.db.steam.FakeBindingDao
import com.mapo.data.db.steam.FakeBindingGroupDao
import com.mapo.data.db.steam.FakeControllerProfileDao
import com.mapo.data.db.steam.FakeGameActionDao
import com.mapo.data.db.steam.FakeGroupInputDao
import com.mapo.data.db.steam.FakeLayerPresetBindingDao
import com.mapo.data.db.steam.FakePresetBindingDao
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.LayerPresetBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ControllerConfigRepositoryTest {

    private lateinit var controllerProfileDao: FakeControllerProfileDao
    private lateinit var actionSetDao: FakeActionSetDao
    private lateinit var actionLayerDao: FakeActionLayerDao
    private lateinit var gameActionDao: FakeGameActionDao
    private lateinit var bindingGroupDao: FakeBindingGroupDao
    private lateinit var groupInputDao: FakeGroupInputDao
    private lateinit var activatorDao: FakeActivatorDao
    private lateinit var bindingDao: FakeBindingDao
    private lateinit var presetBindingDao: FakePresetBindingDao
    private lateinit var layerPresetBindingDao: FakeLayerPresetBindingDao
    private lateinit var subject: ControllerConfigRepository

    @Before
    fun setUp() {
        controllerProfileDao = FakeControllerProfileDao()
        actionSetDao = FakeActionSetDao()
        actionLayerDao = FakeActionLayerDao()
        gameActionDao = FakeGameActionDao()
        bindingGroupDao = FakeBindingGroupDao()
        groupInputDao = FakeGroupInputDao()
        activatorDao = FakeActivatorDao()
        bindingDao = FakeBindingDao()
        presetBindingDao = FakePresetBindingDao()
        layerPresetBindingDao = FakeLayerPresetBindingDao()
        subject = ControllerConfigRepository(
            controllerProfileDao,
            actionSetDao,
            actionLayerDao,
            gameActionDao,
            bindingGroupDao,
            groupInputDao,
            activatorDao,
            bindingDao,
            presetBindingDao,
            layerPresetBindingDao,
        )
    }

    @Test
    fun ensureSeeded_freshProfile_createsDefaultControllerProfile() = runTest {
        val cpId = subject.ensureSeeded(profileId = 1L)

        assertTrue("Expected positive auto-gen id", cpId > 0)
        val stored = controllerProfileDao.getByProfile(1L)
        assertEquals(1, stored.size)
        assertEquals(ControllerType.GENERIC_ANDROID, stored[0].controllerType)
        assertEquals("Default", stored[0].name)
        assertTrue(stored[0].legacySet)
    }

    @Test
    fun ensureSeeded_calledTwice_doesNotDuplicate() = runTest {
        val first = subject.ensureSeeded(profileId = 1L)
        val second = subject.ensureSeeded(profileId = 1L)

        assertEquals(first, second)
        assertEquals(1, controllerProfileDao.getByProfile(1L).size)
    }

    @Test
    fun ensureSeeded_differentProfiles_areIsolated() = runTest {
        val a = subject.ensureSeeded(profileId = 1L)
        val b = subject.ensureSeeded(profileId = 2L)

        assertFalse("Each profile gets its own controller_profile id", a == b)
        assertEquals(1, controllerProfileDao.getByProfile(1L).size)
        assertEquals(1, controllerProfileDao.getByProfile(2L).size)
    }

    @Test
    fun seedDefaultConfig_createsOneActionSet() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val sets = actionSetDao.getByControllerProfile(cpId)
        assertEquals(1, sets.size)
        assertEquals("Default", sets[0].title)
        assertTrue(sets[0].legacy)
    }

    @Test
    fun seedDefaultConfig_createsBindingGroupForEverySeededInputSource() = runTest {
        subject.seedDefaultConfig(profileId = 1L)

        val expectedSources = setOf(
            InputSource.BUTTON_DIAMOND,
            InputSource.DPAD,
            InputSource.LEFT_BUMPER,
            InputSource.RIGHT_BUMPER,
            InputSource.LEFT_TRIGGER,
            InputSource.RIGHT_TRIGGER,
            InputSource.LEFT_JOYSTICK,
            InputSource.RIGHT_JOYSTICK,
            InputSource.SWITCH_START,
            InputSource.SWITCH_SELECT,
        )
        val seededSources = presetBindingDao.rows.value.map { it.inputSource }.toSet()
        assertEquals(expectedSources, seededSources)
    }

    @Test
    fun seedDefaultConfig_buttonDiamondGroup_hasButtonPadModeWithFourInputs() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val faceButtons = cfg.activeActionSet!!.presetFor(InputSource.BUTTON_DIAMOND)!!.group

        assertEquals(BindingMode.BUTTON_PAD, faceButtons.group.mode)
        assertEquals(
            listOf("button_a", "button_b", "button_x", "button_y"),
            faceButtons.inputs.map { it.input.inputKey },
        )
    }

    @Test
    fun seedDefaultConfig_dpadGroup_hasDpadModeWithFourDirections() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val dpad = cfg.activeActionSet!!.presetFor(InputSource.DPAD)!!.group

        assertEquals(BindingMode.DPAD, dpad.group.mode)
        assertEquals(
            setOf("dpad_north", "dpad_south", "dpad_east", "dpad_west"),
            dpad.inputs.map { it.input.inputKey }.toSet(),
        )
    }

    @Test
    fun seedDefaultConfig_everyInputHasFullPressActivatorWithUnboundBinding() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!

        for (preset in cfg.activeActionSet!!.preset) {
            for (input in preset.group.inputs) {
                assertEquals(
                    "Each input gets exactly one FULL_PRESS activator at seed time",
                    1, input.activators.size,
                )
                assertEquals(ActivatorType.FULL_PRESS, input.activators[0].activator.type)

                val bindings = input.activators[0].bindings
                assertEquals(1, bindings.size)
                assertEquals(BindingOutputType.UNBOUND, bindings[0].outputType)
            }
        }
    }

    @Test
    fun getActiveConfigOnce_unseededProfile_returnsNull() = runTest {
        assertNull(subject.getActiveConfigOnce(profileId = 1L))
    }

    @Test
    fun observeActiveConfig_emitsSeededConfigOnFirstSubscribe() = runTest {
        val emitted = subject.observeActiveConfig(profileId = 1L).first()
        assertNotNull(emitted)
        assertEquals(ControllerType.GENERIC_ANDROID, emitted!!.controllerProfile.controllerType)
        assertEquals(1, emitted.actionSets.size)
    }

    @Test
    fun setBinding_replacesActivatorBindings() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val activatorId = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].activator.id

        subject.setBinding(activatorId, BindingOutput.KeyPress("ENTER"))

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0]
        assertEquals(1, updated.bindings.size)
        assertEquals(BindingOutputType.KEY_PRESS, updated.bindings[0].outputType)
        assertEquals("ENTER", updated.bindings[0].args)
        assertEquals(BindingOutput.KeyPress("ENTER"), updated.primaryOutput)
    }

    @Test
    fun setBinding_unbound_keepsExactlyOneRowWithUnboundType() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val activatorId = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].activator.id

        subject.setBinding(activatorId, BindingOutput.KeyPress("ENTER"))
        subject.setBinding(activatorId, BindingOutput.Unbound)

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0]
        assertEquals(1, updated.bindings.size)
        assertEquals(BindingOutputType.UNBOUND, updated.bindings[0].outputType)
        assertEquals(BindingOutput.Unbound, updated.primaryOutput)
    }

    // ── addActivator / removeActivator / updateActivatorType (Brick 3.4) ────

    @Test
    fun addActivator_appendsRowWithUnboundBinding_andHigherOrderIndex() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val buttonA = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
        val groupInputId = buttonA.input.id
        val originalActivatorCount = buttonA.activators.size

        val newId = subject.addActivator(groupInputId, ActivatorType.LONG_PRESS)

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
        assertEquals(originalActivatorCount + 1, updated.activators.size)
        val newActivator = updated.activators.first { it.activator.id == newId }
        assertEquals(ActivatorType.LONG_PRESS, newActivator.activator.type)
        assertEquals(originalActivatorCount, newActivator.activator.orderIndex)
        assertEquals(1, newActivator.bindings.size)
        assertEquals(BindingOutput.Unbound, newActivator.primaryOutput)
    }

    @Test
    fun removeActivator_deletesActivatorAndItsBindings() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val buttonA = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
        val target = buttonA.activators[0].activator.id

        subject.removeActivator(target)

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
        assertTrue(updated.activators.none { it.activator.id == target })
    }

    @Test
    fun updateActivatorType_changesTypeWithoutTouchingBindings() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val activator = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0]
        val activatorId = activator.activator.id
        subject.setBinding(activatorId, BindingOutput.KeyPress("ENTER"))

        subject.updateActivatorType(activatorId, ActivatorType.LONG_PRESS)

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators
            .first { it.activator.id == activatorId }
        assertEquals(ActivatorType.LONG_PRESS, updated.activator.type)
        assertEquals(BindingOutput.KeyPress("ENTER"), updated.primaryOutput)
    }

    @Test
    fun updateActivatorType_unknownId_isNoOp() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        subject.updateActivatorType(activatorId = 9_999_999L, type = ActivatorType.LONG_PRESS)
        // Just verifying it doesn't throw — assertion is on completion alone.
    }

    @Test
    fun updateActivatorSettings_replacesJsonBlob() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val activatorId = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].activator.id

        subject.updateActivatorSettings(
            activatorId,
            """{"long_press_time_ms":900,"double_tap_time_ms":200}""",
        )

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0]
            .activator
        assertEquals(
            """{"long_press_time_ms":900,"double_tap_time_ms":200}""",
            updated.settingsJson,
        )
    }

    @Test
    fun updateActivatorSettings_unknownId_isNoOp() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        subject.updateActivatorSettings(activatorId = 9_999_999L, settingsJson = """{"x":1}""")
        // Just verifying it doesn't throw.
    }

    @Test
    fun copyConfig_emptySource_isNoOp() = runTest {
        subject.copyConfig(sourceProfileId = 1L, destProfileId = 2L)

        assertTrue(controllerProfileDao.getByProfile(2L).isEmpty())
    }

    @Test
    fun copyConfig_seededSource_producesEquivalentGraphForDest() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val sourceCfg = subject.getActiveConfigOnce(1L)!!

        subject.copyConfig(sourceProfileId = 1L, destProfileId = 2L)
        val destCfg = subject.getActiveConfigOnce(2L)!!

        assertEquals(sourceCfg.controllerProfile.name, destCfg.controllerProfile.name)
        assertEquals(sourceCfg.actionSets.size, destCfg.actionSets.size)
        val sourceFace = sourceCfg.activeActionSet!!.presetFor(InputSource.BUTTON_DIAMOND)!!.group
        val destFace = destCfg.activeActionSet!!.presetFor(InputSource.BUTTON_DIAMOND)!!.group
        assertEquals(
            sourceFace.inputs.map { it.input.inputKey },
            destFace.inputs.map { it.input.inputKey },
        )
    }

    @Test
    fun copyConfig_clonedGraphHasFreshIds() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        subject.copyConfig(sourceProfileId = 1L, destProfileId = 2L)

        // The "duplicates own their data" invariant: every cloned row gets a new PK and the
        // source's PKs must not appear under the destination.
        val sourceCp = controllerProfileDao.getByProfile(1L).single()
        val destCp = controllerProfileDao.getByProfile(2L).single()
        assertFalse(sourceCp.id == destCp.id)

        val sourceSetIds = actionSetDao.getByControllerProfile(sourceCp.id).map { it.id }.toSet()
        val destSetIds = actionSetDao.getByControllerProfile(destCp.id).map { it.id }.toSet()
        assertTrue("Cloned action sets must use new PKs", (sourceSetIds intersect destSetIds).isEmpty())
    }

    @Test
    fun copyConfig_preservesBindings() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val sourceCfg = subject.getActiveConfigOnce(1L)!!
        val aActivator = sourceCfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].activator.id
        subject.setBinding(aActivator, BindingOutput.KeyPress("ENTER"))

        subject.copyConfig(sourceProfileId = 1L, destProfileId = 2L)

        val destAOutput = subject.getActiveConfigOnce(2L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].primaryOutput
        assertEquals(BindingOutput.KeyPress("ENTER"), destAOutput)
    }

    @Test
    fun copyConfig_editsToDestDoNotAffectSource() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        subject.copyConfig(sourceProfileId = 1L, destProfileId = 2L)

        val destAActivator = subject.getActiveConfigOnce(2L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].activator.id
        subject.setBinding(destAActivator, BindingOutput.KeyPress("ESCAPE"))

        val sourceAOutput = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!
            .activators[0].primaryOutput
        assertEquals(BindingOutput.Unbound, sourceAOutput)
    }

    @Test
    fun setBinding_doesNotLeakAcrossActivators() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val faceButtons = cfg.activeActionSet!!.presetFor(InputSource.BUTTON_DIAMOND)!!.group
        val aActivator = faceButtons.inputByKey("button_a")!!.activators[0].activator.id
        val bActivator = faceButtons.inputByKey("button_b")!!.activators[0].activator.id

        subject.setBinding(aActivator, BindingOutput.KeyPress("ENTER"))

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
        assertEquals(
            BindingOutput.KeyPress("ENTER"),
            updated.inputByKey("button_a")!!.activators[0].primaryOutput,
        )
        assertEquals(
            BindingOutput.Unbound,
            updated.inputByKey("button_b")!!.activators[0].primaryOutput,
        )
    }

    // ── Brick 3.6 multi-command ──────────────────────────────────────────────

    @Test
    fun addCommand_appendsNewBindingAtNextOrderIndex() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val cfg = subject.getActiveConfigOnce(1L)!!
        val aActivator = cfg.activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]

        val originalSize = aActivator.bindings.size
        val newId = subject.addCommand(aActivator.activator.id)
        assert(newId > 0L) { "addCommand should return a non-zero ID" }

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        assertEquals(originalSize + 1, updated.bindings.size)
        // New row is Unbound; original primary still intact at orderIndex 0.
        val newBinding = updated.bindings.first { it.id == newId }
        assertEquals(BindingOutput.Unbound, BindingOutput.fromEntity(newBinding.outputType, newBinding.args))
    }

    @Test
    fun setCommand_updatesOneRowOnly() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val aActivator = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        val firstBindingId = aActivator.bindings[0].id
        val newBindingId = subject.addCommand(aActivator.activator.id)

        subject.setCommand(firstBindingId, BindingOutput.KeyPress("ENTER"))
        subject.setCommand(newBindingId, BindingOutput.KeyPress("ESCAPE"))

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        val first = updated.bindings.first { it.id == firstBindingId }
        val second = updated.bindings.first { it.id == newBindingId }
        assertEquals(BindingOutput.KeyPress("ENTER"), BindingOutput.fromEntity(first.outputType, first.args))
        assertEquals(BindingOutput.KeyPress("ESCAPE"), BindingOutput.fromEntity(second.outputType, second.args))
    }

    @Test
    fun removeCommand_deletesOnlyTargetedRow() = runTest {
        subject.seedDefaultConfig(profileId = 1L)
        val aActivator = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        val secondId = subject.addCommand(aActivator.activator.id)
        val thirdId = subject.addCommand(aActivator.activator.id)

        subject.removeCommand(secondId)

        val updated = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        assertEquals(2, updated.bindings.size)
        assert(updated.bindings.any { it.id == thirdId }) { "Third binding should still be present" }
        assert(updated.bindings.none { it.id == secondId }) { "Second binding should be removed" }
    }

    // ── Action set CRUD (Brick 4.1) ─────────────────────────────────────────

    @Test
    fun addActionSet_blank_seedsDefaultGroupsAndPresets() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)

        val newSetId = subject.addActionSet(cpId, name = "menu", title = "Menu")

        val sets = actionSetDao.getByControllerProfile(cpId)
        assertEquals(2, sets.size)
        // Sets ordered: default (0), new (1)
        assertEquals(1, sets.first { it.id == newSetId }.orderIndex)

        // New set has its own preset_bindings + groups (not shared with default set).
        val newSetPresets = presetBindingDao.rows.value.filter { it.actionSetId == newSetId }
        assertTrue("New set should have its own presets", newSetPresets.size >= 10)
        val newSetGroupIds = newSetPresets.map { it.bindingGroupId }.toSet()
        val defaultSetPresets = presetBindingDao.rows.value
            .filter { it.actionSetId != newSetId && it.actionSetId in sets.map { s -> s.id } }
        val defaultGroupIds = defaultSetPresets.map { it.bindingGroupId }.toSet()
        assertTrue(
            "Groups must not be shared between sets",
            newSetGroupIds.intersect(defaultGroupIds).isEmpty(),
        )
    }

    @Test
    fun addActionSet_inherit_clonesGroupsBindingsAndPresetsFromSource() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val defaultSet = actionSetDao.getByControllerProfile(cpId).single()
        // Customize the source so we can verify the clone copied it.
        val aActivatorId = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0].activator.id
        subject.setBinding(aActivatorId, BindingOutput.KeyPress("ENTER"))

        val clonedSetId = subject.addActionSet(
            cpId, name = "gameplay-copy", title = "Gameplay Copy",
            inheritFromSetId = defaultSet.id,
        )

        // Resolve the cloned set's button_a binding.
        val cfg = subject.getActiveConfigOnce(1L)!!
        val clonedSet = cfg.actionSets.first { it.actionSet.id == clonedSetId }
        val clonedActivator = clonedSet
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0]
        assertEquals(BindingOutputType.KEY_PRESS, clonedActivator.bindings[0].outputType)
        assertEquals("ENTER", clonedActivator.bindings[0].args)
        // Fresh ids (per feedback_duplicates_own_their_data).
        assertTrue(
            "Cloned activator must have a different id than the source activator",
            clonedActivator.activator.id != aActivatorId,
        )
    }

    @Test
    fun duplicateActionSet_independentlyEditable() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val sourceSet = actionSetDao.getByControllerProfile(cpId).single()
        // Set source's button_a to ENTER, then duplicate.
        val sourceActivatorId = subject.getActiveConfigOnce(1L)!!
            .activeActionSet!!
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0].activator.id
        subject.setBinding(sourceActivatorId, BindingOutput.KeyPress("ENTER"))

        val dupSetId = subject.duplicateActionSet(sourceSet.id, "menu", "Menu")

        // Now edit the duplicate's button_a to SPACE.
        val cfg = subject.getActiveConfigOnce(1L)!!
        val dupActivatorId = cfg.actionSets.first { it.actionSet.id == dupSetId }
            .presetFor(InputSource.BUTTON_DIAMOND)!!.group
            .inputByKey("button_a")!!.activators[0].activator.id
        subject.setBinding(dupActivatorId, BindingOutput.KeyPress("SPACE"))

        // Source must remain ENTER; duplicate must be SPACE.
        val sourceBinding = bindingDao.getByActivators(listOf(sourceActivatorId)).single()
        assertEquals("ENTER", sourceBinding.args)
        val dupBinding = bindingDao.getByActivators(listOf(dupActivatorId)).single()
        assertEquals("SPACE", dupBinding.args)
    }

    @Test
    fun renameActionSet_updatesNameAndTitle() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val set = actionSetDao.getByControllerProfile(cpId).single()

        subject.renameActionSet(set.id, name = "menu", title = "Menu Set")

        val updated = actionSetDao.getById(set.id)!!
        assertEquals("menu", updated.name)
        assertEquals("Menu Set", updated.title)
    }

    @Test
    fun renameActionSet_unknownId_isNoOp() = runTest {
        subject.renameActionSet(actionSetId = 999L, name = "x", title = "X")
        // No exception; nothing inserted.
        assertEquals(0, actionSetDao.rows.value.size)
    }

    @Test
    fun deleteActionSet_lastSet_isGuardedAndDoesNothing() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val onlySet = actionSetDao.getByControllerProfile(cpId).single()

        val result = subject.deleteActionSet(onlySet.id)

        assertFalse("Deleting the last set must be refused", result)
        assertEquals(1, actionSetDao.getByControllerProfile(cpId).size)
    }

    @Test
    fun deleteActionSet_nonLastSet_succeeds() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val newSetId = subject.addActionSet(cpId, "menu", "Menu")

        val result = subject.deleteActionSet(newSetId)

        assertTrue(result)
        val remaining = actionSetDao.getByControllerProfile(cpId)
        assertEquals(1, remaining.size)
        assertTrue(remaining.none { it.id == newSetId })
    }

    // ── Layer CRUD (Brick 5.2) ───────────────────────────────────────────────

    @Test
    fun addLayer_appendsEmptyLayerWithOrderIndexZero() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id

        val layerId = subject.addLayer(setId, name = "scope", title = "Scope")

        val rows = actionLayerDao.getByActionSets(listOf(setId))
        assertEquals(1, rows.size)
        val layer = rows.single()
        assertEquals(layerId, layer.id)
        assertEquals("scope", layer.name)
        assertEquals("Scope", layer.title)
        assertEquals(0, layer.orderIndex)
        // No overlay binding_groups land at create time.
        val groups = bindingGroupDao.getByActionLayers(listOf(layerId))
        assertTrue("New layer must start empty (no overlay groups)", groups.isEmpty())
    }

    @Test
    fun addLayer_secondLayerGetsIncrementedOrderIndex() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id

        subject.addLayer(setId, "scope", "Scope")
        val secondId = subject.addLayer(setId, "vehicle", "Vehicle")

        val rows = actionLayerDao.getByActionSets(listOf(setId))
        assertEquals(2, rows.size)
        val second = rows.first { it.id == secondId }
        assertEquals(1, second.orderIndex)
    }

    @Test
    fun renameLayer_updatesNameAndTitle() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")

        subject.renameLayer(layerId, name = "aim_down_sights", title = "Aim Down Sights")

        val updated = actionLayerDao.getById(layerId)!!
        assertEquals("aim_down_sights", updated.name)
        assertEquals("Aim Down Sights", updated.title)
    }

    @Test
    fun renameLayer_unknownId_isNoOp() = runTest {
        subject.renameLayer(layerId = 999L, name = "x", title = "X")
        assertEquals(0, actionLayerDao.rows.value.size)
    }

    @Test
    fun deleteLayer_unknownId_returnsFalse() = runTest {
        val result = subject.deleteLayer(999L)
        assertFalse(result)
    }

    @Test
    fun deleteLayer_existing_succeeds() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")

        val result = subject.deleteLayer(layerId)

        assertTrue(result)
        assertTrue(actionLayerDao.getByActionSets(listOf(setId)).isEmpty())
    }

    @Test
    fun duplicateLayer_emptySource_clonesLayerRowOnly() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val sourceId = subject.addLayer(setId, "scope", "Scope")

        val cloneId = subject.duplicateLayer(sourceId, name = "scope_copy", title = "Scope Copy")

        assertTrue("Cloned layer must have a fresh id", cloneId != sourceId)
        val rows = actionLayerDao.getByActionSets(listOf(setId))
        assertEquals(2, rows.size)
        val clone = rows.first { it.id == cloneId }
        assertEquals(setId, clone.parentActionSetId)
        assertEquals("scope_copy", clone.name)
        assertEquals(1, clone.orderIndex)
    }

    @Test
    fun duplicateLayer_clonesGroupsInputsActivatorsBindings_withFreshIds() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val sourceId = subject.addLayer(setId, "scope", "Scope")
        // Seed an overlay binding_group under the source layer by hand — the 5.5 UI
        // doesn't exist yet, but the cloning path needs to be exercised so it doesn't
        // bit-rot before that brick lands.
        val groupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = null,
                actionLayerId = sourceId,
                name = "face_buttons_overlay",
                mode = BindingMode.BUTTON_PAD,
                settingsJson = "{}",
            )
        )
        val inputId = groupInputDao.insert(
            GroupInput(bindingGroupId = groupId, inputKey = "button_a", orderIndex = 0)
        )
        val activatorId = activatorDao.insert(
            Activator(
                groupInputId = inputId,
                type = ActivatorType.FULL_PRESS,
                settingsJson = "{}",
                orderIndex = 0,
            )
        )
        bindingDao.insert(
            Binding(
                activatorId = activatorId,
                outputType = BindingOutputType.KEY_PRESS,
                args = "ENTER",
                orderIndex = 0,
            )
        )

        val cloneId = subject.duplicateLayer(sourceId, name = "scope_copy", title = "Scope Copy")

        // Source still has exactly its original group / input / activator / binding.
        val sourceGroups = bindingGroupDao.getByActionLayers(listOf(sourceId))
        assertEquals(1, sourceGroups.size)
        // Cloned layer has its own group with a new id.
        val cloneGroups = bindingGroupDao.getByActionLayers(listOf(cloneId))
        assertEquals(1, cloneGroups.size)
        assertTrue(
            "Cloned group must have a fresh id",
            cloneGroups.single().id != sourceGroups.single().id,
        )
        // Inputs / activators / bindings all cloned and fresh-id'd.
        val cloneInputs = groupInputDao.getByGroups(cloneGroups.map { it.id })
        assertEquals(1, cloneInputs.size)
        assertTrue("Cloned input must have a fresh id", cloneInputs.single().id != inputId)
        val cloneActivators = activatorDao.getByGroupInputs(cloneInputs.map { it.id })
        assertEquals(1, cloneActivators.size)
        assertTrue(
            "Cloned activator must have a fresh id",
            cloneActivators.single().id != activatorId,
        )
        val cloneBindings = bindingDao.getByActivators(cloneActivators.map { it.id })
        assertEquals(1, cloneBindings.size)
        assertEquals("ENTER", cloneBindings.single().args)
    }

    @Test
    fun duplicateLayer_editsToCloneDoNotAffectSource() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val sourceId = subject.addLayer(setId, "scope", "Scope")
        val groupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = null,
                actionLayerId = sourceId,
                name = "g",
                mode = BindingMode.BUTTON_PAD,
                settingsJson = "{}",
            )
        )
        val inputId = groupInputDao.insert(
            GroupInput(bindingGroupId = groupId, inputKey = "button_a", orderIndex = 0)
        )
        val sourceActivatorId = activatorDao.insert(
            Activator(groupInputId = inputId, type = ActivatorType.FULL_PRESS, settingsJson = "{}", orderIndex = 0)
        )
        bindingDao.insert(
            Binding(activatorId = sourceActivatorId, outputType = BindingOutputType.KEY_PRESS, args = "ENTER", orderIndex = 0)
        )

        val cloneId = subject.duplicateLayer(sourceId, name = "copy", title = "Copy")
        val cloneActivatorId = activatorDao.getByGroupInputs(
            groupInputDao.getByGroups(
                bindingGroupDao.getByActionLayers(listOf(cloneId)).map { it.id }
            ).map { it.id }
        ).single().id
        // Edit the clone's binding via setBinding (replace).
        subject.setBinding(cloneActivatorId, BindingOutput.KeyPress("SPACE"))

        // Source's binding is untouched.
        val sourceBinding = bindingDao.getByActivators(listOf(sourceActivatorId)).single()
        assertEquals("ENTER", sourceBinding.args)
        val cloneBinding = bindingDao.getByActivators(listOf(cloneActivatorId)).single()
        assertEquals("SPACE", cloneBinding.args)
    }

    // ── Brick 5.5.a: layer preset bindings ──────────────────────────────────

    @Test
    fun observeActiveConfig_hydratesActionLayerGraphPreset_fromLayerPresetBindingDao() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        // Seed an overlay binding_group + the layer_preset_binding pointing at it.
        // No 5.5.b "materializeLayerOverride" helper yet, so we hand-roll the rows.
        val overlayGroupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = null,
                actionLayerId = layerId,
                name = "face_buttons_overlay",
                mode = BindingMode.BUTTON_PAD,
                settingsJson = "{}",
            )
        )
        groupInputDao.insert(
            GroupInput(bindingGroupId = overlayGroupId, inputKey = "button_a", orderIndex = 0)
        )
        layerPresetBindingDao.insert(
            LayerPresetBinding(
                actionLayerId = layerId,
                inputSource = InputSource.BUTTON_DIAMOND,
                state = "active",
                bindingGroupId = overlayGroupId,
            )
        )

        val config = subject.observeActiveConfig(profileId = 1L).first()
        val layerGraph = config!!.actionSets.single().layers.single()
        assertEquals(layerId, layerGraph.layer.id)
        assertEquals(1, layerGraph.preset.size)
        val entry = layerGraph.preset.single()
        assertEquals(InputSource.BUTTON_DIAMOND, entry.inputSource)
        assertEquals("active", entry.state)
        assertEquals(overlayGroupId, entry.group.group.id)
        assertEquals("button_a", entry.group.inputs.single().input.inputKey)
    }

    @Test
    fun duplicateLayer_clonesLayerPresetBindingRows_pointingAtClonedGroups() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val sourceLayerId = subject.addLayer(setId, "scope", "Scope")
        val overlayGroupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = null,
                actionLayerId = sourceLayerId,
                name = "face_overlay",
                mode = BindingMode.BUTTON_PAD,
                settingsJson = "{}",
            )
        )
        groupInputDao.insert(GroupInput(bindingGroupId = overlayGroupId, inputKey = "button_a", orderIndex = 0))
        layerPresetBindingDao.insert(
            LayerPresetBinding(
                actionLayerId = sourceLayerId,
                inputSource = InputSource.BUTTON_DIAMOND,
                state = "active",
                bindingGroupId = overlayGroupId,
            )
        )

        val cloneId = subject.duplicateLayer(sourceLayerId, name = "scope_copy", title = "Scope Copy")

        val sourceRows = layerPresetBindingDao.getByActionLayers(listOf(sourceLayerId))
        val cloneRows = layerPresetBindingDao.getByActionLayers(listOf(cloneId))
        assertEquals(1, sourceRows.size)
        assertEquals(1, cloneRows.size)
        // Source row still points at the original overlay group.
        assertEquals(overlayGroupId, sourceRows.single().bindingGroupId)
        // Cloned row points at a FRESH group (not the original) — per
        // feedback_duplicates_own_their_data.
        assertTrue(
            "Cloned layer_preset_binding must reference the cloned binding_group, not the source",
            cloneRows.single().bindingGroupId != overlayGroupId,
        )
        assertEquals(InputSource.BUTTON_DIAMOND, cloneRows.single().inputSource)
    }

    @Test
    fun observeActiveConfig_layerWithNoPreset_yieldsEmptyPresetList() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        subject.addLayer(setId, "scope", "Scope")

        val config = subject.observeActiveConfig(profileId = 1L).first()
        val layerGraph = config!!.actionSets.single().layers.single()
        assertTrue("Default-state layer has no preset entries", layerGraph.preset.isEmpty())
    }

    // ── Brick 5.5.b: layer override CRUD ─────────────────────────────────────

    @Test
    fun materializeLayerOverride_freshLayer_createsGroupInputActivatorAndBindingChain() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")

        val newInputId = subject.materializeLayerOverride(
            layerId = layerId,
            inputSource = InputSource.BUTTON_DIAMOND,
            groupInputKey = "button_a",
        )

        // Overlay binding_group exists on the layer.
        val overlayGroups = bindingGroupDao.getByActionLayers(listOf(layerId))
        assertEquals(1, overlayGroups.size)
        // GroupInput materialized under it for button_a.
        val groupInputs = groupInputDao.getByGroups(listOf(overlayGroups.single().id))
        assertEquals(1, groupInputs.size)
        assertEquals("button_a", groupInputs.single().inputKey)
        assertEquals(newInputId, groupInputs.single().id)
        // Default FULL_PRESS activator with one unbound binding.
        val activators = activatorDao.getByGroupInputs(listOf(newInputId))
        assertEquals(1, activators.size)
        assertEquals(ActivatorType.FULL_PRESS, activators.single().type)
        val bindings = bindingDao.getByActivators(listOf(activators.single().id))
        assertEquals(1, bindings.size)
        assertEquals(BindingOutputType.UNBOUND, bindings.single().outputType)
        // layer_preset_binding row points overlay group at the input source.
        val presets = layerPresetBindingDao.getByActionLayers(listOf(layerId))
        assertEquals(1, presets.size)
        assertEquals(InputSource.BUTTON_DIAMOND, presets.single().inputSource)
        assertEquals(overlayGroups.single().id, presets.single().bindingGroupId)
    }

    @Test
    fun materializeLayerOverride_secondSubInputOnSameSource_reusesOverlayGroup() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")

        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_b")

        // Still one overlay group for BUTTON_DIAMOND on this layer.
        val overlayGroups = bindingGroupDao.getByActionLayers(listOf(layerId))
        assertEquals(1, overlayGroups.size)
        // Both button_a and button_b under it.
        val keys = groupInputDao.getByGroups(overlayGroups.map { it.id }).map { it.inputKey }.sorted()
        assertEquals(listOf("button_a", "button_b"), keys)
        // Still one preset row (the group is shared).
        assertEquals(1, layerPresetBindingDao.getByActionLayers(listOf(layerId)).size)
    }

    @Test
    fun materializeLayerOverride_calledTwiceForSameSubInput_isIdempotent() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")

        val first = subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")
        val second = subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        assertEquals("Same call must yield the same GroupInput id (no duplicate row)", first, second)
        // Only one group_input for button_a.
        val overlayGroup = bindingGroupDao.getByActionLayers(listOf(layerId)).single()
        assertEquals(
            1,
            groupInputDao.getByGroups(listOf(overlayGroup.id)).count { it.inputKey == "button_a" },
        )
        // Only one activator + binding pair.
        val activators = activatorDao.getByGroupInputs(listOf(first))
        assertEquals(1, activators.size)
        assertEquals(1, bindingDao.getByActivators(listOf(activators.single().id)).size)
    }

    @Test
    fun materializeLayerOverride_inheritsBaseGroupModeAndSettings() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        // Mutate the base BUTTON_DIAMOND group's settings so we can verify inheritance.
        val basePresetRow = presetBindingDao.getByActionSets(listOf(setId))
            .single { it.inputSource == InputSource.BUTTON_DIAMOND && it.state == "active" }
        val baseGroup = bindingGroupDao.getById(basePresetRow.bindingGroupId)!!
        bindingGroupDao.update(baseGroup.copy(settingsJson = """{"deadzone":0.42}"""))

        val layerId = subject.addLayer(setId, "scope", "Scope")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        val overlayGroup = bindingGroupDao.getByActionLayers(listOf(layerId)).single()
        assertEquals(
            "Overlay group must inherit base group's mode",
            baseGroup.mode, overlayGroup.mode,
        )
        assertEquals(
            "Overlay group must inherit base group's settingsJson",
            """{"deadzone":0.42}""", overlayGroup.settingsJson,
        )
    }

    @Test
    fun clearLayerOverride_existingOverride_deletesGroupInput() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        val materializedId =
            subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        subject.clearLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        assertNull(groupInputDao.getById(materializedId))
    }

    @Test
    fun clearLayerOverride_lastSubInput_alsoDropsOverlayGroupAndPresetRow() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        subject.clearLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        assertTrue(
            "Overlay binding_group must be gone once its last sub-input is cleared",
            bindingGroupDao.getByActionLayers(listOf(layerId)).isEmpty(),
        )
        assertTrue(
            "layer_preset_binding row must also be gone",
            layerPresetBindingDao.getByActionLayers(listOf(layerId)).isEmpty(),
        )
    }

    @Test
    fun clearLayerOverride_keepsSiblingSubInputsIntact() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_b")

        subject.clearLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        // button_b's chain is untouched. Overlay group still present.
        val overlayGroups = bindingGroupDao.getByActionLayers(listOf(layerId))
        assertEquals(1, overlayGroups.size)
        val keys = groupInputDao.getByGroups(overlayGroups.map { it.id }).map { it.inputKey }
        assertEquals(listOf("button_b"), keys)
        // Preset row still present (group still has a sub-input).
        assertEquals(1, layerPresetBindingDao.getByActionLayers(listOf(layerId)).size)
    }

    @Test
    fun clearLayerOverride_unknownOverride_isNoOp() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        // No materialize call — nothing exists for this layer.

        subject.clearLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")
        // No throw, no side effects: still no overlay group + no preset row.
        assertTrue(bindingGroupDao.getByActionLayers(listOf(layerId)).isEmpty())
        assertTrue(layerPresetBindingDao.getByActionLayers(listOf(layerId)).isEmpty())
    }

    @Test
    fun clearLayerOverride_unknownSubInputOnExistingOverlayGroup_isNoOp() = runTest {
        val cpId = subject.seedDefaultConfig(profileId = 1L)
        val setId = actionSetDao.getByControllerProfile(cpId).single().id
        val layerId = subject.addLayer(setId, "scope", "Scope")
        subject.materializeLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_a")

        // Sub-input not in the overlay group.
        subject.clearLayerOverride(layerId, InputSource.BUTTON_DIAMOND, "button_x")

        // button_a override untouched; group + preset still present.
        val overlayGroup = bindingGroupDao.getByActionLayers(listOf(layerId)).single()
        val keys = groupInputDao.getByGroups(listOf(overlayGroup.id)).map { it.inputKey }
        assertEquals(listOf("button_a"), keys)
    }

}
