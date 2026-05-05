package com.mapo.data.repository

import com.mapo.data.db.AppProfileBindingDao
import com.mapo.data.model.AppProfileBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Repository is a thin wrapper over the DAO; tests use a hand-rolled fake DAO
 * (the DAO is an interface, so no MockK or Robolectric needed).
 */
class AppProfileBindingRepositoryTest {

    private lateinit var dao: FakeBindingDao
    private lateinit var subject: AppProfileBindingRepository

    @Before
    fun setUp() {
        dao = FakeBindingDao()
        subject = AppProfileBindingRepository(dao)
    }

    @Test
    fun bind_createsNewBinding() = runTest {
        subject.bind(packageName = "com.example", profileId = 1L)
        val stored = subject.getForPackageOnce("com.example")
        assertEquals(AppProfileBinding(packageName = "com.example", profileId = 1L), stored)
    }

    @Test
    fun bind_replacesExistingBindingForSamePackageAndSubId() = runTest {
        subject.bind(packageName = "com.example", profileId = 1L)
        subject.bind(packageName = "com.example", profileId = 2L)
        assertEquals(2L, subject.getForPackageOnce("com.example")?.profileId)
    }

    @Test
    fun bind_keepsBindingsForDifferentSubIdsSeparate() = runTest {
        subject.bind(packageName = "com.example", profileId = 1L, subId = "")
        subject.bind(packageName = "com.example", profileId = 2L, subId = "guest")

        assertEquals(1L, subject.getForPackageOnce("com.example", subId = "")?.profileId)
        assertEquals(2L, subject.getForPackageOnce("com.example", subId = "guest")?.profileId)
    }

    @Test
    fun unbind_removesBinding() = runTest {
        subject.bind(packageName = "com.example", profileId = 1L)
        subject.unbind(packageName = "com.example")
        assertNull(subject.getForPackageOnce("com.example"))
    }

    @Test
    fun unbind_missingPackage_isNoOp() = runTest {
        subject.unbind(packageName = "com.example")
        assertNull(subject.getForPackageOnce("com.example"))
    }

    @Test
    fun getAll_emitsCurrentSnapshot() = runTest {
        subject.bind(packageName = "com.alpha", profileId = 1L)
        subject.bind(packageName = "com.beta", profileId = 2L)

        val all = subject.getAll().first()
        assertEquals(
            setOf(
                AppProfileBinding(packageName = "com.alpha", profileId = 1L),
                AppProfileBinding(packageName = "com.beta", profileId = 2L),
            ),
            all.toSet(),
        )
    }

    @Test
    fun getForPackage_filtersToPackage() = runTest {
        subject.bind(packageName = "com.alpha", profileId = 1L, subId = "")
        subject.bind(packageName = "com.alpha", profileId = 2L, subId = "guest")
        subject.bind(packageName = "com.beta", profileId = 3L)

        val alphaBindings = subject.getForPackage("com.alpha").first()
        assertEquals(
            setOf(
                AppProfileBinding(packageName = "com.alpha", profileId = 1L, subId = ""),
                AppProfileBinding(packageName = "com.alpha", profileId = 2L, subId = "guest"),
            ),
            alphaBindings.toSet(),
        )
    }
}

/**
 * In-memory implementation of [AppProfileBindingDao] keyed by (packageName, subId)
 * — mirrors the Room schema's primary key. Backs Flow-returning queries with a
 * StateFlow so collectors see updates after upsert/delete.
 */
private class FakeBindingDao : AppProfileBindingDao {
    private val rows = MutableStateFlow<Map<Pair<String, String>, AppProfileBinding>>(emptyMap())

    override fun getAll(): Flow<List<AppProfileBinding>> =
        rows.map { it.values.sortedBy { row -> row.packageName } }

    override fun getForPackage(packageName: String): Flow<List<AppProfileBinding>> =
        rows.map { it.values.filter { row -> row.packageName == packageName } }

    override suspend fun getForPackageOnce(
        packageName: String,
        subId: String,
    ): AppProfileBinding? = rows.value[packageName to subId]

    override suspend fun upsert(binding: AppProfileBinding) {
        rows.value = rows.value + ((binding.packageName to binding.subId) to binding)
    }

    override suspend fun delete(packageName: String, subId: String) {
        rows.value = rows.value - (packageName to subId)
    }

    override suspend fun deleteAllForProfile(profileId: Long) {
        rows.value = rows.value.filterValues { it.profileId != profileId }
    }
}
