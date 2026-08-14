package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.DownloadDao
import com.example.shelfplayer.core.database.entity.DownloadRequestEntity
import com.example.shelfplayer.core.database.entity.DownloadedBookEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-002 / DL-003 — the offline manifest against Room.
 *
 * ### Where the safety actually is
 *
 * Two of DL-003's criteria are enforced by the schema rather than by anything here, deliberately: the
 * cascade from `profiles` decrements a book's reference count when a profile is deleted, and the cascade
 * from `downloaded_books` removes a book's files with it. Code that has to remember to do those is code
 * that will one day not.
 *
 * What is left for this class is the part a constraint cannot express — that a book becomes `Complete` only
 * when every one of its files is, and that releasing a claim never deletes anything.
 */
@Singleton
class DefaultDownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
    private val clock: AppClock,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {

    override fun observeAll(): Flow<List<OfflineBook>> =
        downloadDao.observeAll().map { rows -> rows.map(DownloadMappers::toDomain) }

    override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> =
        downloadDao.observe(keyOf(serverId, itemId)).map { row -> row?.let(DownloadMappers::toDomain) }

    override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> =
        downloadDao.observeCompletedFor(profileId.value).map { keys ->
            keys.map { key -> LibraryItemId(EntityKey.remoteIdOf(key)) }.toSet()
        }

    override fun observeTotalBytes(): Flow<Long> = downloadDao.observeTotalBytes()

    override suspend fun freeBytes(): Long = withContext(ioDispatcher) { storage.usableBytes() }

    override suspend fun request(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        files: List<OfflineFile>,
    ): AppResult<OfflineBook> = io {
        val key = keyOf(serverId, itemId)
        val now = clock.now()
        val existing = downloadDao.find(key)

        // A book already here keeps its manifest untouched: this is the shared-copy case, and rewriting the
        // rows would reset a transfer that may be running. Only the claim is new.
        if (existing == null) {
            downloadDao.upsertBook(
                DownloadedBookEntity(
                    bookKey = key,
                    serverId = serverId.value,
                    remoteItemId = itemId.value,
                    state = DownloadState.Queued.name,
                    storageTreeUri = null,
                    coverUri = null,
                    failureSummary = null,
                    createdAt = now.toEpochMilli(),
                    updatedAt = now.toEpochMilli(),
                ),
            )
            downloadDao.upsertFiles(files.map { file -> DownloadMappers.toEntity(key, file) })
        }

        downloadDao.addRequest(
            DownloadRequestEntity(
                bookKey = key,
                profileId = profileId.value,
                requestedAt = now.toEpochMilli(),
                isPinned = false,
            ),
        )
        requireStored(key)
    }

    override suspend fun updateFile(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile): AppResult<Unit> =
        io {
            val key = keyOf(serverId, itemId)
            checkNotNull(downloadDao.find(key)) { "no manifest to update" }
            downloadDao.upsertFiles(listOf(DownloadMappers.toEntity(key, file)))
            touch(key, state = null)
        }

    /**
     * PRODUCT_SPEC DL-001 — "a book becomes `Downloaded` only after all required audio tracks, cover, and
     * offline manifest are committed", and an atomic commit prevents a crash from faking that.
     *
     * The check is against the *files*, not against what the caller believes. A downloader that lost track
     * of one part would otherwise be able to declare a book playable offline, and the failure would surface
     * as silence on the third track in a car.
     *
     * The cover is not part of the check. DL-001 lists it, but a server that has no artwork for an item is
     * ordinary and a book with every audio file is completely listenable — refusing to complete would leave
     * a permanently stuck download for a cosmetic gap.
     */
    override suspend fun markComplete(
        serverId: ServerId,
        itemId: LibraryItemId,
        coverUri: String?,
    ): AppResult<OfflineBook> = io {
        val key = keyOf(serverId, itemId)
        val stored = DownloadMappers.toDomain(checkNotNull(downloadDao.find(key)) { "no manifest to complete" })
        require(stored.isComplete) {
            "refusing to mark complete: ${stored.files.count { it.state != DownloadState.Complete }} of " +
                "${stored.files.size} file(s) are not committed"
        }
        touch(key, state = DownloadState.Complete, coverUri = coverUri, clearFailure = true)
        requireStored(key)
    }

    override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> = io {
        touch(keyOf(serverId, itemId), state = DownloadState.Failed, failureSummary = summary)
    }

    override suspend fun setPinned(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        isPinned: Boolean,
    ): AppResult<Unit> = io {
        downloadDao.setPinned(keyOf(serverId, itemId), profileId.value, isPinned)
    }

    override suspend fun release(serverId: ServerId, itemId: LibraryItemId, profileId: ProfileId): AppResult<Boolean> =
        io {
            val key = keyOf(serverId, itemId)
            downloadDao.removeRequest(key, profileId.value)
            downloadDao.referenceCount(key) > 0
        }

    override suspend fun unreferenced(): AppResult<List<OfflineBook>> = io {
        downloadDao.unreferencedBookKeys().mapNotNull { key ->
            downloadDao.find(key)?.let(DownloadMappers::toDomain)
        }
    }

    override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = io {
        downloadDao.deleteManifest(keyOf(serverId, itemId))
    }

    private suspend fun requireStored(key: String): OfflineBook =
        DownloadMappers.toDomain(checkNotNull(downloadDao.find(key)) { "manifest disappeared after writing it" })

    /**
     * Rewrites the book row's mutable half.
     *
     * A read-then-write rather than a targeted `UPDATE` per column: there are five of them and they change
     * together, and five one-column statements is five chances for one to be forgotten. The row is small and
     * the caller is never on the playback path.
     */
    private suspend fun touch(
        key: String,
        state: DownloadState?,
        coverUri: String? = null,
        failureSummary: String? = null,
        clearFailure: Boolean = false,
    ) {
        val current = checkNotNull(downloadDao.find(key)) { "no manifest to update" }.book
        downloadDao.upsertBook(
            current.copy(
                state = state?.name ?: current.state,
                coverUri = coverUri ?: current.coverUri,
                failureSummary = if (clearFailure) null else failureSummary ?: current.failureSummary,
                updatedAt = clock.now().toEpochMilli(),
            ),
        )
    }

    private fun keyOf(serverId: ServerId, itemId: LibraryItemId): String = EntityKey.of(serverId.value, itemId.value)

    /**
     * ADR-0003 — the single exception boundary, on the IO dispatcher.
     *
     * `IllegalStateException` and `IllegalArgumentException` are the two this code raises on purpose — a
     * missing manifest and a premature completion — and both are programming errors in the caller rather
     * than conditions a user caused, so they map to [AppError.Unknown] with the cause kept.
     */
    private suspend fun <T> io(block: suspend () -> T): AppResult<T> =
        withContext(ioDispatcher) { resultOf { block() } }
}
