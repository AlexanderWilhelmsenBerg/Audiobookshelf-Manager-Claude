package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.database.DatabaseTransactionRunner
import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
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
    /** Returns the number of books written. */
    suspend fun write(libraries: List<Library>, snapshots: Map<LibraryId, List<BookSnapshot>>): Int {
        var written = 0
        transaction {
            libraryWriteDao.upsertLibraries(libraries.map(EntityMappers::toEntity))
            libraries.forEach { library ->
                val rows = snapshots[library.id].orEmpty().map(EntityMappers::toEntities)
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
                if (rows.isEmpty()) {
                    libraryWriteDao.markAllBooksDeleted(libraryKey)
                } else {
                    libraryWriteDao.markMissingBooksDeleted(libraryKey, rows.map { it.book.bookKey })
                }
                written += rows.size
            }
        }
        return written
    }
}
