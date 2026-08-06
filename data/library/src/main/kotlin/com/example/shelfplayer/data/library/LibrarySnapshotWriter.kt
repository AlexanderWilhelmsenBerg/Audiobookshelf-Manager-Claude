package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.database.DatabaseTransactionRunner
import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileVisibleBookEntity
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.data.library.mapper.EntityMappers
import javax.inject.Inject
import javax.inject.Singleton

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
) {
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
}
