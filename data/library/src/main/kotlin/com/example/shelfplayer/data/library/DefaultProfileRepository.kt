package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.data.library.mapper.EntityMappers
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-002 — profiles live in Room, the *selection* lives in Proto DataStore.
 *
 * Splitting them is deliberate. The profile list is server-derived data that a sync rewrites; the
 * active selection is a user setting that must survive a database rebuild and must be readable
 * before Room opens, which is what the playback service needs on a cold start (PRODUCT_SPEC 9.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val settings: AppSettingsDataSource,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun observeProfiles(): Flow<List<Profile>> =
        profileDao.observeProfiles().map { entities -> entities.map(EntityMappers::toDomain) }

    override fun observeServers(): Flow<List<Server>> =
        profileDao.observeServers().map { entities -> entities.map(EntityMappers::toDomain) }

    override fun observeActiveProfile(): Flow<Profile?> = settings.activeProfileId.flatMapLatest { profileId ->
        if (profileId == null) {
            flowOf(null)
        } else {
            profileDao.observeProfile(profileId.value).map { entity ->
                entity?.let(EntityMappers::toDomain)
            }
        }
    }

    override suspend fun activeProfileId(): ProfileId? = settings.activeProfileId.first()

    /**
     * PRODUCT_SPEC 6.5 — switching to a profile that is not stored locally is refused.
     *
     * Writing the id first and discovering afterwards that no such profile exists would leave the
     * app in a state where every screen is empty and no error explains why.
     */
    override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = withContext(ioDispatcher) {
        val stored = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(
                AppError.Validation(summary = "That profile is no longer saved on this device."),
            )
        settings.setActiveProfile(ProfileId(stored.profileId))
        AppResult.Success(Unit)
    }
}
