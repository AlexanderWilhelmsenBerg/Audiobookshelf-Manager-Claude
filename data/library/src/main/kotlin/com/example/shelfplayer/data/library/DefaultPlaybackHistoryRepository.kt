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

/**
 * PRODUCT_SPEC PLAY-003 / 5.2 — a book's events, scoped to the profile they happened to.
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
    /** PRODUCT_SPEC PLAY-003 — the server's own session records. See [refreshServerSessions]. */
    private val gateway: AudiobookshelfGateway,
    /**
     * PRODUCT_SPEC PLAY-003 — how this install names itself to the server when it opens a session.
     *
     * Read here so a session the server attributes to *this* phone can be told from one another device
     * recorded, which is the difference between adding history and duplicating it.
     */
    private val device: PlaybackDeviceIdentity,
    private val logger: Logger,
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

    override suspend fun record(
        bookId: LibraryItemId,
        event: PlaybackEvent,
        from: Duration?,
        to: Duration,
        detail: Duration?,
        at: Instant?,
        owner: ProfileId?,
    ) = withContext(ioDispatcher) {
        // PRODUCT_SPEC 6.5 — the account the event belongs to, named by the player from the loaded book's
        // own extras. Falling back to the active profile only when the caller has no session to name.
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
                // The caller's moment when it has one — a change the server made happened when the server
                // says it did, not when the refresh that found it ran.
                at = (at ?: clock.now()).toEpochMilli(),
            ),
            keep = PlaybackHistoryRepository.DEFAULT_LIMIT,
        )
    }

    /**
     * Whether another device has newer activity for this book than BookWave's last local pause.
     *
     * The endpoint is account-wide, so the same book and another device are explicit filters. `updatedAt`
     * rather than `startedAt` matters because a long-lived session may have started yesterday and still be
     * the newest thing touching the book today. Failure is false: uncertainty must preserve the loaded local
     * position, never make a stale server read authoritative.
     */
    override suspend fun hasNewerExternalSession(
        bookId: LibraryItemId,
        after: Instant,
    ): Boolean = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId() ?: return@withContext false
        val fetched = gateway.playback.listeningSessions(profileId)
        val sessions = (fetched as? AppResult.Success)?.value ?: return@withContext false
        val thisDevice = device.describe().deviceId
        sessions.any { candidate ->
            candidate.bookId == bookId &&
                candidate.deviceId != thisDevice &&
                candidate.listened > Duration.ZERO &&
                candidate.updatedAt > after
        }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — imports the server's own session records for one book.
     *
     * ### Idempotent without a migration, and that is the point of the id
     *
     * The row's `entryId` is derived from the **session's** id rather than a fresh `UUID`, so a session
     * fetched on two consecutive refreshes is one row twice, not two rows — `OnConflictStrategy.REPLACE`
     * does the rest. The existing schema already had a `String` primary key, so persisting these needed no
     * Room migration at all.
     *
     * [SERVER_SESSION_PREFIX] keeps the derived ids in their own namespace: a locally recorded event uses a
     * random UUID, and a server session id colliding with one is not a risk worth leaving to chance.
     *
     * ### The three filters
     *
     * **This book only**, because the endpoint is account-wide. **Other devices only** — this phone's own
     * sessions come back too and would duplicate the player's `Play` and `Pause` rows. **Something actually
     * listened**, because opening a book and closing it leaves a zero-second session on the server, and a
     * history row saying somebody listened for no time is noise.
     *
     * A session with no device id counts as *another* device: the alternative is dropping a real session
     * from another client that did not identify itself, and a duplicate row is a smaller loss than a
     * missing one.
     *
     * ### Failure is silent, by design
     *
     * A pane whose local half is good must not become an error because the network was not there. The
     * failure is logged and the rows persisted by earlier refreshes stay on screen.
     */
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
                    // Where the session opened, so tapping the row goes back to it.
                    fromMillis = session.startedFrom.inWholeMilliseconds.coerceAtLeast(0),
                    toMillis = session.reachedAt.inWholeMilliseconds.coerceAtLeast(0),
                    reason = PlaybackEvent.ServerSession.name,
                    // How much was actually listened, which is not the span: a paused session accrues none.
                    detailMillis = session.listened.inWholeMilliseconds.coerceAtLeast(0),
                    // The server's own start time, so the row sits where it happened rather than where the
                    // fetch noticed it.
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

/**
 * The namespace for a history row derived from a server session id.
 *
 * Locally recorded events use a random `UUID`, so a collision is already unlikely; the prefix makes it
 * impossible, and makes a row's origin readable in a database dump.
 */
private const val SERVER_SESSION_PREFIX = "abs-session:"

private fun PlaybackHistoryEntity.toDomain() = PlaybackHistoryEntry(
    id = entryId,
    event = PlaybackEvent.parse(reason),
    from = fromMillis?.milliseconds,
    to = toMillis.milliseconds,
    detail = detailMillis?.milliseconds,
    at = Instant.ofEpochMilli(at),
)
