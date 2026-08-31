package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.database.dao.PlaybackHistoryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.ResumePolicyRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Profile-scoped opt-out from following Audiobookshelf activity on other clients. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultResumePolicyRepository @Inject constructor(
    private val profiles: ProfileRepository,
    private val profileDao: ProfileDao,
    private val history: PlaybackHistoryDao,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : ResumePolicyRepository {

    override fun observeCrossDeviceResumeEnabled(): Flow<Boolean> = profiles.observeActiveProfile()
        .flatMapLatest { profile ->
            if (profile == null) {
                flowOf(true)
            } else {
                history.observeInternal(profile.id.value, disabledMarkerId(profile.id)).map { marker -> marker == null }
            }
        }

    override suspend fun isCrossDeviceResumeEnabled(): Boolean = withContext(ioDispatcher) {
        val profileId = profiles.activeProfileId() ?: return@withContext true
        history.findInternal(profileId.value, disabledMarkerId(profileId)) == null
    }

    override suspend fun setCrossDeviceResumeEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        val profileId = profiles.activeProfileId() ?: return@withContext
        val markerId = disabledMarkerId(profileId)
        if (enabled) {
            history.deleteInternal(profileId.value, markerId)
            return@withContext
        }
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext
        history.insert(
            PlaybackHistoryEntity(
                entryId = markerId,
                profileId = profileId.value,
                bookKey = EntityKey.of(profile.serverId, POLICY_ENTITY_ID),
                fromMillis = null,
                toMillis = 0,
                reason = PlaybackEvent.ServerSession.name,
                detailMillis = null,
                at = 0,
            ),
        )
    }

    private companion object {
        const val POLICY_ENTITY_ID = "cross-device-resume-policy"
        const val DISABLED_PREFIX = "resume-cache:policy-disabled:"

        fun disabledMarkerId(profileId: ProfileId): String = DISABLED_PREFIX + profileId.value
    }
}
