package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * PRODUCT_SPEC DL-001 — a download repository for tests that are not about downloads.
 *
 * Every method that could produce a file refuses rather than pretending, because a fake that produced
 * bytes would let a test believe a download had happened — which is precisely the belief the download
 * layer exists to make impossible. Only the observers answer, and they answer empty.
 *
 * Shared rather than copied: it was private to `DefaultPlaybackRepositoryTest` until `ProgressSyncChainTest`
 * needed the same thing, and two of these drifting apart is how a test starts asserting against a
 * repository the app does not have.
 */
internal object NoDownloads : DownloadRepository {
    override fun observeAll(): Flow<List<OfflineBook>> = flowOf(emptyList())
    override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> = flowOf(null)
    override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> = flowOf(emptySet())
    override fun observeTotalBytes(): Flow<Long> = flowOf(0)
    override suspend fun freeBytes(): Long = Long.MAX_VALUE
    override suspend fun request(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        files: List<OfflineFile>,
    ): AppResult<OfflineBook> = unsupported()

    override suspend fun updateFile(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile): AppResult<Unit> =
        unsupported()

    override suspend fun markComplete(
        serverId: ServerId,
        itemId: LibraryItemId,
        coverUri: String?,
    ): AppResult<OfflineBook> = unsupported()

    override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> =
        unsupported()

    override suspend fun markPaused(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

    override suspend fun markQueued(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

    override suspend fun setPinned(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        isPinned: Boolean,
    ): AppResult<Unit> = unsupported()

    override suspend fun release(serverId: ServerId, itemId: LibraryItemId, profileId: ProfileId): AppResult<Boolean> =
        unsupported()

    override suspend fun unreferenced(): AppResult<List<OfflineBook>> = unsupported()
    override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

    private fun <T> unsupported(): AppResult<T> =
        AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this test"))
}
