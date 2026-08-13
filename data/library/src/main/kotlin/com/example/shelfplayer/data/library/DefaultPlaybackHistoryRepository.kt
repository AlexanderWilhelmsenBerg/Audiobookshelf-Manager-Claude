package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.PlaybackHistoryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackJump
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-003 / 5.2 — a book's jumps, scoped to the profile that made them.
 *
 * Profile-scoped for the same reason progress is: two people on one server share a library and do not share
 * where they have been in it. The foreign key cascades, so removing a profile removes its history with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultPlaybackHistoryRepository @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileDao: ProfileDao,
    private val history: PlaybackHistoryDao,
    private val clock: AppClock,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackHistoryRepository {

    /**
     * Re-subscribed when the active profile changes, so switching profile switches whose history is shown
     * rather than leaving the previous one's on screen (PRODUCT_SPEC 5.2).
     */
    override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                history.observe(profile.id.value, EntityKey.of(profile.serverId.value, bookId.value), limit)
                    .map { rows -> rows.map(PlaybackHistoryEntity::toDomain) }
            }
        }

    override suspend fun record(bookId: LibraryItemId, jump: PlaybackJump, from: Duration?, to: Duration) =
        withContext(ioDispatcher) {
            val profileId = profileRepository.activeProfileId() ?: return@withContext
            val profile = profileDao.findProfile(profileId.value) ?: return@withContext
            history.record(
                entry = PlaybackHistoryEntity(
                    entryId = UUID.randomUUID().toString(),
                    profileId = profileId.value,
                    bookKey = EntityKey.of(profile.serverId, bookId.value),
                    fromMillis = from?.inWholeMilliseconds?.coerceAtLeast(0),
                    toMillis = to.inWholeMilliseconds.coerceAtLeast(0),
                    reason = jump.name,
                    at = clock.now().toEpochMilli(),
                ),
                keep = PlaybackHistoryRepository.DEFAULT_LIMIT,
            )
        }

    override suspend fun clear(bookId: LibraryItemId) = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId() ?: return@withContext
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext
        history.clear(profileId.value, EntityKey.of(profile.serverId, bookId.value))
    }
}

private fun PlaybackHistoryEntity.toDomain() = PlaybackHistoryEntry(
    id = entryId,
    jump = PlaybackJump.parse(reason),
    from = fromMillis?.milliseconds,
    to = toMillis.milliseconds,
    at = Instant.ofEpochMilli(at),
)
