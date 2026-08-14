package com.example.shelfplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.shelfplayer.core.database.entity.DownloadRequestEntity
import com.example.shelfplayer.core.database.entity.DownloadedBookEntity
import com.example.shelfplayer.core.database.entity.DownloadedFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC DL-002 / DL-003 — the offline manifest's storage.
 *
 * Read and write are one interface here, unlike `LibraryDao`/`LibraryWriteDao`. The reason that pair is
 * split does not apply: the library's writer is a background sync and its readers are screens, so keeping
 * `upsert` out of a screen's reach is worth an interface. A download is written by the thing that started
 * it and read by the same repository moments later, and splitting would only mean two injections into one
 * class.
 */
@Dao
interface DownloadDao {

    /**
     * Every download on the device, newest first — the storage screen's list.
     *
     * Deliberately **unscoped by profile**, which is the owner's decision 6: *"a simple solution can show
     * all downloaded books for all users in the setting. So if I go into a user that doesn't have that book,
     * I can still see all downloaded books in the settings."* The rows are what is taking up space, which is
     * a fact about the device rather than about an account.
     *
     * PRODUCT_SPEC 5.2 still applies to what may be *shown*: a caller renders the title only for a book the
     * current profile can see, and everything else as an untitled row with its size. The boundary is at the
     * screen because the deletion this list exists for has to be possible for a row nobody can name.
     */
    @Transaction
    @Query("SELECT * FROM downloaded_books ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadedBookWithFiles>>

    /** One book's manifest, for the screen that shows it and the player that resolves URIs from it. */
    @Transaction
    @Query("SELECT * FROM downloaded_books WHERE bookKey = :bookKey")
    fun observe(bookKey: String): Flow<DownloadedBookWithFiles?>

    @Transaction
    @Query("SELECT * FROM downloaded_books WHERE bookKey = :bookKey")
    suspend fun find(bookKey: String): DownloadedBookWithFiles?

    /**
     * PRODUCT_SPEC DL-002 — what the start-up verifier walks.
     *
     * Complete books only. An incomplete one has nothing to verify — its files are expected to be missing —
     * and including it would turn every interrupted download into a start-up warning.
     */
    @Transaction
    @Query("SELECT * FROM downloaded_books WHERE state = 'Complete' ORDER BY updatedAt ASC")
    suspend fun completed(): List<DownloadedBookWithFiles>

    @Upsert
    suspend fun upsertBook(book: DownloadedBookEntity)

    @Upsert
    suspend fun upsertFiles(files: List<DownloadedFileEntity>)

    /**
     * PRODUCT_SPEC DL-003 criterion 4 — a second profile asking for a book that is already here.
     *
     * `IGNORE` rather than `REPLACE`: asking twice must not reset [DownloadRequestEntity.requestedAt], and
     * above all must not clear a pin. A profile that pinned a book and then pressed download again has not
     * asked to unpin it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRequest(request: DownloadRequestEntity)

    @Query("UPDATE download_requests SET isPinned = :isPinned WHERE bookKey = :bookKey AND profileId = :profileId")
    suspend fun setPinned(bookKey: String, profileId: String, isPinned: Boolean)

    /**
     * PRODUCT_SPEC DL-003 criterion 5 — one profile stops wanting a copy.
     *
     * Removes the reference, never the files. Whether anything is left to delete is [referenceCount]'s
     * answer, and the deletion itself is a filesystem step no database can perform.
     */
    @Query("DELETE FROM download_requests WHERE bookKey = :bookKey AND profileId = :profileId")
    suspend fun removeRequest(bookKey: String, profileId: String)

    @Query("SELECT COUNT(*) FROM download_requests WHERE bookKey = :bookKey")
    suspend fun referenceCount(bookKey: String): Int

    /**
     * The books no profile references any more — what a cleanup pass deletes from disk.
     *
     * The rows can survive their last reference: a profile deleted from the device cascades its requests
     * away, and the files it left behind are still on the card. This is how they are found again.
     */
    @Query(
        """
        SELECT bookKey FROM downloaded_books
        WHERE NOT EXISTS (
            SELECT 1 FROM download_requests WHERE download_requests.bookKey = downloaded_books.bookKey
        )
        """,
    )
    suspend fun unreferencedBookKeys(): List<String>

    /**
     * PRODUCT_SPEC DL-006 — a book a profile has protected from automatic cleanup.
     *
     * Any pin protects the copy, not only the current profile's: one shared blob, and one person's decision
     * to keep it is enough to keep it.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM download_requests WHERE bookKey = :bookKey AND isPinned = 1)")
    suspend fun isPinned(bookKey: String): Boolean

    /** Which of these books this profile can play offline, for a shelf that marks them. */
    @Query(
        """
        SELECT downloaded_books.bookKey FROM downloaded_books
        INNER JOIN download_requests ON download_requests.bookKey = downloaded_books.bookKey
        WHERE download_requests.profileId = :profileId AND downloaded_books.state = 'Complete'
        """,
    )
    fun observeCompletedFor(profileId: String): Flow<List<String>>

    /**
     * Removes the manifest, and only the manifest.
     *
     * The files are gone by the time this is called, or they are about to be orphaned deliberately — DL-003
     * lets a user keep local media when a server connection is removed. Either way this row going away is a
     * separate decision from the bytes going away, and a method that did both would make the safe order
     * impossible to write.
     */
    @Query("DELETE FROM downloaded_books WHERE bookKey = :bookKey")
    suspend fun deleteManifest(bookKey: String)

    @Query("DELETE FROM downloaded_files WHERE bookKey = :bookKey AND remoteFileId = :remoteFileId")
    suspend fun deleteFile(bookKey: String, remoteFileId: String)

    /** What the storage screen puts at the top: how much of the device this is using. */
    @Query("SELECT COALESCE(SUM(downloadedBytes), 0) FROM downloaded_files")
    fun observeTotalBytes(): Flow<Long>
}

/** One book's manifest and its files, which are never useful apart. */
data class DownloadedBookWithFiles(
    @Embedded val book: DownloadedBookEntity,
    @Relation(parentColumn = "bookKey", entityColumn = "bookKey")
    val files: List<DownloadedFileEntity>,
    @Relation(parentColumn = "bookKey", entityColumn = "bookKey")
    val requests: List<DownloadRequestEntity>,
)
