package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import com.example.shelfplayer.domain.download.DownloadLocations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineStorageAvailabilityTest {

    @Test
    fun `known removable copy becomes playable again when its owner returns without manifest mutation`() {
        val file = File.createTempFile("bookwave-offline", ".mp3")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val locations = MutableLocations(DownloadStorageState.Unavailable)
            val manifest = manifest(file)

            assertFalse(manifest.isLocalStorageReachable(locations))

            locations.state = DownloadStorageState.Available

            assertTrue(manifest.isLocalStorageReachable(locations))
            assertTrue(manifest.isComplete, "availability changes must not rewrite durable download state")
            assertTrue(file.isFile, "recovery must reuse the existing physical copy")
        } finally {
            file.delete()
        }
    }

    private fun manifest(file: File) = OfflineBook(
        serverId = ServerId("server"),
        itemId = LibraryItemId("book"),
        state = DownloadState.Complete,
        storageVolumeUuid = "removable-owner",
        files = listOf(
            OfflineFile(
                remoteFileId = "file",
                index = 0,
                uri = file.toURI().toString(),
                state = DownloadState.Complete,
                expectedBytes = file.length(),
                downloadedBytes = file.length(),
                mimeType = "audio/mpeg",
                duration = null,
                eTag = null,
                lastModified = null,
            ),
        ),
        coverUri = null,
        requestedBy = setOf(ProfileId("profile")),
        isPinned = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class MutableLocations(var state: DownloadStorageState) : DownloadLocations {
        override suspend fun options(): List<StorageVolumeOption> = emptyList()
        override fun observeSelected(): Flow<String> = flowOf(StorageVolumeOption.INTERNAL_UUID)
        override fun availability(volumeUuid: String?): DownloadStorageState = state
        override suspend fun select(uuid: String): AppResult<Unit> = AppResult.Success(Unit)
    }
}
