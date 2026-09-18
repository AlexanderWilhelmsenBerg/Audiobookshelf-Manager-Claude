package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.CachedLibrary

/**
 * Issue #41 — bounded priority hydration inside the existing library refresh.
 *
 * Listening sessions only choose which visible catalogue items deserve early attention. The catalogue is
 * still the permission/admission source, targeted item fetch remains the metadata boundary, and the caller's
 * existing progress writer remains the only conflict policy for server-derived positions.
 */
internal class RecentBookHotSetHydrator(
    private val gateway: AudiobookshelfGateway,
    private val writer: LibrarySnapshotWriter,
    private val logger: Logger,
) {

    /**
     * Reads exactly one captured-size history page and preserves the server's newest-first order.
     *
     * Session timestamps and positions are deliberately ignored; only the first occurrence of each item id
     * is retained. Failure is best-effort because the ordinary full refresh is still authoritative.
     */
    suspend fun recentBookCandidates(profileId: ProfileId): List<LibraryItemId> =
        when (
            val sessions = gateway.playback.listeningSessions(
                profileId = profileId,
                page = 0,
                itemsPerPage = RECENT_SESSION_LIMIT,
            )
        ) {
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.Sync,
                    "Recent-book priority hints unavailable; continuing full refresh",
                    LogField.Public("errorCode", sessions.error.code),
                    LogField.Public("retryable", sessions.error.isRetryable),
                )
                emptyList()
            }

            is AppResult.Success -> {
                val seen = mutableSetOf<LibraryItemId>()
                val ids = sessions.value
                    .asSequence()
                    .map { it.bookId }
                    .filter(seen::add)
                    .take(RECENT_BOOK_LIMIT)
                    .toList()
                logger.info(
                    LogCategory.Sync,
                    "Prepared bounded recent-book priority hints",
                    LogField.Count("sessions", sessions.value.size),
                    LogField.Count("books", ids.size),
                )
                ids
            }
        }

    /**
     * Intersects priority hints with one catalogue batch, so a session id alone can never authorize a write.
     *
     * [attempted] is shared across the whole refresh and guarantees at most one targeted hydration attempt
     * per candidate even if a malformed catalogue repeated an item.
     */
    suspend fun hydrateBatch(
        profileId: ProfileId,
        library: Library,
        catalogueBatch: List<BookSnapshot>,
        recentBookIds: List<LibraryItemId>,
        attempted: MutableSet<LibraryItemId>,
        cached: LibraryRefreshCache,
        writeProgress: suspend (List<AccountProgress>) -> AppResult<Int>,
    ) {
        if (recentBookIds.isEmpty() || catalogueBatch.isEmpty()) return
        val visibleNow = catalogueBatch.associateBy { it.book.id }
        for (bookId in recentBookIds) {
            val preview = visibleNow[bookId] ?: continue
            if (!attempted.add(bookId)) continue
            val catalogueRevision = preview.book.remoteUpdatedAt?.toEpochMilli()
            if (cached.isUpToDate(bookId, catalogueRevision)) continue
            hydrateCandidate(profileId, library, bookId, cached, writeProgress)
        }
    }

    private suspend fun hydrateCandidate(
        profileId: ProfileId,
        library: Library,
        bookId: LibraryItemId,
        cached: LibraryRefreshCache,
        writeProgress: suspend (List<AccountProgress>) -> AppResult<Int>,
    ) {
        when (val fetched = gateway.library.fetchBook(profileId, bookId)) {
            is AppResult.Failure -> logger.warn(
                LogCategory.Sync,
                "Recent-book targeted hydration failed; bulk expansion will continue",
                LogField.Public("errorCode", fetched.error.code),
                LogField.Public("retryable", fetched.error.isRetryable),
            )

            is AppResult.Success -> {
                val snapshot = fetched.value
                // Admission came from this exact library's catalogue; a mismatched response cannot borrow it.
                if (snapshot.book.id != bookId || snapshot.book.libraryId != library.id) {
                    logger.warn(LogCategory.Sync, "Recent-book targeted hydration returned a mismatched item")
                    return
                }

                val progress = snapshot.book.progress
                // Snapshot metadata uses the normal Room owner, but server progress must go through the
                // repository's existing visibility/newness/local-unsynced conflict boundary.
                writer.writeBook(
                    profileId,
                    snapshot.copy(book = snapshot.book.copy(progress = null)),
                )
                val progressAccepted = progress == null || writeProgress(
                    listOf(
                        AccountProgress(
                            bookId = bookId,
                            position = progress.position,
                            duration = progress.duration,
                            isFinished = progress.isFinished,
                            updatedAt = progress.updatedAt,
                        ),
                    ),
                ) is AppResult.Success

                if (progressAccepted) {
                    cached.markExpanded(snapshot)
                }
            }
        }
    }

    private companion object {
        // The supported-server capture is page=0, itemsPerPage=10. One request only.
        const val RECENT_SESSION_LIMIT = 10
        const val RECENT_BOOK_LIMIT = 10
    }
}

/**
 * One refresh's materialised cache facts.
 *
 * The mutable expanded stamps are refresh-local only. A successful targeted hydration updates this same
 * object so the later ordinary expansion pass can skip the item when its stored server revision matches the
 * catalogue revision. Room, not this object, remains durable authority.
 */
internal class LibraryRefreshCache(
    private val expanded: MutableMap<String, Long?>,
    private val inProgress: Set<String>,
) : CachedLibrary {
    override fun isUpToDate(id: LibraryItemId, updatedAt: Long?): Boolean {
        val stored = expanded[id.value]
        return stored != null && updatedAt != null && stored == updatedAt
    }

    override fun isInProgress(id: LibraryItemId): Boolean = id.value in inProgress

    fun markExpanded(snapshot: BookSnapshot) {
        expanded[snapshot.book.id.value] = snapshot.book.remoteUpdatedAt?.toEpochMilli()
    }
}
