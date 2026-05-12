package com.mapo.data.repository

import com.mapo.data.db.LayoutDao
import com.mapo.data.db.ProfileDao
import com.mapo.data.model.Profile
import com.mapo.data.model.toGridLayout
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.toJson
import com.mapo.data.model.toSnapshot
import com.mapo.data.model.withFreshButtonIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val layoutDao: LayoutDao,
    private val layoutRepository: LayoutRepository,
    private val controllerConfigRepository: ControllerConfigRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    init {
        scope.launch {
            profileDao.getDefault().collect { profile ->
                if (_activeProfile.value == null && profile != null) {
                    _activeProfile.value = profile
                }
            }
        }
    }

    fun getAllProfiles(): Flow<List<Profile>> = profileDao.getAll()

    fun getDefaultProfile(): Flow<Profile?> = profileDao.getDefault()

    fun setActiveProfile(profile: Profile) {
        _activeProfile.value = profile
    }

    suspend fun setActiveProfileById(id: Long): Profile? {
        val profile = profileDao.getById(id) ?: return null
        _activeProfile.value = profile
        return profile
    }

    suspend fun addProfile(name: String): Long {
        val newId = profileDao.insert(Profile(name = name))
        layoutRepository.seedDefaults(newId)
        return newId
    }

    suspend fun duplicateProfile(source: Profile, newName: String) {
        val newId = profileDao.insert(Profile(name = newName))
        val sourceLayouts = layoutDao.getByProfileOnce(source.id)
        if (sourceLayouts.isEmpty()) {
            // The Default profile may have been created via the SQL seed callback before any
            // layouts were persisted; fall back to seeding so the duplicate isn't blank.
            layoutRepository.seedDefaults(newId)
        } else {
            sourceLayouts.forEach { layout ->
                // Fresh UUIDs per button so the duplicated profile's keyboards don't share
                // button ids with the source profile's keyboards. The reset-to-original
                // snapshot is regenerated from the fresh-id state to match.
                val fresh = layout.toGridLayout().withFreshButtonIds()
                val snapshotJson = fresh.toSnapshot().toJson()
                layoutDao.insert(
                    fresh.toKeyLayout(
                        profileId = newId,
                        position = layout.position,
                        originalSnapshotJson = snapshotJson
                    )
                )
            }
        }
        controllerConfigRepository.copyConfig(source.id, newId)
    }

    suspend fun deleteProfile(profile: Profile) {
        require(!profile.isDefault) { "Cannot delete the default profile" }
        profileDao.delete(profile)
    }
}
