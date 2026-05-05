package com.mapo.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room/DAO instrumented smoke check. Builds an in-memory [AppDatabase] and
 * round-trips an [AppProfileBinding] through [AppProfileBindingDao] to confirm
 * the schema, FK to [Profile], and the test harness all work end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class AppProfileBindingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var profileDao: ProfileDao
    private lateinit var bindingDao: AppProfileBindingDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profileDao = db.profileDao()
        bindingDao = db.appProfileBindingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAndQuery_roundTrip() = runTest {
        val profileId = profileDao.insert(Profile(name = "Test Profile"))
        val binding = AppProfileBinding(packageName = "com.example.app", profileId = profileId)

        bindingDao.upsert(binding)

        val fetched = bindingDao.getForPackageOnce("com.example.app")
        assertEquals(binding, fetched)
    }

    @Test
    fun delete_removesBinding() = runTest {
        val profileId = profileDao.insert(Profile(name = "Test Profile"))
        val binding = AppProfileBinding(packageName = "com.example.app", profileId = profileId)
        bindingDao.upsert(binding)

        bindingDao.delete("com.example.app")

        assertNull(bindingDao.getForPackageOnce("com.example.app"))
    }

    @Test
    fun upsert_replacesBindingForSamePackageAndSubId() = runTest {
        val profileA = profileDao.insert(Profile(name = "A"))
        val profileB = profileDao.insert(Profile(name = "B"))

        bindingDao.upsert(AppProfileBinding(packageName = "com.example.app", profileId = profileA))
        bindingDao.upsert(AppProfileBinding(packageName = "com.example.app", profileId = profileB))

        assertEquals(profileB, bindingDao.getForPackageOnce("com.example.app")?.profileId)
    }

    @Test
    fun deleteAllForProfile_removesOnlyThatProfilesBindings() = runTest {
        val profileA = profileDao.insert(Profile(name = "A"))
        val profileB = profileDao.insert(Profile(name = "B"))
        bindingDao.upsert(AppProfileBinding(packageName = "com.alpha", profileId = profileA))
        bindingDao.upsert(AppProfileBinding(packageName = "com.beta", profileId = profileB))

        bindingDao.deleteAllForProfile(profileA)

        assertNull(bindingDao.getForPackageOnce("com.alpha"))
        assertEquals(profileB, bindingDao.getForPackageOnce("com.beta")?.profileId)
    }

    @Test
    fun deletingProfile_cascadesToBindings() = runTest {
        val profileId = profileDao.insert(Profile(name = "Test"))
        bindingDao.upsert(AppProfileBinding(packageName = "com.example.app", profileId = profileId))

        profileDao.delete(Profile(id = profileId, name = "Test"))

        assertNull(bindingDao.getForPackageOnce("com.example.app"))
    }
}
