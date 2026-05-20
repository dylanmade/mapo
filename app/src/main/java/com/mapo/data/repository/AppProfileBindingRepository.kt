package com.mapo.data.repository

import com.mapo.data.db.AppProfileBindingDao
import com.mapo.data.model.AppProfileBinding
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppProfileBindingRepository @Inject constructor(
    private val dao: AppProfileBindingDao
) {

    fun getAll(): Flow<List<AppProfileBinding>> = dao.getAll()

    fun getForPackage(packageName: String): Flow<List<AppProfileBinding>> =
        dao.getForPackage(packageName)

    suspend fun getForPackageOnce(packageName: String, subId: String = ""): AppProfileBinding? =
        dao.getForPackageOnce(packageName, subId)

    suspend fun bind(packageName: String, profileId: Long, subId: String = "") {
        dao.upsert(AppProfileBinding(packageName = packageName, subId = subId, profileId = profileId))
    }

    /**
     * Bind every package in [packageNames] to [profileId] under the default
     * sub-id. REPLACE conflict semantics on the upsert mean re-binding an
     * already-bound package silently re-points it to the new profile, matching
     * how the single-bind path behaves from the create-profile prompt.
     */
    suspend fun bindMany(profileId: Long, packageNames: Collection<String>) {
        for (pkg in packageNames) {
            dao.upsert(AppProfileBinding(packageName = pkg, subId = "", profileId = profileId))
        }
    }

    suspend fun unbind(packageName: String, subId: String = "") {
        dao.delete(packageName, subId)
    }
}
