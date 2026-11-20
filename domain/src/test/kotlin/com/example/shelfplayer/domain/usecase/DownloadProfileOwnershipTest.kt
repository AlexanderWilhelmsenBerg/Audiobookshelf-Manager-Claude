package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.TrafficCategory
import com.example.shelfplayer.domain.FakeBookAssetSource
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.download.BookAssets
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.offlineBook
import com.example.shelfplayer.domain.profile
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class DownloadProfileOwnershipTest {

    @Test
    fun `the profile that authorizes a download is the profile persisted with the job`() = runTest {
        val scheduler = RecordingOwnershipScheduler()
        val useCase = DownloadBookUseCase(
            profiles = FakeProfileRepository(profile().copy(canDownload = true)),
            assets = FakeBookAssetSource(),
            downloads = FakeDownloadRepository(),
            scheduler = scheduler,
        )

        useCase(LibraryItemId("book-a"))

        assertEquals(TEST_PROFILE, scheduler.owner)
    }

    @Test
    fun `retry queues a newly required track when space fits just that track`() = runTest {
        val old = offlineBook("book-a")
        val first = old.files.single()
        val added = first.copy(
            remoteFileId = "book-a-2",
            index = 2,
            uri = "",
            state = DownloadState.Queued,
            expectedBytes = 128,
            downloadedBytes = 0,
        )
        val assets = object : BookAssetSource {
            override suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets> =
                AppResult.Success(BookAssets(files = listOf(first, added), coverUrl = null, estimatedBytes = 1_152))
        }
        val downloads = object : DownloadRepository by FakeDownloadRepository(listOf(old)) {
            override suspend fun freeBytes() = 200L
        }
        val scheduler = RecordingOwnershipScheduler()
        val useCase = DownloadBookUseCase(
            profiles = FakeProfileRepository(profile().copy(canDownload = true)),
            assets = assets,
            downloads = downloads,
            scheduler = scheduler,
        )

        assertEquals(AppResult.Success(Unit), useCase(old.itemId))
        assertEquals(TEST_PROFILE, scheduler.owner)
    }

    private class RecordingOwnershipScheduler : DownloadScheduler {
        var owner: ProfileId? = null
            private set

        override suspend fun enqueue(
            profileId: ProfileId,
            serverId: ServerId,
            itemId: LibraryItemId,
            category: TrafficCategory,
        ) {
            owner = profileId
        }

        override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) = Unit
    }
}
