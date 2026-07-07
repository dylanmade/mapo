package com.mappo.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mappo.data.model.KeyLayout
import com.mappo.data.model.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayoutDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LayoutDao
    private lateinit var profileDao: ProfileDao
    private var profileId: Long = 0L

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.layoutDao()
        profileDao = db.profileDao()
        profileId = profileDao.insert(Profile(name = "Test"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun layout(name: String, position: Int = 0) = KeyLayout(
        profileId = profileId,
        name = name,
        columns = 3,
        rows = 2,
        buttonsJson = "[]",
        position = position,
    )

    @Test
    fun insert_assignsAutoId() = runTest {
        val id = dao.insert(layout("Main"))
        assertTrue(id > 0)
    }

    @Test
    fun getByProfile_ordersByPositionThenId() = runTest {
        val firstId = dao.insert(layout("First", position = 0))
        val secondId = dao.insert(layout("Second", position = 1))
        val thirdId = dao.insert(layout("Third", position = 1))

        val ids = dao.getByProfile(profileId).first().map { it.id }
        assertEquals(listOf(firstId, secondId, thirdId), ids)
    }

    @Test
    fun reorder_updatesOnlyChangedPositions() = runTest {
        val a = dao.insert(layout("A", position = 0))
        val b = dao.insert(layout("B", position = 1))
        val c = dao.insert(layout("C", position = 2))

        dao.reorder(
            profileId = profileId,
            idToPosition = mapOf(a to 2, b to 0, c to 1),
        )

        val ordered = dao.getByProfile(profileId).first().map { it.id }
        assertEquals(listOf(b, c, a), ordered)
    }

    @Test
    fun reorder_skipsRowsWithUnchangedPosition() = runTest {
        val a = dao.insert(layout("A", position = 0))
        val b = dao.insert(layout("B", position = 1))

        // Map sets a→0 (unchanged) and b→1 (unchanged); should be a no-op.
        dao.reorder(
            profileId = profileId,
            idToPosition = mapOf(a to 0, b to 1),
        )

        val ids = dao.getByProfile(profileId).first().map { it.id }
        assertEquals(listOf(a, b), ids)
    }

    @Test
    fun deleteById_removesRow() = runTest {
        val id = dao.insert(layout("X"))
        dao.deleteById(id)
        assertEquals(emptyList<KeyLayout>(), dao.getByProfile(profileId).first())
    }

    @Test
    fun update_persistsChanges() = runTest {
        val id = dao.insert(layout("Old"))
        val current = dao.getById(id)!!
        dao.update(current.copy(name = "New"))
        assertEquals("New", dao.getById(id)!!.name)
    }

    @Test
    fun deletingProfile_cascadesToLayouts() = runTest {
        dao.insert(layout("L1"))
        dao.insert(layout("L2"))
        assertEquals(2, dao.getByProfileOnce(profileId).size)

        profileDao.delete(Profile(id = profileId, name = "Test"))
        assertEquals(0, dao.getByProfileOnce(profileId).size)
    }
}
