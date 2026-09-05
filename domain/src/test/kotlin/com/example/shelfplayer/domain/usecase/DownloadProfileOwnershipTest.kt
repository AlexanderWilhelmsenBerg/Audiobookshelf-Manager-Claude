package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.TrafficCategory
import com.example.shelfplayer.domain.FakeBookAssetSource
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.profile
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
