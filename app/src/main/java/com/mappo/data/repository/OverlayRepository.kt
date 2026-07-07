package com.mappo.data.repository

import com.mappo.data.db.OverlayElementDao
import com.mappo.data.model.OverlayElement
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of truth for the rebuilt overlay's free-positioned elements
 * (see `OVERLAY_REBUILD_PLAN.md`). Thin wrapper over [OverlayElementDao]; the active
 * overlay is "all elements for the active profile."
 */
@Singleton
class OverlayRepository @Inject constructor(private val dao: OverlayElementDao) {

    fun elementsByProfile(profileId: Long): Flow<List<OverlayElement>> = dao.getByProfile(profileId)

    /** Set-owned elements (editor set scope / run-mode active-set overlay). */
    fun elementsBySet(actionSetId: Long): Flow<List<OverlayElement>> = dao.getBySet(actionSetId)

    /** Layer-owned elements (editor layer scope). */
    fun elementsByLayer(actionLayerId: Long): Flow<List<OverlayElement>> = dao.getByLayer(actionLayerId)

    suspend fun elementsByProfileOnce(profileId: Long): List<OverlayElement> =
        dao.getByProfileOnce(profileId)

    suspend fun getById(id: Long): OverlayElement? = dao.getById(id)

    /** Insert a new element (or replace one with the same id). Returns the row id. */
    suspend fun add(element: OverlayElement): Long = dao.insert(element)

    /** Insert several elements in ONE transaction (single emission). Returns the new row ids. */
    suspend fun addAll(elements: List<OverlayElement>): List<Long> = dao.insertAll(elements)

    suspend fun update(element: OverlayElement) = dao.update(element)

    /** Atomic batch update — see [OverlayElementDao.update] (single transaction, one emission). */
    suspend fun update(elements: List<OverlayElement>) = dao.update(elements)

    suspend fun delete(element: OverlayElement) = dao.delete(element)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** Restore one scope's elements to [elements] in a single transaction (used by editor undo/redo). */
    suspend fun replaceScopeElements(actionSetId: Long?, actionLayerId: Long?, elements: List<OverlayElement>) =
        dao.replaceScope(actionSetId, actionLayerId, elements)

    suspend fun clearForProfile(profileId: Long) = dao.deleteAllForProfile(profileId)
}
