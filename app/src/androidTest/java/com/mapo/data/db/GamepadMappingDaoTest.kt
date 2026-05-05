package com.mapo.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapo.data.model.GamepadMapping
import com.mapo.data.model.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamepadMappingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: GamepadMappingDao
    private lateinit var profileDao: ProfileDao
    private var profileId: Long = 0L

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.gamepadMappingDao()
        profileDao = db.profileDao()
        profileId = profileDao.insert(Profile(name = "Test"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAll_persistsAndOverwritesByCompositeKey() = runTest {
        dao.insertAll(
            listOf(
                GamepadMapping(profileId, "BUTTON_A", "key:ENTER"),
                GamepadMapping(profileId, "BUTTON_B", "key:BACK"),
            ),
        )
        // Re-insert BUTTON_A → should overwrite (REPLACE on conflict).
        dao.insertAll(listOf(GamepadMapping(profileId, "BUTTON_A", "key:SPACE")))

        val all = dao.getByProfileOnce(profileId).associateBy { it.gamepadButton }
        assertEquals("key:SPACE", all["BUTTON_A"]?.targetEncoded)
        assertEquals("key:BACK", all["BUTTON_B"]?.targetEncoded)
    }

    @Test
    fun getByProfile_emitsCurrent() = runTest {
        dao.insertAll(listOf(GamepadMapping(profileId, "BUTTON_X", "key:X")))
        assertEquals(1, dao.getByProfile(profileId).first().size)
    }

    @Test
    fun deleteAllForProfile_removesOnlyThatProfilesMappings() = runTest {
        val otherId = profileDao.insert(Profile(name = "Other"))
        dao.insertAll(listOf(GamepadMapping(profileId, "BUTTON_A", "key:A")))
        dao.insertAll(listOf(GamepadMapping(otherId, "BUTTON_A", "key:Z")))

        dao.deleteAllForProfile(profileId)

        assertEquals(0, dao.getByProfileOnce(profileId).size)
        assertEquals(1, dao.getByProfileOnce(otherId).size)
    }

    @Test
    fun deletingProfile_cascadesToMappings() = runTest {
        dao.insertAll(listOf(GamepadMapping(profileId, "BUTTON_A", "key:A")))
        profileDao.delete(Profile(id = profileId, name = "Test"))
        assertEquals(0, dao.getByProfileOnce(profileId).size)
    }
}
