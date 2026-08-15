package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.MetadataDraftDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MetadataDraftEntity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.MetadataRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC MGR-001 — the metadata editor's repository.
 *
 * ### The order of operations on a save, and why it is that order
 *
 * 1. Send only the changed fields.
 * 2. Re-read the item, expanded, and write it to Room.
 * 3. Only then discard the draft.
 *
 * The third step is last on purpose. A draft discarded before the refresh lands would be a draft lost to
 * a network failure that happened *after* a successful write — the user's edit is on the server and their
 * device has forgotten what they typed, which is the worst of both.
 *
 * ### Why the refresh is a second request
 *
 * The `PATCH` answers with the whole item, and that answer is complete for the *metadata*. It is not
 * expanded, so it carries no tracks — and a `BookSnapshot` written without tracks would replace a playable
 * book with an unplayable one (product priority 1). The expanded re-read costs one request on an action a
 * user takes rarely, and it goes through the same writer the catalogue sync uses, so there is exactly one
 * piece of code that knows how to store a book.
 */
@Singleton
class DefaultMetadataRepository @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val library: LibraryRepository,
    private val draftDao: MetadataDraftDao,
    private val writer: LibrarySnapshotWriter,
    private val drafts: MetadataDraftCodec,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : MetadataRepository {

    override fun observeDraft(profileId: ProfileId, bookId: LibraryItemId): Flow<BookMetadataEdit?> =
        draftDao.observe(profileId.value, bookId.value).map { entity -> entity?.let(::decodeOrForget) }

    /**
     * A draft this build cannot read is the user's own words being dropped, so it is the one outcome here
     * worth a log line. Nothing is swallowed: the caller is told there is no draft, which is true.
     */
    private fun decodeOrForget(entity: MetadataDraftEntity): BookMetadataEdit? =
        when (val decoded = drafts.decode(entity.payload)) {
            is AppResult.Success -> decoded.value
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.Sync,
                    "A saved metadata draft could not be read and was ignored",
                    // The reason, never the draft: a draft is the user's own words about their own
                    // library (PRODUCT_SPEC 14.5).
                    LogField.Public("reason", decoded.error.code),
                )
                null
            }
        }

    override suspend fun saveDraft(profileId: ProfileId, bookId: LibraryItemId, edit: BookMetadataEdit) =
        withContext(ioDispatcher) {
            draftDao.upsert(
                MetadataDraftEntity(
                    draftId = EntityKey.scoped(profileId.value, bookId.value),
                    profileId = profileId.value,
                    bookKey = bookId.value,
                    payload = drafts.encode(edit),
                    savedAt = clock.now().toEpochMilli(),
                ),
            )
        }

    override suspend fun discardDraft(profileId: ProfileId, bookId: LibraryItemId) = withContext(ioDispatcher) {
        draftDao.delete(profileId.value, bookId.value)
    }

    override suspend fun reload(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book> =
        withContext(ioDispatcher) {
            when (val fetched = gateway.library.fetchBook(profileId, bookId)) {
                is AppResult.Failure -> fetched
                is AppResult.Success -> AppResult.Success(store(profileId, fetched.value))
            }
        }

    override suspend fun save(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<Book> = withContext(ioDispatcher) {
        when (val saved = gateway.management.updateMetadata(profileId, bookId, edit, changed)) {
            is AppResult.Failure -> saved
            is AppResult.Success -> {
                val book = store(profileId, saved.value)
                // Last, and only once the server's own version is in Room. See the class comment.
                discardDraft(profileId, bookId)
                AppResult.Success(book)
            }
        }
    }

    /**
     * Writes a fetched item to Room through the catalogue's own writer, and reads back what was stored.
     *
     * Reading back rather than returning the snapshot's own book is deliberate: the stored row carries the
     * profile's progress and its local download state, which the server's copy knows nothing about. A
     * caller handed the network's version would show a book that had forgotten it was downloaded.
     */
    private suspend fun store(profileId: ProfileId, snapshot: BookSnapshot): Book {
        writer.writeBook(profileId, snapshot)
        return library.observeBook(profileId, snapshot.book.id).first() ?: snapshot.book
    }
}
