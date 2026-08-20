package com.example.shelfplayer.domain

import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.StartupMode
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.BookAssets
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Fakes for the download half of `:domain`.
 *
 * Separate from [TestFixtures.kt][TEST_SERVER]'s file because the two are read by different tests and the
 * catalogue fixtures were already long enough to be hard to navigate.
 */

internal fun progress(
    bookId: String,
    isFinished: Boolean = true,
    updatedAt: Instant = TEST_INSTANT,
    hasUnsyncedChanges: Boolean = false,
) = MediaProgress(
    serverId = TEST_SERVER,
    profileId = TEST_PROFILE,
    bookId = LibraryItemId(bookId),
    position = 9.minutes,
    duration = 10.minutes,
    isFinished = isFinished,
    updatedAt = updatedAt,
    hasUnsyncedChanges = hasUnsyncedChanges,
)

internal fun offlineBook(
    id: String,
    isPinned: Boolean = false,
    state: DownloadState = DownloadState.Complete,
    requestedBy: Set<ProfileId> = setOf(TEST_PROFILE),
    createdAt: Instant = TEST_INSTANT,
) = OfflineBook(
    serverId = TEST_SERVER,
    itemId = LibraryItemId(id),
    state = state,
    files = listOf(
        OfflineFile(
            remoteFileId = "$id-1",
            index = 1,
            uri = "file:///downloads/$id/1.mp3",
            state = state,
            expectedBytes = 1_024,
            downloadedBytes = if (state == DownloadState.Complete) 1_024 else 512,
            mimeType = "audio/mpeg",
            duration = null,
            eTag = null,
            lastModified = null,
        ),
    ),
    coverUri = null,
    requestedBy = requestedBy,
    isPinned = isPinned,
    createdAt = createdAt,
    updatedAt = createdAt,
)

/**
 * The filesystem, as a list of the books it was asked to delete.
 *
 * What every requirement here is about is *which* books get removed and which are refused, so recording the
 * calls is the whole assertion surface. [refusals] lets a test make one removal report the shared-copy
 * outcome — a success that freed nothing — which is a different result from a failure.
 */
internal class FakeOfflineFiles : OfflineFiles {
    val removed = mutableListOf<LibraryItemId>()

    /** Books whose removal reports "another profile still wants this", i.e. success with `false`. */
    val refusals = mutableSetOf<LibraryItemId>()

    var failure: AppError? = null

    override suspend fun remove(profileId: ProfileId, serverId: ServerId, bookId: LibraryItemId): AppResult<Boolean> {
        failure?.let { return AppResult.Failure(it) }
        removed += bookId
        return AppResult.Success(bookId !in refusals)
    }

    override suspend fun discardPartials(serverId: ServerId, bookId: LibraryItemId): AppResult<Long> =
        AppResult.Success(0)

    override suspend fun sweepOrphans(): AppResult<Long> = AppResult.Success(0)
}

/** The manifest, held in memory. Only the reads these use cases perform are implemented. */
internal class FakeDownloadRepository(stored: List<OfflineBook> = emptyList()) : DownloadRepository {
    private val books = MutableStateFlow(stored)

    val pinned = mutableListOf<Pair<LibraryItemId, Boolean>>()

    override fun observeAll(): Flow<List<OfflineBook>> = books

    override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> =
        books.map { all -> all.firstOrNull { it.itemId == itemId } }

    override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> =
        books.map { all -> all.filter { it.isComplete }.map(OfflineBook::itemId).toSet() }

    override fun observeTotalBytes(): Flow<Long> = books.map { all -> all.sumOf(OfflineBook::downloadedBytes) }

    var freeBytes: Long = Long.MAX_VALUE

    override suspend fun freeBytes(): Long = freeBytes

    override suspend fun request(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        files: List<OfflineFile>,
    ): AppResult<OfflineBook> {
        val existing = books.value.firstOrNull { it.itemId == itemId }
        if (existing != null) return AppResult.Success(existing)
        val created = offlineBook(itemId.value, state = DownloadState.Queued, requestedBy = setOf(profileId))
        books.value += created
        return AppResult.Success(created)
    }

    override suspend fun updateFile(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun markComplete(
        serverId: ServerId,
        itemId: LibraryItemId,
        coverUri: String?,
    ): AppResult<OfflineBook> = AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))

    override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> =
        AppResult.Success(Unit)

    /** The state transitions the pause tests assert on, applied to the in-memory manifest. */
    override suspend fun markPaused(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> {
        books.value = books.value.map { if (it.itemId == itemId) it.copy(state = DownloadState.Paused) else it }
        return AppResult.Success(Unit)
    }

    /** Paused to queued only, exactly as the real one: see `DownloadRepository.markQueued`. */
    override suspend fun markQueued(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> {
        books.value = books.value.map {
            if (it.itemId == itemId && it.state == DownloadState.Paused) it.copy(state = DownloadState.Queued) else it
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setPinned(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        isPinned: Boolean,
    ): AppResult<Unit> {
        pinned += itemId to isPinned
        return AppResult.Success(Unit)
    }

    override suspend fun release(serverId: ServerId, itemId: LibraryItemId, profileId: ProfileId): AppResult<Boolean> =
        AppResult.Success(false)

    override suspend fun unreferenced(): AppResult<List<OfflineBook>> = AppResult.Success(emptyList())

    override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = AppResult.Success(Unit)
}

/** Only the housekeeping half is real; the rest of the settings surface is not read by these use cases. */
internal class FakeSettingsRepository(
    housekeeping: DownloadHousekeeping = DownloadHousekeeping.Default,
    /** PRODUCT_SPEC ROUTE-003 — the one playback setting a domain test currently dictates. */
    private val startupMode: StartupMode = StartupMode.Default,
) : PlaybackSettingsRepository {
    private val stored = MutableStateFlow(housekeeping)

    override fun observeHousekeeping(): Flow<DownloadHousekeeping> = stored

    override suspend fun setHousekeeping(housekeeping: DownloadHousekeeping): AppResult<Unit> {
        stored.value = housekeeping
        return AppResult.Success(Unit)
    }

    override fun observeSettings(): Flow<PlaybackSettings> = flowOf(PlaybackSettings(startupMode = startupMode))

    override suspend fun setFocusBehaviour(behaviour: FocusBehaviour): AppResult<Unit> = notUsed()

    override suspend fun setStartupMode(mode: StartupMode): AppResult<Unit> = notUsed()

    override suspend fun setDefaultSpeed(speed: PlaybackSpeed): AppResult<Unit> = notUsed()

    override suspend fun setSkipIntervals(skips: SkipIntervals): AppResult<Unit> = notUsed()

    override suspend fun setAutoRewind(rewind: AutoRewind): AppResult<Unit> = notUsed()

    override suspend fun setBufferPreset(preset: BufferPreset): AppResult<Unit> = notUsed()

    override fun observeNetworkPolicy(): Flow<NetworkPolicy> = flowOf(NetworkPolicy.Default)

    override suspend fun setNetworkPolicy(policy: NetworkPolicy): AppResult<Unit> = notUsed()

    override suspend fun setAutoPlayOnCarConnect(enabled: Boolean): AppResult<Unit> = notUsed()

    override fun observeSpeedFor(bookId: LibraryItemId): Flow<PlaybackSpeed?> = flowOf(null)

    override suspend fun speedFor(bookId: LibraryItemId): PlaybackSpeed = PlaybackSpeed.Normal

    override suspend fun setSpeedFor(bookId: LibraryItemId, speed: PlaybackSpeed?): AppResult<Unit> = notUsed()

    private fun <T> notUsed(): AppResult<T> =
        AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
}

/** Records what was queued. What the requirement says is *which* book, not what WorkManager did with it. */
internal class FakeDownloadScheduler : DownloadScheduler {
    val enqueued = mutableListOf<LibraryItemId>()
    val cancelled = mutableListOf<LibraryItemId>()

    override suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId) {
        enqueued += itemId
    }

    override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) {
        cancelled += itemId
    }
}

/** Every book has one file of a kilobyte, which is enough for a use case that never opens one. */
internal class FakeBookAssetSource : BookAssetSource {
    override suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets> =
        AppResult.Success(
            BookAssets(
                files = offlineBook(bookId.value, state = DownloadState.Queued).files,
                coverUrl = null,
                estimatedBytes = 1_024,
            ),
        )
}

internal class RecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}
