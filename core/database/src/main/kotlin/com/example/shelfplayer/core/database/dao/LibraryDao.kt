package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.shelfplayer.core.database.entity.BookWithRelations
import com.example.shelfplayer.core.database.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC 9.1 — the UI's read path.
 *
 * Every read returns a [Flow], because the UI observes Room rather than being pushed to by the sync
 * layer (PRODUCT_SPEC LIB-001, SYNC-002).
 *
 * The write path is [LibraryWriteDao]. They are separate interfaces because they have separate
 * callers: screens read, and only the sync writes. Keeping them apart means a screen cannot reach an
 * `upsert` by autocompletion, and it keeps either interface small enough to read in one screenful.
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

    /**
     * The books of an explicit set of libraries.
     *
     * One query serves both a single library and the whole shelf: a caller that wants one library
     * passes one key, and a caller honouring a profile's library grant passes the keys it names. Which
     * libraries a profile may read is decided outside this module — a DAO holds no opinion about who
     * may see what.
     *
     * Callers must not pass an empty list; there is no row one could want back, and the repository
     * short-circuits that case rather than relying on how `IN ()` is expanded.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM books
        WHERE libraryKey IN (:libraryKeys) AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeBooksIn(libraryKeys: List<String>): Flow<List<BookWithRelations>>

    /**
     * Every non-deleted book on one server, for the profile whose grant covers all libraries.
     *
     * Kept apart from [observeBooksIn] rather than expressed as "all the keys": the grant says *all
     * libraries*, including one that appears between this query and the next sync, and enumerating
     * today's keys would quietly not mean that.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM books
        WHERE serverId = :serverId AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeBooksOnServer(serverId: String): Flow<List<BookWithRelations>>

    @Transaction
    @Query("SELECT * FROM books WHERE bookKey = :bookKey AND isDeleted = 0")
    fun observeBook(bookKey: String): Flow<BookWithRelations?>

    @Query("SELECT COUNT(*) FROM books WHERE libraryKey = :libraryKey AND isDeleted = 0")
    suspend fun countBooks(libraryKey: String): Int
}
