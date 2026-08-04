package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.dao.SyncStateDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.runInTransaction
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.data.library.mapper.EntityMappers
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC LIB-001 — the Room-backed library repository.
 *
 * Reads come from Room only. [refresh] pulls from the gateway and writes into Room in a single
 * transaction, so a partially applied sync can never be observed. A refresh failure leaves the last
 * cached content untouched and is recorded in the sync state instead of surfacing as an empty
 * library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultLibraryRepository @Inject constructor(
    private val database: ShelfPlayerDatabase,
    private val libraryDao: LibraryDao,
    private val profileDao: ProfileDao,
    private val progressDao: ProgressDao,
    private val syncStateDao: SyncStateDao,
    private val gateway: AudiobookshelfGateway,
    private val clock: AppClock,
    private val logger: Logger,
    @Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : LibraryRepository {

    override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> =
        serverIdFlow(profileId).flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(emptyList())
            } else {
                libraryDao.observeLibraries(serverId.value).map { entities ->
                    entities.map { entity ->
                        EntityMappers.toDomain(entity, bookCount = libraryDao.countBooks(entity.libraryKey))
                    }
                }
            }
        }

    override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
        serverIdFlow(profileId).flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(null)
            } else {
                val key = EntityKey.of(serverId.value, libraryId.value)
                libraryDao.observeLibrary(key).map { entity ->
                    entity?.let { EntityMappers.toDomain(it, libraryDao.countBooks(key)) }
                }
            }
        }

    override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
        serverIdFlow(profileId).flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(emptyList())
            } else {
                val libraryKey = EntityKey.of(serverId.value, libraryId.value)
                combine(
                    libraryDao.observeBooks(libraryKey),
                    progressDao.observeProgressFor(profileId.value),
                ) { books, progress ->
                    val byBookKey = progress.associateBy(MediaProgressEntity::bookKey)
                    books.map { relations ->
                        EntityMappers.toDomain(relations, byBookKey[relations.book.bookKey])
                    }
                }
            }
        }

    override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
        serverIdFlow(profileId).flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(null)
            } else {
                val bookKey = EntityKey.of(serverId.value, bookId.value)
                combine(
                    libraryDao.observeBook(bookKey),
                    progressDao.observeProgress(profileId.value, bookKey),
                ) { relations, progress ->
                    relations?.let { EntityMappers.toDomain(it, progress) }
                }
            }
        }

    override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
        serverIdFlow(profileId).flatMapLatest { serverId ->
            syncStateDao.observeSyncState(profileId.value).map { entity ->
                entity?.let(EntityMappers::toDomain)
                    ?: SyncState.idle(serverId ?: UNKNOWN_SERVER, profileId)
            }
        }

    /**
     * PRODUCT_SPEC LIB-001 — the whole refresh, or none of it.
     *
     * The gateway calls happen outside the transaction (they can be slow and can fail), and only a
     * fully materialised result is written. That is what makes "a failed sync leaves cached content
     * on screen" true rather than aspirational.
     */
    override suspend fun refresh(profileId: ProfileId): AppResult<Int> = withContext(ioDispatcher) {
        markSyncing(profileId)

        when (val libraries = gateway.library.listLibraries(profileId)) {
            is AppResult.Failure -> {
                recordFailure(profileId, libraries.error)
                logger.warn(
                    LogCategory.Sync,
                    "Library refresh failed",
                    LogField.Public("errorCode", libraries.error.code),
                    LogField.Public("retryable", libraries.error.isRetryable),
                )
                libraries
            }

            is AppResult.Success -> {
                val snapshots = mutableMapOf<LibraryId, List<BookSnapshot>>()
                for (library in libraries.value) {
                    when (val books = gateway.library.listBooks(profileId, library.id)) {
                        is AppResult.Failure -> {
                            recordFailure(profileId, books.error)
                            return@withContext books
                        }

                        is AppResult.Success -> snapshots[library.id] = books.value
                    }
                }

                val written = persist(libraries.value, snapshots)
                recordSuccess(profileId, libraries.value.firstOrNull()?.serverId)
                logger.info(
                    LogCategory.Sync,
                    "Library refresh completed",
                    LogField.Count("libraries", libraries.value.size),
                    LogField.Count("books", written),
                )
                AppResult.Success(written)
            }
        }
    }

    private suspend fun persist(libraries: List<Library>, snapshots: Map<LibraryId, List<BookSnapshot>>): Int {
        var written = 0
        database.runInTransaction {
            libraryDao.upsertLibraries(libraries.map(EntityMappers::toEntity))
            libraries.forEach { library ->
                val rows = snapshots[library.id].orEmpty().map(EntityMappers::toEntities)
                libraryDao.upsertAuthors(rows.flatMap { it.authors }.distinctBy { it.authorKey })
                libraryDao.upsertSeries(rows.flatMap { it.series }.distinctBy { it.seriesKey })
                libraryDao.upsertBooks(rows.map { it.book })
                rows.forEach { row ->
                    libraryDao.deleteAuthorLinksFor(row.book.bookKey)
                    libraryDao.deleteSeriesLinksFor(row.book.bookKey)
                    libraryDao.deleteTracksFor(row.book.bookKey)
                    libraryDao.deleteChaptersFor(row.book.bookKey)
                }
                libraryDao.upsertBookAuthors(rows.flatMap { it.authorLinks })
                libraryDao.upsertBookSeries(rows.flatMap { it.seriesLinks })
                libraryDao.upsertTracks(rows.flatMap { it.tracks })
                libraryDao.upsertChapters(rows.flatMap { it.chapters })
                progressDao.upsertProgress(rows.mapNotNull { it.progress })
                val libraryKey = EntityKey.of(library.serverId.value, library.id.value)
                if (rows.isEmpty()) {
                    libraryDao.markAllBooksDeleted(libraryKey)
                } else {
                    libraryDao.markMissingBooksDeleted(libraryKey, rows.map { it.book.bookKey })
                }
                written += rows.size
            }
        }
        return written
    }

    private fun serverIdFlow(profileId: ProfileId): Flow<ServerId?> =
        profileDao.observeProfile(profileId.value).map { entity ->
            entity?.serverId?.let(::ServerId)
        }

    private suspend fun markSyncing(profileId: ProfileId) {
        val serverId = profileDao.findProfile(profileId.value)?.serverId?.let(::ServerId)
            ?: UNKNOWN_SERVER
        syncStateDao.upsertSyncState(
            EntityMappers.toEntity(
                SyncState(
                    serverId = serverId,
                    profileId = profileId,
                    status = SyncStatus.Syncing,
                    lastSuccessfulSyncAt = existingSuccessAt(profileId),
                    lastAttemptedAt = clock.now(),
                    lastError = null,
                ),
            ),
        )
    }

    private suspend fun recordSuccess(profileId: ProfileId, serverId: ServerId?) {
        val now = clock.now()
        syncStateDao.upsertSyncState(
            EntityMappers.toEntity(
                SyncState(
                    serverId = serverId ?: UNKNOWN_SERVER,
                    profileId = profileId,
                    status = SyncStatus.Succeeded,
                    lastSuccessfulSyncAt = now,
                    lastAttemptedAt = now,
                    lastError = null,
                ),
            ),
        )
    }

    private suspend fun recordFailure(profileId: ProfileId, error: AppError) {
        val serverId = profileDao.findProfile(profileId.value)?.serverId?.let(::ServerId)
            ?: UNKNOWN_SERVER
        syncStateDao.upsertSyncState(
            EntityMappers.toEntity(
                SyncState(
                    serverId = serverId,
                    profileId = profileId,
                    status = SyncStatus.Failed,
                    // PRODUCT_SPEC LIB-001: the previous successful sync is preserved so the UI can
                    // say how stale the cached content is instead of blanking the screen.
                    lastSuccessfulSyncAt = existingSuccessAt(profileId),
                    lastAttemptedAt = clock.now(),
                    lastError = error,
                ),
            ),
        )
    }

    private suspend fun existingSuccessAt(profileId: ProfileId): Instant? =
        syncStateDao.findSyncState(profileId.value)?.lastSuccessfulSyncAt?.let(Instant::ofEpochMilli)

    private companion object {
        /**
         * Used only when a profile row disappeared between two steps of a refresh. Sync state is
         * keyed by profile, so the row still resolves; the server id is cosmetic in that case.
         */
        val UNKNOWN_SERVER = ServerId("unknown")
    }
}
