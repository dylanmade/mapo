package com.mapo.data.repository

import com.mapo.data.db.LayoutDao
import com.mapo.data.model.KeyLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LayoutRepositoryTest {

    private lateinit var dao: FakeLayoutDaoForRepoTest
    private lateinit var subject: LayoutRepository

    @Before
    fun setUp() {
        dao = FakeLayoutDaoForRepoTest()
        subject = LayoutRepository(dao)
    }

    @Test
    fun seedDefaults_insertsAllDefaultLayouts() = runTest {
        subject.seedDefaults(profileId = 1L)
        val seeded = dao.getByProfileOnce(1L)
        assertTrue(
            "DefaultLayouts.all should produce at least one default layout",
            seeded.isNotEmpty(),
        )
    }

    @Test
    fun seedDefaults_assignsSequentialPositions() = runTest {
        subject.seedDefaults(profileId = 1L)
        val seeded = dao.getByProfileOnce(1L)
        val positions = seeded.map { it.position }
        assertEquals(positions.indices.toList(), positions)
    }

    @Test
    fun seedDefaults_populatesOriginalSnapshotJson() = runTest {
        subject.seedDefaults(profileId = 1L)
        val seeded = dao.getByProfileOnce(1L)
        seeded.forEach { layout ->
            assertNotNull(
                "seeded layout '${layout.name}' should carry an originalSnapshotJson",
                layout.originalSnapshotJson,
            )
        }
    }

    @Test
    fun seedDefaultsIfEmpty_noOp_whenLayoutsAlreadyPresent() = runTest {
        subject.seedDefaults(profileId = 1L)
        val countAfterFirstSeed = dao.getByProfileOnce(1L).size

        subject.seedDefaultsIfEmpty(profileId = 1L)

        assertEquals(countAfterFirstSeed, dao.getByProfileOnce(1L).size)
    }

    @Test
    fun seedDefaultsIfEmpty_seedsWhenEmpty() = runTest {
        assertTrue(dao.getByProfileOnce(1L).isEmpty())
        subject.seedDefaultsIfEmpty(profileId = 1L)
        assertTrue(dao.getByProfileOnce(1L).isNotEmpty())
    }

    @Test
    fun reorder_delegatesToDao() = runTest {
        subject.seedDefaults(profileId = 1L)
        val seeded = dao.getByProfileOnce(1L)
        val reverseMap = seeded.mapIndexed { i, layout ->
            layout.id to (seeded.size - 1 - i)
        }.toMap()

        subject.reorder(profileId = 1L, idToPosition = reverseMap)

        val reordered = dao.getByProfileOnce(1L)
        assertEquals(seeded.map { it.id }.reversed(), reordered.map { it.id })
    }
}

private class FakeLayoutDaoForRepoTest : LayoutDao {
    private val rows = MutableStateFlow<Map<Long, KeyLayout>>(emptyMap())
    private var nextId = 1L

    override fun getByProfile(profileId: Long): Flow<List<KeyLayout>> =
        rows.map { all -> all.values.filter { it.profileId == profileId } }

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
