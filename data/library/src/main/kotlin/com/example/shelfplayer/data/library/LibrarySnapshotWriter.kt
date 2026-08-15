package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.database.DatabaseTransactionRunner
import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.PlaybackHistoryDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.database.entity.ProfileVisibleBookEntity
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.data.library.mapper.EntityMappers
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * PRODUCT_SPEC LIB-001 — the whole refresh, or none of it.
 *
 * Split out of `DefaultLibraryRepository` because it is the one place that writes: the repository
 * decides *what* to fetch and what the UI sees, and this decides how a fetched snapshot lands in Room.
 * The split is what keeps the write DAO off the repository's constructor, and with it any chance of a
 * read path reaching an `upsert`.
 *
 * Everything runs inside one transaction, which is what makes "a failed sync leaves cached content on
 * screen" true rather than aspirational — a half-applied sync is never observable.
 *
 * Public only because `DefaultLibraryRepository` is; nothing outside this module can name it, since
 * `:core:database` is an `implementation` dependency and every parameter here is a Room DAO.
 */
@Singleton
class LibrarySnapshotWriter @Inject constructor(
    private val transaction: DatabaseTransactionRunner,
    private val libraryWriteDao: LibraryWriteDao,
    private val progressDao: ProgressDao,
    private val historyDao: PlaybackHistoryDao,
) {
    /**
     * PRODUCT_SPEC PLAY-004 / SYNC-002 — positions the server reported, and the history they make.
     *
     * ### Two writes that have to be one
     *
     * The rows are the account's positions; the history entries say which of them arrived from a device
     * that is not this one. Written in a single transaction because a crash between them would leave a
     * position with no explanation, which is the exact confusion the entries exist to prevent.
     *
     * ### Why the history needs these at all
     *
     * A device run asked for it: *"The history should also show the latest changes from the server."*
     * Without them the pane is a record of one phone, and a book that jumped four hours because it was
     * finished in the web player looks exactly like the app losing somebody's place. With them the list
     * says which.
     *
     * ### Which rows count as "somewhere else"
     *
     * Three filters, and each removes a specific false positive.
     *
     * **A book with no local row is skipped.** The first sync of a library writes a position for everything
     * the account has ever played, and none of that is news — it is the app learning what the account is,
     * not a change. Recording it would put a hundred rows in a pane that has never been opened.
     *
     * **A position that barely moved is skipped.** This device's own writes go up to the server and come
     * back, and the echo is a change by `updatedAt` while being the same listening. [REMOTE_TOLERANCE_MS]
     * is set above the sync cadence so a round trip cannot clear it, and the cost of that choice is stated
     * plainly: somebody else listening for less than a minute on another device leaves no row.
     *
     * **The finished flag is exempt from that tolerance**, because flipping it is not a position change at
     * all and is the surprise most worth explaining.
     *
     * @param rows the positions to write, already filtered by the repository's conflict rules.
     * @param existing what this device held before, keyed by book, for deciding what is news.
     */
    suspend fun writeProgress(
        profileId: ProfileId,
        rows: List<MediaProgressEntity>,
        existing: Map<String, MediaProgressEntity>,
    ) {
        transaction {
            progressDao.upsertProgress(rows)
            rows.forEach { row -> recordRemoteChange(profileId, row, existing[row.bookKey]) }
        }
    }

    private suspend fun recordRemoteChange(
        profileId: ProfileId,
        row: MediaProgressEntity,
        before: MediaProgressEntity?,
    ) {
        if (before == null) return
        val moved = (row.positionMillis - before.positionMillis).absoluteValue >= REMOTE_TOLERANCE_MS
        val finishedChanged = row.isFinished != before.isFinished
        if (!moved && !finishedChanged) return
        historyDao.record(
            entry = PlaybackHistoryEntity(
                entryId = UUID.randomUUID().toString(),
                profileId = profileId.value,
                bookKey = row.bookKey,
                // Where this device was, so tapping the row goes back to it. That is the undo a listener
                // needs when they find themselves somewhere they did not choose to be.
                fromMillis = before.positionMillis,
                toMillis = row.positionMillis,
                reason = if (finishedChanged) {
                    PlaybackEvent.RemoteFinished.name
                } else {
                    PlaybackEvent.RemoteProgress.name
                },
                detailMillis = null,
                // The server's own timestamp. A refresh after a week away would otherwise stamp a week of
                // another device's listening with this morning.
                at = row.updatedAt,
            ),
            keep = PlaybackHistoryRepository.DEFAULT_LIMIT,
        )
    }

    /**
     * Returns the number of books written.
     *
     * [reconciles] is the syncing profile's authority to *delete*, not just to add — see
     * `LibraryAccess.reconciles`. An account the server filters (by library or by tag) sees a partial
     * view, so absence from its sync says nothing about the server. A device run showed what happens
     * without this: a restricted account synced a shared library and 302 of the unrestricted account's
     * 490 books were marked removed.
     */
    suspend fun write(
        profileId: ProfileId,
        libraries: List<Library>,
        snapshots: Map<LibraryId, LibrarySnapshot>,
        reconciles: Boolean = true,
    ): Int {
        var written = 0
        transaction {
            libraryWriteDao.upsertLibraries(libraries.map(EntityMappers::toEntity))
            if (reconciles) {
                reconcileLibraries(libraries)
            }
            libraries.forEach { library ->
                val fetched = snapshots[library.id]
                val rows = fetched?.books.orEmpty().map(EntityMappers::toEntities)
                libraryWriteDao.upsertAuthors(rows.flatMap { it.authors }.distinctBy { it.authorKey })
                libraryWriteDao.upsertSeries(rows.flatMap { it.series }.distinctBy { it.seriesKey })
                libraryWriteDao.upsertBooks(rows.map { it.book })
                rows.forEach { row ->
                    libraryWriteDao.deleteAuthorLinksFor(row.book.bookKey)
                    libraryWriteDao.deleteSeriesLinksFor(row.book.bookKey)
                    libraryWriteDao.deleteTracksFor(row.book.bookKey)
                    libraryWriteDao.deleteChaptersFor(row.book.bookKey)
                }
                libraryWriteDao.upsertBookAuthors(rows.flatMap { it.authorLinks })
                libraryWriteDao.upsertBookSeries(rows.flatMap { it.seriesLinks })
                libraryWriteDao.upsertTracks(rows.flatMap { it.tracks })
                libraryWriteDao.upsertChapters(rows.flatMap { it.chapters })
                progressDao.upsertProgress(rows.mapNotNull { it.progress })
                val libraryKey = EntityKey.of(library.serverId.value, library.id.value)
                recordVisibility(profileId, library, libraryKey, fetched)
                // PRODUCT_SPEC 13.2 / LIB-001 — absence only means deletion when this profile could have
                // seen everything *and* the fetch actually did.
                //
                // An incomplete fetch is missing items because the network failed, and soft-deleting them
                // would turn one timeout into a library that visibly loses books. A filtered profile is
                // missing items because the server hid them, which is not the same as their being gone.
                if (reconciles && fetched?.isComplete == true) {
                    if (rows.isEmpty()) {
                        libraryWriteDao.markAllBooksDeleted(libraryKey)
                    } else {
                        libraryWriteDao.markMissingBooksDeleted(libraryKey, rows.map { it.book.bookKey })
                    }
                }
                written += rows.size
            }
        }
        return written
    }

    /**
     * PRODUCT_SPEC LIB-001 — books that have arrived so far, written so the shelf can show them.
     *
     * Additive only. No visibility, no reconciliation, no deletions: every one of those is a statement
     * about the *whole* catalogue, and a batch is by definition a part of it. Writing a deletion from a
     * partial view is the defect this codebase has already been bitten by twice.
     *
     * Its own transaction per batch, so each becomes visible as it lands. That is the entire point —
     * one transaction at the end is what left a 490-book library blank for minutes.
     */
    suspend fun writeBooks(profileId: ProfileId, library: Library, books: List<BookSnapshot>) {
        writeBooks(profileId, EntityKey.of(library.serverId.value, library.id.value), books) {
            libraryWriteDao.upsertLibraries(listOf(EntityMappers.toEntity(library)))
        }
    }

    /**
     * PRODUCT_SPEC MGR-001 / MGR-004 — one book, refreshed after something changed it on the server.
     *
     * The same write as [writeBooks] without the library upsert, because there is nothing new to say about
     * the library: this is a book the device already had, being re-read. Inventing a `Library` row from a
     * single item's `libraryId` would write a library with no name over the real one.
     */
    suspend fun writeBook(profileId: ProfileId, book: BookSnapshot) {
        writeBooks(profileId, EntityKey.of(book.book.serverId.value, book.book.libraryId.value), listOf(book))
    }

    private suspend fun writeBooks(
        profileId: ProfileId,
        libraryKey: String,
        books: List<BookSnapshot>,
        upsertLibrary: suspend () -> Unit = {},
    ) {
        if (books.isEmpty()) return
        val rows = books.map(EntityMappers::toEntities)
        transaction {
            upsertLibrary()
            libraryWriteDao.upsertAuthors(rows.flatMap { it.authors }.distinctBy { it.authorKey })
            libraryWriteDao.upsertSeries(rows.flatMap { it.series }.distinctBy { it.seriesKey })
            libraryWriteDao.upsertBooks(rows.map { it.book })
            rows.forEach { row ->
                libraryWriteDao.deleteAuthorLinksFor(row.book.bookKey)
                libraryWriteDao.deleteSeriesLinksFor(row.book.bookKey)
                libraryWriteDao.deleteTracksFor(row.book.bookKey)
                libraryWriteDao.deleteChaptersFor(row.book.bookKey)
            }
            libraryWriteDao.upsertBookAuthors(rows.flatMap { it.authorLinks })
            libraryWriteDao.upsertBookSeries(rows.flatMap { it.seriesLinks })
            libraryWriteDao.upsertTracks(rows.flatMap { it.tracks })
            libraryWriteDao.upsertChapters(rows.flatMap { it.chapters })
            progressDao.upsertProgress(rows.mapNotNull { it.progress })
            // PRODUCT_SPEC 5.2 — visible as they arrive, or they do not arrive at all: every read joins
            // through this table, so a batch written without it is a batch nothing can display.
            //
            // Added, never cleared. The clear belongs to the final pass, which knows the whole catalogue.
            // Doing it here would briefly hide books the profile could already see, and a sync that then
            // failed would leave them hidden — turning a dropped connection into a shrinking library.
            libraryWriteDao.insertVisibility(
                rows.map { row ->
                    ProfileVisibleBookEntity(
                        profileId = profileId.value,
                        bookKey = row.book.bookKey,
                        libraryKey = libraryKey,
                    )
                },
            )
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — what this profile was shown, as opposed to what is stored.
     *
     * Only a library this sync actually reached is rewritten. A library whose fetch never produced a
     * snapshot keeps whatever visibility it had, because "we could not ask" is not "you may not see it"
     * — the same distinction `LibrarySnapshot.unreachableCount` draws one level down, and getting it
     * wrong here would empty a shelf on a dropped connection.
     */
    private suspend fun recordVisibility(
        profileId: ProfileId,
        library: Library,
        libraryKey: String,
        fetched: LibrarySnapshot?,
    ) {
        if (fetched == null) return
        libraryWriteDao.clearVisibility(profileId.value, libraryKey)
        libraryWriteDao.insertVisibility(
            fetched.visibleIds.map { itemId ->
                ProfileVisibleBookEntity(
                    profileId = profileId.value,
                    bookKey = EntityKey.of(library.serverId.value, itemId.value),
                    libraryKey = libraryKey,
                )
            },
        )
    }

    /**
     * PRODUCT_SPEC 13.2 — libraries the server stopped listing, and their books.
     *
     * The books have to go too: the shelf reads books by server, so a library row marked deleted would
     * otherwise leave its contents on screen.
     */
    private suspend fun reconcileLibraries(libraries: List<Library>) {
        val serverId = libraries.firstOrNull()?.serverId?.value ?: return
        val presentKeys = libraries.map { EntityKey.of(it.serverId.value, it.id.value) }
        libraryWriteDao.markMissingLibrariesDeleted(serverId, presentKeys)
        libraryWriteDao.markBooksOutsideLibrariesDeleted(serverId, presentKeys)
    }

    private companion object {
        /**
         * PRODUCT_SPEC PLAY-004 — how far a server position has to move before it is *news*.
         *
         * Sixty seconds, chosen against the two cadences it has to clear. The journal writes every five
         * seconds and the remote sync every thirty, so this device's own position can be up to half a minute
         * ahead of the copy the server echoes back — and every one of those echoes is a change by timestamp
         * while being nobody else's listening. A minute clears that with room to spare.
         */
        const val REMOTE_TOLERANCE_MS = 60_000L
    }
}
