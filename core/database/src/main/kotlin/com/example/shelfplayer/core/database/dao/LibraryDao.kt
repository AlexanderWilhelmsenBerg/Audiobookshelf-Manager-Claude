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
     * The books of an explicit set of libraries, as one profile may see them.
     *
     * One query serves both a single library and the whole shelf: a caller that wants one library
     * passes one key, and a caller honouring a profile's library grant passes the keys it names. Which
     * libraries a profile may read is decided outside this module — a DAO holds no opinion about who
     * may see what.
     *
     * The join onto `profile_visible_books` is not an optimisation and cannot be dropped for a profile
     * that "sees everything": Audiobookshelf's second restriction is per item, so the library grant the
     * caller applied is only half the answer (PRODUCT_SPEC 5.2). An inner join also gives the
     * default-deny the requirement wants for free — a profile with no recorded visibility matches no
     * rows.
     *
     * Callers must not pass an empty list; there is no row one could want back, and the repository
     * short-circuits that case rather than relying on how `IN ()` is expanded.
     */
    @Transaction
    @Query(
        """
        SELECT books.* FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.libraryKey IN (:libraryKeys)
          AND books.isDeleted = 0
        ORDER BY books.title COLLATE NOCASE ASC
        """,
    )
    fun observeBooksIn(profileId: String, libraryKeys: List<String>): Flow<List<BookWithRelations>>

    /**
     * Every non-deleted book on one server that this profile can see.
     *
     * Kept apart from [observeBooksIn] rather than expressed as "all the keys": the grant says *all
     * libraries*, including one that appears between this query and the next sync, and enumerating
     * today's keys would quietly not mean that.
     */
    @Transaction
    @Query(
        """
        SELECT books.* FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.serverId = :serverId
          AND books.isDeleted = 0
        ORDER BY books.title COLLATE NOCASE ASC
        """,
    )
    fun observeBooksOnServer(profileId: String, serverId: String): Flow<List<BookWithRelations>>

    /**
     * One book, if this profile may see it.
     *
     * PRODUCT_SPEC 15 requires a deep link to validate access, and a detail screen reached by id is a
     * deep link whether or not it arrived through one.
     */
    @Transaction
    @Query(
        """
        SELECT books.* FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.bookKey = :bookKey
          AND books.isDeleted = 0
        """,
    )
    fun observeBook(profileId: String, bookKey: String): Flow<BookWithRelations?>

    @Query(
        """
        SELECT COUNT(*) FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.libraryKey = :libraryKey
          AND books.isDeleted = 0
        """,
    )
    suspend fun countBooks(profileId: String, libraryKey: String): Int

    /**
     * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — what is stored, regardless of who may see it.
     *
     * Deliberately *unfiltered* by any profile's grant, which is the whole point: the acceptance check is
     * that an unauthorized library was never **written**, and a query that already applied the grant could
     * not tell that apart from one that hid it. The result is a count, never a name, so nothing crosses
     * the boundary it is measuring (PRODUCT_SPEC 5.2).
     */
    @Query("SELECT COUNT(*) FROM libraries WHERE serverId = :serverId AND isDeleted = 0")
    fun observeLibraryCount(serverId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE serverId = :serverId AND isDeleted = :deleted")
    fun observeBookCount(serverId: String, deleted: Boolean): Flow<Int>

    /**
     * The counterpart to [observeBookCount]: of everything stored for this server, how much this profile
     * can see.
     *
     * The pair is the diagnostic. "490 stored, 188 visible" is the on-device proof that item-level
     * filtering is doing its job, and it is a question the shelf itself cannot answer — a screen showing
     * 188 books looks identical whether the other 302 were hidden or never fetched.
     */
    @Query(
        """
        SELECT COUNT(*) FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.serverId = :serverId
          AND books.isDeleted = 0
        """,
    )
    fun observeVisibleBookCount(profileId: String, serverId: String): Flow<Int>

    /**
     * PRODUCT_SPEC 5.2 — the book keys this profile may see, for a caller that has to filter a list it
     * did not get from a query.
     *
     * The progress sync is that caller: the server sends every position the *account* has, including
     * positions in libraries it has since lost, and those must not become rows nothing can display.
     */
    @Query("SELECT bookKey FROM profile_visible_books WHERE profileId = :profileId")
    suspend fun visibleBookKeys(profileId: String): List<String>

    /**
     * PRODUCT_SPEC LIB-001 — which books are already expanded, and at which server revision.
     *
     * "Expanded" is `EXISTS (a track)`, not a flag. A flag would have to be written correctly by every
     * path that stores a book and would be wrong the first time one forgot; the tracks either are in
     * the database or they are not, and that is the thing the sync actually needs to know.
     *
     * Scoped by visibility like every other read, so one profile's cache cannot answer for another's.
     */
    @Query(
        """
        SELECT books.remoteId AS remoteId, books.remoteUpdatedAt AS remoteUpdatedAt FROM books
        INNER JOIN profile_visible_books ON profile_visible_books.bookKey = books.bookKey
        WHERE profile_visible_books.profileId = :profileId
          AND books.libraryKey = :libraryKey
          AND books.isDeleted = 0
          AND EXISTS (SELECT 1 FROM audio_tracks WHERE audio_tracks.bookKey = books.bookKey)
        """,
    )
    suspend fun expandedBookStamps(profileId: String, libraryKey: String): List<ExpandedBookStamp>
}

/** One already-expanded book: its server id and the `updatedAt` the server reported when it was stored. */
data class ExpandedBookStamp(val remoteId: String, val remoteUpdatedAt: Long?)
