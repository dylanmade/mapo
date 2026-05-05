package com.mapo.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapo.data.model.KeyboardTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardTemplateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: KeyboardTemplateDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.keyboardTemplateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun template(name: String) = KeyboardTemplate(
        name = name,
        columns = 4,
        rows = 3,
        buttonsJson = "[]",
    )

    @Test
    fun getAll_returnsAlphabeticallyOrdered() = runTest {
        dao.insert(template("Zeta"))
        dao.insert(template("Alpha"))
        dao.insert(template("Mu"))

        val names = dao.getAll().first().map { it.name }
        assertEquals(listOf("Alpha", "Mu", "Zeta"), names)
    }

    @Test
    fun getByNameOnce_findsMatchingTemplate() = runTest {
        dao.insert(template("Foo"))
        val found = dao.getByNameOnce("Foo")
        assertEquals("Foo", found?.name)
    }

    @Test
    fun getByNameOnce_missingName_returnsNull() = runTest {
        assertNull(dao.getByNameOnce("Nope"))
    }

    @Test
    fun insertingDuplicateName_throws_dueToUniqueIndex() = runTest {
        dao.insert(template("Foo"))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { dao.insert(template("Foo")) }
        }
    }

    @Test
    fun update_persistsChanges() = runTest {
        val id = dao.insert(template("Foo"))
        val current = dao.getByNameOnce("Foo")!!.copy(id = id, columns = 5)
        dao.update(current)
        assertEquals(5, dao.getByNameOnce("Foo")?.columns)
    }

    @Test
    fun delete_removesTemplate() = runTest {
        val id = dao.insert(template("ToDelete"))
        val current = dao.getByNameOnce("ToDelete")!!.copy(id = id)
        dao.delete(current)
        assertNull(dao.getByNameOnce("ToDelete"))
    }
}
