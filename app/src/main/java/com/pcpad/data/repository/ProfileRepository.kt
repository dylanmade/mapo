package com.pcpad.data.repository

import com.pcpad.data.db.LayoutDao
import com.pcpad.data.db.ProfileDao
import com.pcpad.data.defaults.DefaultLayouts
import com.pcpad.data.model.Profile
import com.pcpad.data.model.toKeyLayout
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val layoutDao: LayoutDao
) {

    fun getAllProfiles(): Flow<List<Profile>> = profileDao.getAll()

    fun getDefaultProfile(): Flow<Profile?> = profileDao.getDefault()

    suspend fun addProfile(name: String) {
        val newId = profileDao.insert(Profile(name = name))
        DefaultLayouts.all.forEach { layout ->
            layoutDao.insert(layout.toKeyLayout(newId))
        }
    }

    suspend fun duplicateProfile(source: Profile, newName: String) {
        val newId = profileDao.insert(Profile(name = newName))
        val layoutsToCopy = if (source.isDefault) {
            val persisted = layoutDao.getByProfileOnce(source.id)
            val overrideMap = persisted.associateBy { it.name }
            DefaultLayouts.all.map { defaultLayout ->
                overrideMap[defaultLayout.name]?.copy(id = 0, profileId = newId)
                    ?: defaultLayout.toKeyLayout(newId)
            }
        } else {
            layoutDao.getByProfileOnce(source.id).map { it.copy(id = 0, profileId = newId) }
        }
        layoutsToCopy.forEach { layoutDao.insert(it) }
    }

    suspend fun deleteProfile(profile: Profile) {
        require(!profile.isDefault) { "Cannot delete the default profile" }
        profileDao.delete(profile)
    }
}
