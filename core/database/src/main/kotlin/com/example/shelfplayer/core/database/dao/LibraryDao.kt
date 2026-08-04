package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.BookWithRelations
import com.example.shelfplayer.core.database.entity.ChapterEntity
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC 9.1 — the UI's read path.
 *
 * Every read returns a [Flow], because the UI observes Room rather than being pushed to by the sync
 * layer (PRODUCT_SPEC LIB-001, SYNC-002).
 */
@Dao
interface LibraryDao {
    @Query(
        """
        SELECT * FROM libraries
        WHERE serverId = :serverId AND isDeleted = 0
        ORDER BY displayOrder ASC, name ASC
        """,
    )
    fun observeLibraries(serverId: String): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM libraries WHERE libraryKey = :libraryKey AND isDeleted = 0")
    fun observeLibrary(libraryKey: String): Flow<LibraryEntity?>

    @Transaction
    @Query(
        """
        SELECT * FROM books
        WHERE libraryKey = :libraryKey AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeBooks(libraryKey: String): Flow<List<BookWithRelations>>

    @Transaction
    @Query("SELECT * FROM books WHERE bookKey = :bookKey AND isDeleted = 0")
    fun observeBook(bookKey: String): Flow<BookWithRelations?>

    @Query("SELECT COUNT(*) FROM books WHERE libraryKey = :libraryKey AND isDeleted = 0")
    suspend fun countBooks(libraryKey: String): Int

    // --- Write path ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraries(libraries: List<LibraryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAuthors(authors: List<AuthorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
