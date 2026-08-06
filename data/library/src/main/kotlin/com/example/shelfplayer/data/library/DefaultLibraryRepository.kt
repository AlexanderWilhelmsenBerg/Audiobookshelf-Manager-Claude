package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.dao.SyncStateDao
import com.example.shelfplayer.core.database.entity.BookWithRelations
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
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
    private val libraryDao: LibraryDao,
    private val profileDao: ProfileDao,
    private val progressDao: ProgressDao,
    private val syncStateDao: SyncStateDao,
    private val gateway: AudiobookshelfGateway,
    private val writer: LibrarySnapshotWriter,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : LibraryRepository {

    override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> =
        scopeFlow(profileId).flatMapLatest { scope ->
            if (scope == null) {
                flowOf(emptyList())
            } else {
                libraryDao.observeLibraries(scope.serverId.value).map { entities ->
                    entities
                        .filter { scope.access.allows(LibraryId(it.remoteId)) }
                        .map { entity ->
                            EntityMappers.toDomain(
                                entity,
                                // The count follows the same visibility rule as the list it labels. A
                                // library reading "490 books" that opens onto 188 is a worse answer than
                                // no count at all.
                                bookCount = libraryDao.countBooks(profileId.value, entity.libraryKey),
                            )
                        }
                }
            }
        }

    override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
        scopeFlow(profileId).flatMapLatest { scope ->
            if (scope == null || !scope.access.allows(libraryId)) {
                flowOf(null)
            } else {
                val key = EntityKey.of(scope.serverId.value, libraryId.value)
                libraryDao.observeLibrary(key).map { entity ->
                    entity?.let { EntityMappers.toDomain(it, libraryDao.countBooks(profileId.value, key)) }
                }
            }
        }

    override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
        scopeFlow(profileId).flatMapLatest { scope ->
            if (scope == null || !scope.access.allows(libraryId)) {
                flowOf(emptyList())
            } else {
                val libraryKey = EntityKey.of(scope.serverId.value, libraryId.value)
                withProgress(profileId, libraryDao.observeBooksIn(profileId.value, listOf(libraryKey)))
            }
        }

    /**
     * PRODUCT_SPEC LIB-002 / 5.2 — the whole shelf, minus anything this profile may not see.
     *
     * Two filters, and both are needed. The library grant is applied here, from the profile row; the
     * item grant is applied by the DAO's join onto the recorded visibility, because the server never
     * states it and only the shape of the catalogue it served reveals it.
     *
     * A grant of *all* libraries is one query on the server id. A narrower grant is a query on the
     * library keys it names, and an empty set short-circuits before reaching SQL: `IN ()` is a shape
     * this codebase already avoids (see `markMissingBooksDeleted`), and there is nothing to ask for.
     */
    override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> =
        scopeFlow(profileId).flatMapLatest { scope ->
            val keys = scope?.allowedLibraryKeys()
            when {
                scope == null -> flowOf(emptyList())
                keys == null -> withProgress(
                    profileId,
                    libraryDao.observeBooksOnServer(profileId.value, scope.serverId.value),
                )
                keys.isEmpty() -> flowOf(emptyList())
                else -> withProgress(profileId, libraryDao.observeBooksIn(profileId.value, keys))
            }
        }

    override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
        scopeFlow(profileId).flatMapLatest { scope ->
            if (scope == null) {
                flowOf(null)
            } else {
                val bookKey = EntityKey.of(scope.serverId.value, bookId.value)
                val allowedKeys = scope.allowedLibraryKeys()
                combine(
                    // The item grant is already applied by the query; the library grant still is not,
                    // and both matter. A revoked library and a revoked tag hide a book for different
                    // reasons, and the detail screen must not open it on either count.
                    libraryDao.observeBook(profileId.value, bookKey),
                    progressDao.observeProgress(profileId.value, bookKey),
                ) { relations, progress ->
                    relations
                        ?.takeIf { allowedKeys == null || it.book.libraryKey in allowedKeys }
                        ?.let { EntityMappers.toDomain(it, progress) }
                }
            }
        }

    /** Attaches the active profile's progress to a stream of book rows, and nobody else's. */
    private fun withProgress(profileId: ProfileId, books: Flow<List<BookWithRelations>>): Flow<List<Book>> =
        combine(books, progressDao.observeProgressFor(profileId.value)) { rows, progress ->
            val byBookKey = progress.associateBy(MediaProgressEntity::bookKey)
            rows.map { relations -> EntityMappers.toDomain(relations, byBookKey[relations.book.bookKey]) }
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
                val snapshots = mutableMapOf<LibraryId, LibrarySnapshot>()
                for (library in libraries.value) {
                    when (val books = gateway.library.listBooks(profileId, library.id)) {
                        is AppResult.Failure -> {
                            recordFailure(profileId, books.error)
                            return@withContext books
                        }

                        is AppResult.Success -> snapshots[library.id] = books.value
                    }
                }

                // PRODUCT_SPEC 5.2 — only an account the server does not filter may drive deletions.
                val access = profileDao.findProfile(profileId.value)?.let(EntityMappers::toLibraryAccess)
                    ?: LibraryAccess.None
                val written = writer.write(profileId, libraries.value, snapshots, reconciles = access.reconciles)
                // PRODUCT_SPEC LIB-001 — a sync that could not reach some items succeeded *partly*, and
                // says so. Recording it as a plain success would hide that the library on screen is
                // missing books; recording it as a failure would hide that most of it is current.
                val incomplete = snapshots.values.count { !it.isComplete }
                recordOutcome(
                    profileId = profileId,
                    serverId = libraries.value.firstOrNull()?.serverId,
                    status = if (incomplete == 0) SyncStatus.Succeeded else SyncStatus.PartiallySucceeded,
                )
                logger.info(
                    LogCategory.Sync,
                    "Library refresh completed",
                    LogField.Count("libraries", libraries.value.size),
                    LogField.Count("books", written),
                    LogField.Count("incompleteLibraries", incomplete),
                )
                AppResult.Success(written)
            }
        }
    }

    private fun serverIdFlow(profileId: ProfileId): Flow<ServerId?> =
        profileDao.observeProfile(profileId.value).map { entity ->
            entity?.serverId?.let(::ServerId)
        }

    /**
     * The server a profile belongs to and what it is allowed to see, observed together.
     *
     * Read as one row so the two can never disagree: resolving the server from one emission and the
     * grant from another is how a switch of profiles ends up showing one account's libraries under the
     * other account's permissions.
     */
    private fun scopeFlow(profileId: ProfileId): Flow<ProfileScope?> =
        profileDao.observeProfile(profileId.value).map { entity ->
            entity?.let {
                ProfileScope(serverId = ServerId(it.serverId), access = EntityMappers.toLibraryAccess(it))
            }
        }

    private data class ProfileScope(val serverId: ServerId, val access: LibraryAccess) {
        /** The library keys this profile may read, or `null` when the grant covers every library. */
        fun allowedLibraryKeys(): List<String>? = when {
            access.hasAllLibraryAccess -> null
            else -> access.accessibleLibraryIds.map { EntityKey.of(serverId.value, it.value) }
        }
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

    /**
     * Records a sync that wrote something — completely or partly.
     *
     * Both count as a successful sync for the purposes of `lastSuccessfulSyncAt`: content was written and
     * the staleness the UI reports is measured from here. The distinction lives in [status].
     */
    private suspend fun recordOutcome(profileId: ProfileId, serverId: ServerId?, status: SyncStatus) {
        val now = clock.now()
        syncStateDao.upsertSyncState(
            EntityMappers.toEntity(
                SyncState(
                    serverId = serverId ?: UNKNOWN_SERVER,
                    profileId = profileId,
                    status = status,
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
