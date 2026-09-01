package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.PlaybackHistoryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
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

/** PRODUCT_SPEC PLAY-003 / 5.2 — a book's events, scoped to the profile they happened to. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultPlaybackHistoryRepository @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileDao: ProfileDao,
    private val history: PlaybackHistoryDao,
    private val clock: AppClock,
    private val gateway: AudiobookshelfGateway,
    private val device: PlaybackDeviceIdentity,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackHistoryRepository {

    override fun observe(bookId: LibraryItemId, limit: Int): Flow<List<PlaybackHistoryEntry>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                history.observe(profile.id.value, EntityKey.of(profile.serverId.value, bookId.value), limit)
                    .map { rows -> rows.map(PlaybackHistoryEntity::toDomain) }
            }
        }

    override suspend fun record(
        bookId: LibraryItemId,
        event: PlaybackEvent,
        from: Duration?,
        to: Duration,
        detail: Duration?,
        at: Instant?,
        owner: ProfileId?,
    ) = withContext(ioDispatcher) {
        val profileId = owner ?: profileRepository.activeProfileId() ?: return@withContext
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext
        history.record(
            entry = PlaybackHistoryEntity(
                entryId = UUID.randomUUID().toString(),
                profileId = profileId.value,
                bookKey = EntityKey.of(profile.serverId, bookId.value),
                fromMillis = from?.inWholeMilliseconds?.coerceAtLeast(0),
                toMillis = to.inWholeMilliseconds.coerceAtLeast(0),
                reason = event.name,
                detailMillis = detail?.inWholeMilliseconds?.coerceAtLeast(0),
                at = (at ?: clock.now()).toEpochMilli(),
            ),
            keep = PlaybackHistoryRepository.DEFAULT_LIMIT,
        )
    }

    override suspend fun hasNewerExternalSession(
        bookId: LibraryItemId,
        currentSessionId: String,
    ): Boolean = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId() ?: return@withContext false
        val fetched = gateway.playback.listeningSessions(profileId)
        val sessions = (fetched as? AppResult.Success)?.value ?: return@withContext false
        val current = sessions.firstOrNull { it.id == currentSessionId } ?: return@withContext false
        val thisDevice = device.describe().deviceId
        sessions.any { candidate ->
            candidate.bookId == bookId &&
                candidate.id != currentSessionId &&
                candidate.deviceId != thisDevice &&
                candidate.listened > Duration.ZERO &&
                candidate.updatedAt > current.updatedAt
        }
    }

    override suspend fun refreshServerSessions(bookId: LibraryItemId) = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId() ?: return@withContext
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext
        val fetched = gateway.playback.listeningSessions(profileId)
        val sessions = when (fetched) {
            is AppResult.Failure -> {
                logger.debug(
                    LogCategory.Sync,
                    "Could not read the server's listening sessions; the stored history stands",
                    LogField.Public("error", fetched.error::class.simpleName ?: "unknown"),
                )
                return@withContext
            }
            is AppResult.Success -> fetched.value
        }
        val thisDevice = device.describe().deviceId
        val relevant = sessions.filter { session ->
            session.bookId == bookId &&
                session.deviceId != thisDevice &&
                session.listened > Duration.ZERO
        }
        logger.debug(
            LogCategory.Sync,
            "Imported the server's sessions for a book",
            LogField.Count("fetched", sessions.size),
            LogField.Count("imported", relevant.size),
        )
        val bookKey = EntityKey.of(profile.serverId, bookId.value)
        relevant.forEach { session ->
            history.record(
                entry = PlaybackHistoryEntity(
                    entryId = SERVER_SESSION_PREFIX + session.id,
                    profileId = profileId.value,
                    bookKey = bookKey,
                    fromMillis = session.startedFrom.inWholeMilliseconds.coerceAtLeast(0),
                    toMillis = session.reachedAt.inWholeMilliseconds.coerceAtLeast(0),
                    reason = PlaybackEvent.ServerSession.name,
                    detailMillis = session.listened.inWholeMilliseconds.coerceAtLeast(0),
                    at = session.startedAt.toEpochMilli(),
                ),
                keep = PlaybackHistoryRepository.DEFAULT_LIMIT,
            )
        }
    }

    override suspend fun clear(bookId: LibraryItemId) = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId() ?: return@withContext
        val profile = profileDao.findProfile(profileId.value) ?: return@withContext
        history.clear(profileId.value, EntityKey.of(profile.serverId, bookId.value))
    }
}

private const val SERVER_SESSION_PREFIX = "abs-session:"

private fun PlaybackHistoryEntity.toDomain() = PlaybackHistoryEntry(
    id = entryId,
    event = PlaybackEvent.parse(reason),
    from = fromMillis?.milliseconds,
    to = toMillis.milliseconds,
    detail = detailMillis?.milliseconds,
    at = Instant.ofEpochMilli(at),
)
