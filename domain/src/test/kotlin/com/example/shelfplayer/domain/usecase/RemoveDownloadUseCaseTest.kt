package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeDownloadScheduler
import com.example.shelfplayer.domain.FakeOfflineFiles
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.offlineBook
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PD-003 / DL-003 — removal is claim-aware: a profile never stops another profile's shared transfer.
 */
class RemoveDownloadUseCaseTest {

    private val book = LibraryItemId("b")
    private val other = ProfileId("profile-2")
    private val scheduler = FakeDownloadScheduler()
    private val files = FakeOfflineFiles()

    @Test
    fun `a sole claim in flight cancels the work and deletes the files`() = runTest {
        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(TEST_PROFILE)))

        assertEquals(AppResult.Success(DownloadRemoval.FilesDeleted), result)
        assertEquals(listOf(book), scheduler.cancelled)
        assertEquals(listOf(book), files.removed)
    }

    /** Revert-detector: the old code cancelled the shared work before looking at the claims. */
    @Test
    fun `a shared copy releases the claim and never cancels the other profile's work`() = runTest {
        files.refusals += book

        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(TEST_PROFILE, other)))

        assertEquals(AppResult.Success(DownloadRemoval.ClaimReleased), result)
        assertTrue(scheduler.cancelled.isEmpty(), "another profile's transfer must keep running")
        assertEquals(listOf(book), files.removed)
    }

    @Test
    fun `a copy that turned out unshared at delete time cancels the work afterwards`() = runTest {
        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(TEST_PROFILE, other)))

        assertEquals(AppResult.Success(DownloadRemoval.FilesDeleted), result)
        assertEquals(listOf(book), scheduler.cancelled)
    }

    @Test
    fun `a profile with no claim cancels and removes nothing`() = runTest {
        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(other)))

        assertEquals(AppResult.Success(DownloadRemoval.NotClaimed), result)
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(files.removed.isEmpty())
    }

    @Test
    fun `no active profile is a failure`() = runTest {
        val useCase = RemoveDownloadUseCase(
            FakeProfileRepository(null),
            FakeDownloadRepository(listOf(offlineBook("b"))),
            files,
            scheduler,
        )

        assertIs<AppResult.Failure>(useCase(book))
        assertTrue(scheduler.cancelled.isEmpty())
    }

    private suspend fun remove(stored: com.example.shelfplayer.core.model.download.OfflineBook) =
        RemoveDownloadUseCase(FakeProfileRepository(), FakeDownloadRepository(listOf(stored)), files, scheduler)(book)
}
