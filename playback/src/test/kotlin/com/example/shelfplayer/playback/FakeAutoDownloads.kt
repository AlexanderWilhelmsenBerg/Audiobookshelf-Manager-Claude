package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Empty download manifest for Android Auto browse tests that are not exercising offline artwork. */
internal object FakeAutoDownloads : DownloadRepository {
    override fun observeAll(): Flow<List<OfflineBook>> = flowOf(emptyList())

    override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> = flowOf(null)

    override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> = flowOf(emptySet())

    override fun observeTotalBytes(): Flow<Long> = flowOf(0L)

    override suspend fun freeBytes(): Long = 0L

    override suspend fun request(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        files: List<OfflineFile>,
    ): AppResult<OfflineBook> =
        error("Not used by Android Auto browse tests")

    override suspend fun updateFile(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile): AppResult<Unit> =
        error("Not used by Android Auto browse tests")

    override suspend fun markComplete(
        serverId: ServerId,
        itemId: LibraryItemId,
        coverUri: String?,
    ): AppResult<OfflineBook> =
        error("Not used by Android Auto browse tests")

    override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> =
        error("Not used by Android Auto browse tests")

    override suspend fun markPaused(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
        error("Not used by Android Auto browse tests")

    override suspend fun markQueued(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
        error("Not used by Android Auto browse tests")

    override suspend fun setPinned(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        isPinned: Boolean,
    ): AppResult<Unit> =
        error("Not used by Android Auto browse tests")

    override suspend fun release(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
    ): AppResult<Boolean> =
        error("Not used by Android Auto browse tests")

    override suspend fun unreferenced(): AppResult<List<OfflineBook>> = error("Not used by Android Auto browse tests")

    override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> =
        error("Not used by Android Auto browse tests")
}
