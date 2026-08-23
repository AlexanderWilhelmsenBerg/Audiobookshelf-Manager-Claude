package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.BookmarkDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.BookmarkEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountBookmark
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 / 5.2 — a book's bookmarks, scoped to the profile that made them.
 *
 * Profile-scoped for the same reason progress is: two people on one server share a library and do not share
 * their notes about it. The foreign key cascades, so removing a profile removes its bookmarks with it.
 *
 * ### Local first, then the server, and never rolled back
 *
 * Each write stores the row and *then* calls the server, returning the server's failure without undoing the
 * local change. The flags are what make that safe across a refresh — see [BookmarkRepository] for why, and
 * [writeAccountBookmarks] for the half that honours them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultBookmarkRepository @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileDao: ProfileDao,
    private val bookmarkDao: BookmarkDao,
    private val gateway: AudiobookshelfGateway,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : BookmarkRepository {

    /**
     * Re-subscribed when the active profile changes, so switching profile switches whose notes are shown
     * rather than leaving the previous one's on screen (PRODUCT_SPEC 5.2).
     */
    override fun observe(bookId: LibraryItemId): Flow<List<Bookmark>> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                bookmarkDao.observe(profile.id.value, EntityKey.of(profile.serverId.value, bookId.value))
                    .map { rows -> rows.map { row -> row.toDomain(bookId) } }
            }
        }

    override suspend fun add(bookId: LibraryItemId, at: Duration, title: String, owner: ProfileId?): AppResult<Unit> =
        withScope(bookId, owner) { profileId, bookKey ->
            val seconds = at.inWholeSeconds.coerceAtLeast(0)
            bookmarkDao.upsert(
                listOf(
                    BookmarkEntity(
                        bookmarkId = idFor(profileId, bookKey, seconds),
                        profileId = profileId.value,
                        bookKey = bookKey,
                        atSeconds = seconds,
                        title = title,
                        createdAt = clock.now().toEpochMilli(),
                        hasUnsyncedChanges = true,
                        isPendingDelete = false,
                    ),
                ),
            )
            logger.info(
                LogCategory.Playback,
                "A bookmark was added",
                LogField.Millis("at", seconds * MILLIS_PER_SECOND),
            )
            val remote = gateway.bookmarks.create(profileId, bookId, seconds.seconds, title)
            markSynced(remote, profileId, bookKey, seconds)
        }

    override suspend fun rename(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> =
        withScope(bookId) { profileId, bookKey ->
            val seconds = at.inWholeSeconds.coerceAtLeast(0)
            val stored = bookmarkDao.find(profileId.value, bookKey, seconds)
                ?: return@withScope AppResult.Failure(
                    AppError.Unknown(summary = "That bookmark is no longer there."),
                )
            bookmarkDao.upsert(listOf(stored.copy(title = title, hasUnsyncedChanges = true)))
            val remote = gateway.bookmarks.rename(profileId, bookId, seconds.seconds, title)
            markSynced(remote, profileId, bookKey, seconds)
        }

    /**
     * Marks the bookmark for deletion, then asks the server, then removes the row on success.
     *
     * Three steps rather than two because a failed delete has to be *remembered*. Deleting the row outright
     * and then failing to tell the server would resurrect the bookmark on the next refresh — the listener
     * deletes something, it comes back an hour later, and nothing in the app explains why.
     */
    override suspend fun remove(bookId: LibraryItemId, at: Duration): AppResult<Unit> =
        withScope(bookId) { profileId, bookKey ->
            val seconds = at.inWholeSeconds.coerceAtLeast(0)
            val stored = bookmarkDao.find(profileId.value, bookKey, seconds) ?: return@withScope AppResult.Success(
                // Already gone. Reporting success is the honest answer to "remove this": it is removed.
                Unit,
            )
            bookmarkDao.upsert(listOf(stored.copy(isPendingDelete = true, hasUnsyncedChanges = true)))
            when (val removed = gateway.bookmarks.remove(profileId, bookId, seconds.seconds)) {
                is AppResult.Failure -> AppResult.Failure(removed.error)
                is AppResult.Success -> {
                    bookmarkDao.deleteById(idFor(profileId, bookKey, seconds))
                    logger.info(LogCategory.Playback, "A bookmark was removed")
                    AppResult.Success(Unit)
                }
            }
        }

    /**
     * PRODUCT_SPEC 11.1 — the server's whole set, with the two local exceptions kept.
     *
     * A **replace** rather than a merge, because the response is authoritative: a bookmark deleted on another
     * device has to disappear here, and a merge would keep it forever. The exceptions are the rows the server
     * has not been told about yet, and they are computed here rather than in SQL because "what the server
     * cannot yet speak for" is a rule about this app, not about the table.
     *
     * A bookmark for a book this profile cannot see is dropped, exactly as `writeProgress` drops a position
     * for one: the server sends everything the *account* has, and writing rows behind the visibility filter
     * would leave data nothing can show and nothing will clean up (PRODUCT_SPEC 5.2).
     */
    override suspend fun writeAccountBookmarks(
        profileId: ProfileId,
        bookmarks: List<AccountBookmark>,
    ): AppResult<Int> = withContext(ioDispatcher) {
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val pending = bookmarkDao.findAllFor(profileId.value)
            .filter { it.hasUnsyncedChanges || it.isPendingDelete }
            .map { it.bookmarkId }
            .toSet()
        val rows = bookmarks.map { remote ->
            val bookKey = EntityKey.of(profile.serverId, remote.bookId.value)
            BookmarkEntity(
                bookmarkId = idFor(profileId, bookKey, remote.atSeconds),
                profileId = profileId.value,
                bookKey = bookKey,
                atSeconds = remote.atSeconds,
                title = remote.title,
                createdAt = remote.createdAt.toEpochMilli(),
                hasUnsyncedChanges = false,
                isPendingDelete = false,
            )
        }
        bookmarkDao.replaceRemote(profileId.value, rows, pending)
        logger.info(
            LogCategory.Sync,
            "Refreshed the account's bookmarks",
            LogField.Count("reported", bookmarks.size),
            LogField.Count("pending", pending.size),
        )
        AppResult.Success(rows.size - rows.count { it.bookmarkId in pending })
    }

    /**
     * The profile and book key every write needs, or an authentication failure.
     *
     * [owner] wins over the active profile when the caller supplies one. The comment here used to say
     * `activeProfileId` was right because "there is no caller that could sensibly name a different profile";
     * `PlaybackService.bookmarkHere` is that caller. It launches on the application scope so a bookmark
     * dropped as a car disconnects still lands, which means the write can finish after a profile switch has
     * changed what `activeProfileId` answers — the race R-49 closed for positions, arriving one write later.
     *
     * `null` still means the active profile, which is what the phone's own sheet wants: it is bound to the
     * profile on screen, and naming one would be ceremony.
     */
    private suspend fun withScope(
        bookId: LibraryItemId,
        owner: ProfileId? = null,
        block: suspend (ProfileId, String) -> AppResult<Unit>,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val profileId = owner ?: profileRepository.activeProfileId()
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        block(profileId, EntityKey.of(profile.serverId, bookId.value))
    }

    /**
     * Clears the unsynced flag when the server accepted the write, and leaves it when it did not.
     *
     * The failure is returned rather than swallowed, and the row stays. That is the whole of the offline
     * story: the bookmark is on screen, the flag says the server has not seen it, and the next refresh will
     * not overwrite it.
     */
    private suspend fun markSynced(
        remote: AppResult<Bookmark>,
        profileId: ProfileId,
        bookKey: String,
        seconds: Long,
    ): AppResult<Unit> = when (remote) {
        is AppResult.Failure -> AppResult.Failure(remote.error)
        is AppResult.Success -> {
            bookmarkDao.find(profileId.value, bookKey, seconds)?.let { stored ->
                bookmarkDao.upsert(listOf(stored.copy(hasUnsyncedChanges = false)))
            }
            AppResult.Success(Unit)
        }
    }

    private fun BookmarkEntity.toDomain(bookId: LibraryItemId) = Bookmark(
        bookId = bookId,
        at = atSeconds.seconds,
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    private companion object {
        /** `<profile>:<book>:<seconds>` — the server's identity for a bookmark, as a local primary key. */
        fun idFor(profileId: ProfileId, bookKey: String, seconds: Long): String =
            "${EntityKey.scoped(profileId.value, bookKey)}:$seconds"

        const val MILLIS_PER_SECOND = 1_000L
    }
}
