package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.ChapterEntity
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity

/**
 * PRODUCT_SPEC LIB-001 — the sync's write path.
 *
 * Nothing here returns a [kotlinx.coroutines.flow.Flow] and nothing here is called by a screen. Every
 * function is meant to run inside one transaction (see `DatabaseTransactionRunner`), so that a
 * partially applied sync is never observable — the counterpart of [LibraryDao], which only reads.
 */
@Dao
interface LibraryWriteDao {
    /**
     * `@Upsert`, **not** `@Insert(REPLACE)` — and the difference is data loss.
     *
     * `INSERT OR REPLACE` implements a conflict as *delete the old row, insert the new one*, and SQLite
     * runs `ON DELETE CASCADE` for that delete. Every one of the four tables below is a parent: replacing
     * a library row deletes its books, replacing a book row deletes its tracks, chapters, author and
     * series links **and the profile's progress in it**. A test caught it here — a sync that re-wrote a
     * library with one book left one book, not the five that were already stored.
     *
     * `@Upsert` issues an `UPDATE` on conflict instead. Nothing is deleted, so nothing cascades.
     *
     * The leaf tables further down keep `REPLACE`: no foreign key points at them, so there is nothing for
     * a delete to cascade to, and the sync's explicit `delete*For` calls already own their lifetime.
     */
    @Upsert
    suspend fun upsertLibraries(libraries: List<LibraryEntity>)

    @Upsert
    suspend fun upsertAuthors(authors: List<AuthorEntity>)

    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Upsert
    suspend fun upsertBooks(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookAuthors(crossRefs: List<BookAuthorCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookSeries(crossRefs: List<BookSeriesCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<AudioTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM audio_tracks WHERE bookKey = :bookKey")
    suspend fun deleteTracksFor(bookKey: String)

    @Query("DELETE FROM chapters WHERE bookKey = :bookKey")
    suspend fun deleteChaptersFor(bookKey: String)

    @Query("DELETE FROM book_authors WHERE bookKey = :bookKey")
    suspend fun deleteAuthorLinksFor(bookKey: String)

    @Query("DELETE FROM book_series WHERE bookKey = :bookKey")
    suspend fun deleteSeriesLinksFor(bookKey: String)

    /**
     * PRODUCT_SPEC 13.2 — soft delete.
     *
     * An item that disappeared from a sync is marked deleted rather than dropped, so that a
     * downloaded copy and its unsynced progress survive a transient server-side hiccup and can be
     * reconciled instead of silently vanishing.
     */
    @Query(
        """
        UPDATE books SET isDeleted = 1
        WHERE libraryKey = :libraryKey AND bookKey NOT IN (:presentKeys)
        """,
    )
    suspend fun markMissingBooksDeleted(libraryKey: String, presentKeys: List<String>)

    /**
     * The empty-result case.
     *
     * `NOT IN ()` is not valid SQLite, so a library that came back with no items needs its own
     * statement rather than an empty binding list.
     */
    @Query("UPDATE books SET isDeleted = 1 WHERE libraryKey = :libraryKey")
    suspend fun markAllBooksDeleted(libraryKey: String)
}
