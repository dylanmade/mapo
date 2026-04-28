package com.mapo.data.repository

import com.mapo.data.db.LayoutDao
import com.mapo.data.model.KeyLayout
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(private val dao: LayoutDao) {

    fun getLayoutsByProfile(profileId: Long): Flow<List<KeyLayout>> = dao.getByProfile(profileId)

    suspend fun saveLayout(layout: KeyLayout) = dao.insert(layout)

    suspend fun deleteLayout(layout: KeyLayout) = dao.delete(layout)
}
