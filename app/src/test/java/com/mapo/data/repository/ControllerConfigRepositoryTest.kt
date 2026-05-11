package com.mapo.data.repository

import com.mapo.data.db.steam.FakeActionLayerDao
import com.mapo.data.db.steam.FakeActionSetDao
import com.mapo.data.db.steam.FakeActivatorDao
import com.mapo.data.db.steam.FakeBindingDao
import com.mapo.data.db.steam.FakeBindingGroupDao
import com.mapo.data.db.steam.FakeControllerProfileDao
import com.mapo.data.db.steam.FakeGameActionDao
import com.mapo.data.db.steam.FakeGroupInputDao
import com.mapo.data.db.steam.FakePresetBindingDao
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource
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
        subject = ControllerConfigRepository(
            controllerProfileDao,
            actionSetDao,
            actionLayerDao,
            bindingGroupDao,
            groupInputDao,
            activatorDao,
            bindingDao,
            presetBindingDao,
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
}
