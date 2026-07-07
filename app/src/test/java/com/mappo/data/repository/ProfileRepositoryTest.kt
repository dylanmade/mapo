package com.mappo.data.repository

import com.mappo.data.db.LayoutDao
import com.mappo.data.db.ProfileDao
import com.mappo.data.model.KeyLayout
import com.mappo.data.model.Profile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Hand-rolled fakes for the DAOs (interfaces). [LayoutRepository] is real,
 * wrapping the fake LayoutDao, so [ProfileRepository.addProfile]'s seedDefaults
 * call exercises the real seeding logic. [ControllerConfigRepository] is mocked
 * since its internals aren't relevant to these tests.
 */
class ProfileRepositoryTest {

    private lateinit var profileDao: FakeProfileDao
    private lateinit var layoutDao: FakeLayoutDao
    private lateinit var layoutRepo: LayoutRepository
    private lateinit var controllerConfigRepo: ControllerConfigRepository
    private lateinit var subject: ProfileRepository

    @Before
    fun setUp() {
        profileDao = FakeProfileDao()
        layoutDao = FakeLayoutDao()
        layoutRepo = LayoutRepository(layoutDao)
        controllerConfigRepo = mockk(relaxed = true)
        coEvery { controllerConfigRepo.copyConfig(any(), any()) } returns Unit

        subject = ProfileRepository(
            profileDao = profileDao,
            layoutDao = layoutDao,
            layoutRepository = layoutRepo,
            controllerConfigRepository = controllerConfigRepo,
        )
    }

    @Test
    fun addProfile_insertsAndSeedsDefaultLayouts() = runTest {
        val id = subject.addProfile("New Profile")

        assertNotNull(profileDao.byId(id))
        val seeded = layoutDao.getByProfileOnce(id)
        assertTrue(
            "addProfile should seed default layouts; found ${seeded.size}",
            seeded.isNotEmpty(),
        )
    }

    @Test
    fun setActiveProfileById_existingId_setsAndReturnsProfile() = runTest {
        val id = subject.addProfile("Test")
        val active = subject.setActiveProfileById(id)
        assertEquals(id, active?.id)
        assertEquals(id, subject.activeProfile.value?.id)
    }

    @Test
    fun setActiveProfileById_missingId_returnsNullAndDoesNotChangeActive() = runTest {
        val id = subject.addProfile("Test")
        subject.setActiveProfileById(id) // sets active

        val result = subject.setActiveProfileById(9999L)

        assertNull(result)
        assertEquals(id, subject.activeProfile.value?.id)
    }

    @Test
    fun setActiveProfile_directly_updatesFlow() = runTest {
        val id = subject.addProfile("Test")
        val profile = profileDao.byId(id)!!
        subject.setActiveProfile(profile)
        assertEquals(profile, subject.activeProfile.value)
    }

    @Test
    fun duplicateProfile_copiesLayoutsFromSource() = runTest {
        val sourceId = subject.addProfile("Source")
        val seededCount = layoutDao.getByProfileOnce(sourceId).size
        val source = profileDao.byId(sourceId)!!

        subject.duplicateProfile(source, "Copy")

        val copyId = profileDao.allOnce().first { it.name == "Copy" }.id
        assertEquals(seededCount, layoutDao.getByProfileOnce(copyId).size)
        coVerify { controllerConfigRepo.copyConfig(sourceProfileId = sourceId, destProfileId = copyId) }
    }

    @Test
    fun duplicateProfile_emptySource_seedsDefaults() = runTest {
        // Profile inserted directly so it has zero layouts (mimics the SQL seed callback path).
        val sourceId = profileDao.insert(Profile(name = "Bare"))
        val source = profileDao.byId(sourceId)!!

        subject.duplicateProfile(source, "FromBare")

        val copyId = profileDao.allOnce().first { it.name == "FromBare" }.id
        assertTrue(
            "empty-source duplicate should fall back to seeding defaults",
            layoutDao.getByProfileOnce(copyId).isNotEmpty(),
        )
    }

    @Test
    fun deleteProfile_default_throws() = runTest {
        val id = profileDao.insert(Profile(name = "Default", isDefault = true))
        val profile = profileDao.byId(id)!!

        try {
            subject.deleteProfile(profile)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cannot delete the default profile", e.message)
        }
    }

    @Test
    fun deleteProfile_nonDefault_removes() = runTest {
        val id = subject.addProfile("Removable")
        val profile = profileDao.byId(id)!!
        subject.deleteProfile(profile)
        assertNull(profileDao.byId(id))
    }
}

private class FakeProfileDao : ProfileDao {
    private val rows = MutableStateFlow<Map<Long, Profile>>(emptyMap())
    private var nextId = 1L

    fun byId(id: Long): Profile? = rows.value[id]
    fun allOnce(): List<Profile> = rows.value.values.toList()

    override fun getAll(): Flow<List<Profile>> =
        rows.map { it.values.sortedBy(Profile::name) }

    override fun getDefault(): Flow<Profile?> =
        rows.map { it.values.firstOrNull(Profile::isDefault) }

    override suspend fun getById(id: Long): Profile? = rows.value[id]

    override suspend fun insert(profile: Profile): Long {
        val assigned = if (profile.id == 0L) nextId++ else profile.id
        rows.value = rows.value + (assigned to profile.copy(id = assigned))
        return assigned
    }

    override suspend fun delete(profile: Profile) {
        rows.value = rows.value - profile.id
    }
}

private class FakeLayoutDao : LayoutDao {
    private val rows = MutableStateFlow<Map<Long, KeyLayout>>(emptyMap())
    private var nextId = 1L

    override fun getByProfile(profileId: Long): Flow<List<KeyLayout>> =
        rows.map { it.values.filter { row -> row.profileId == profileId } }

    override suspend fun getByProfileOnce(profileId: Long): List<KeyLayout> =
        rows.value.values.filter { it.profileId == profileId }
            .sortedWith(compareBy({ it.position }, { it.id }))

    override suspend fun getById(id: Long): KeyLayout? = rows.value[id]

    override suspend fun insert(layout: KeyLayout): Long {
        val assigned = if (layout.id == 0L) nextId++ else layout.id
        rows.value = rows.value + (assigned to layout.copy(id = assigned))
        return assigned
    }

    override suspend fun update(layout: KeyLayout) {
        rows.value = rows.value + (layout.id to layout)
    }

    override suspend fun updateAll(layouts: List<KeyLayout>) {
        rows.value = rows.value + layouts.associateBy { it.id }
    }

    override suspend fun delete(layout: KeyLayout) {
        rows.value = rows.value - layout.id
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value - id
    }
}
