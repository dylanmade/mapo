package com.mapo.data.repository

import com.mapo.data.db.GamepadMappingDao
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.GamepadMapping
import com.mapo.data.model.RemapTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GamepadMappingRepositoryTest {

    private lateinit var dao: FakeGamepadDao
    private lateinit var subject: GamepadMappingRepository

    @Before
    fun setUp() {
        dao = FakeGamepadDao()
        subject = GamepadMappingRepository(dao)
    }

    @Test
    fun saveMappings_persistsAllNonUnboundEntries() = runTest {
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(
                DeviceButton.BUTTON_A to RemapTarget.Keyboard("ENTER"),
                DeviceButton.BUTTON_B to RemapTarget.Mouse("LEFT"),
                DeviceButton.BUTTON_X to RemapTarget.Gamepad("BUTTON_A"),
            ),
        )

        val stored = dao.getByProfileOnce(1L).associateBy { it.gamepadButton }
        assertEquals("keyboard:ENTER", stored["BUTTON_A"]?.targetEncoded)
        assertEquals("mouse:LEFT", stored["BUTTON_B"]?.targetEncoded)
        assertEquals("gamepad:BUTTON_A", stored["BUTTON_X"]?.targetEncoded)
    }

    @Test
    fun saveMappings_filtersOutUnboundEntries() = runTest {
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(
                DeviceButton.BUTTON_A to RemapTarget.Keyboard("ENTER"),
                DeviceButton.BUTTON_B to RemapTarget.Unbound,
            ),
        )

        val stored = dao.getByProfileOnce(1L)
        assertEquals(1, stored.size)
        assertEquals("BUTTON_A", stored[0].gamepadButton)
    }

    @Test
    fun saveMappings_replacesExistingMappings_byDeletingFirst() = runTest {
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(DeviceButton.BUTTON_A to RemapTarget.Keyboard("ENTER")),
        )
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(DeviceButton.BUTTON_B to RemapTarget.Keyboard("BACK")),
        )

        val stored = dao.getByProfileOnce(1L)
        assertEquals(1, stored.size)
        assertEquals("BUTTON_B", stored[0].gamepadButton)
    }

    @Test
    fun saveMappings_allUnbound_clearsProfile() = runTest {
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(DeviceButton.BUTTON_A to RemapTarget.Keyboard("ENTER")),
        )
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(
                DeviceButton.BUTTON_A to RemapTarget.Unbound,
                DeviceButton.BUTTON_B to RemapTarget.Unbound,
            ),
        )

        assertTrue(dao.getByProfileOnce(1L).isEmpty())
    }

    @Test
    fun copyMappings_replicatesSourceIntoDestProfile() = runTest {
        subject.saveMappings(
            profileId = 1L,
            mappings = mapOf(
                DeviceButton.BUTTON_A to RemapTarget.Keyboard("ENTER"),
                DeviceButton.BUTTON_B to RemapTarget.Mouse("LEFT"),
            ),
        )

        subject.copyMappings(sourceProfileId = 1L, destProfileId = 2L)

        val dest = dao.getByProfileOnce(2L).associateBy { it.gamepadButton }
        assertEquals("keyboard:ENTER", dest["BUTTON_A"]?.targetEncoded)
        assertEquals("mouse:LEFT", dest["BUTTON_B"]?.targetEncoded)
    }

    @Test
    fun copyMappings_emptySource_isNoOp() = runTest {
        subject.copyMappings(sourceProfileId = 1L, destProfileId = 2L)
        assertTrue(dao.getByProfileOnce(2L).isEmpty())
    }
}

private class FakeGamepadDao : GamepadMappingDao {
    private val rows = MutableStateFlow<List<GamepadMapping>>(emptyList())

    override fun getByProfile(profileId: Long): Flow<List<GamepadMapping>> =
        rows.map { all -> all.filter { it.profileId == profileId } }

    override suspend fun getByProfileOnce(profileId: Long): List<GamepadMapping> =
        rows.value.filter { it.profileId == profileId }

    override suspend fun insertAll(mappings: List<GamepadMapping>) {
        // Mimic REPLACE-on-conflict via composite key (profileId, gamepadButton).
        val keys = mappings.map { it.profileId to it.gamepadButton }.toSet()
        val kept = rows.value.filterNot { (it.profileId to it.gamepadButton) in keys }
        rows.value = kept + mappings
    }

    override suspend fun deleteAllForProfile(profileId: Long) {
        rows.value = rows.value.filterNot { it.profileId == profileId }
    }
}
