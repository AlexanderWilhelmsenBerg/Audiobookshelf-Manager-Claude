package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
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
import kotlinx.coroutines.flow.first
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
        val stored = offlineBook("b", state = DownloadState.Running, requestedBy = setOf(other))
        val downloads = FakeDownloadRepository(listOf(stored))

        val result = RemoveDownloadUseCase(FakeProfileRepository(), downloads, files, scheduler)(book)

        assertEquals(AppResult.Success(DownloadRemoval.NotClaimed), result)
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(files.removed.isEmpty())
        // No repository mutation either: the manifest is exactly what it was and nothing was pinned.
        assertEquals(listOf(stored), downloads.observeAll().first())
        assertTrue(downloads.pinned.isEmpty())
    }

    @Test
    fun `a book with no manifest at all is not claimed`() = runTest {
        val result = RemoveDownloadUseCase(FakeProfileRepository(), FakeDownloadRepository(), files, scheduler)(book)

        assertEquals(AppResult.Success(DownloadRemoval.NotClaimed), result)
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(files.removed.isEmpty())
    }

    /** A failed delete is reported as one, and a shared copy's work is never cancelled after the fact. */
    @Test
    fun `a failed delete on a shared copy propagates and cancels nothing`() = runTest {
        files.failure = AppError.Storage(summary = "disk")

        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(TEST_PROFILE, other)))

        assertIs<AppResult.Failure>(result)
        assertTrue(scheduler.cancelled.isEmpty(), "no post-delete cancel when the delete failed")
    }

    @Test
    fun `a failed delete on a sole claim propagates and cancels only before the delete`() = runTest {
        files.failure = AppError.Storage(summary = "disk")

        val result = remove(offlineBook("b", state = DownloadState.Running, requestedBy = setOf(TEST_PROFILE)))

        assertIs<AppResult.Failure>(result)
        assertEquals(listOf(book), scheduler.cancelled, "exactly the one pre-delete cancel")
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
