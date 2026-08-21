package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.domain.FakeBookAssetSource
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeDownloadScheduler
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.offlineBook
import com.example.shelfplayer.domain.profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-001 — **pause is not failure**, and the distinction is the whole feature.
 *
 * ### What already worked, and what did not
 *
 * Every mechanical piece of pausing has been here since Phase 3: cancelling a job leaves the `.part` files
 * alone, and enqueueing again resumes from them. `docs/gaps.md` said so — *"the capability exists, there
 * is no button that says pause"*.
 *
 * What was missing was the app's ability to tell afterwards which had happened. A cancelled job leaves the
 * manifest reading `Failed`, so a listener who stopped a download deliberately came back to **"Download
 * failed"** and an offer to retry: the app apologising for having obeyed. These tests pin the state, not
 * the file handling, because the state is the part that was wrong.
 */
class PauseDownloadUseCaseTest {

    private val downloads = FakeDownloadRepository(listOf(offlineBook("tidewatch", state = DownloadState.Running)))
    private val scheduler = FakeDownloadScheduler()

    @Test
    fun `pausing stops the work and records a pause rather than a failure`() = runTest {
        assertEquals(AppResult.Success(Unit), pause()(LibraryItemId("tidewatch")))

        assertEquals(listOf(LibraryItemId("tidewatch")), scheduler.cancelled)
        assertEquals(DownloadState.Paused, stateOf("tidewatch"))
    }

    /**
     * Resuming goes back to `Queued` and re-enqueues.
     *
     * Through [DownloadBookUseCase], which is the point: the grant and the free space are re-checked. A
     * download paused a week ago is a fresh request as far as those two are concerned.
     */
    @Test
    fun `resuming queues the book again`() = runTest {
        pause()(LibraryItemId("tidewatch"))

        assertEquals(AppResult.Success(Unit), download()(LibraryItemId("tidewatch")))

        assertEquals(DownloadState.Queued, stateOf("tidewatch"))
        assertEquals(listOf(LibraryItemId("tidewatch")), scheduler.enqueued)
    }

    /**
     * **The guard that matters.** An unattended caller leaves a paused book alone.
     *
     * A listener who stopped a download on a metered train must not have it restarted because the app
     * noticed Wi-Fi. Smart download passes `isAutomatic = true` for exactly this, and the result is a
     * success rather than a failure — nothing went wrong, the book is simply not wanted right now.
     */
    @Test
    fun `an automatic caller does not resume a paused download`() = runTest {
        pause()(LibraryItemId("tidewatch"))

        assertEquals(AppResult.Success(Unit), download()(LibraryItemId("tidewatch"), isAutomatic = true))

        assertEquals(DownloadState.Paused, stateOf("tidewatch"))
        assertTrue(scheduler.enqueued.isEmpty(), "an automatic pass must not enqueue a paused book")
    }

    /** A book nobody paused is unaffected by the guard: the automatic path still downloads normally. */
    @Test
    fun `an automatic caller still downloads a book that is not paused`() = runTest {
        assertEquals(AppResult.Success(Unit), download()(LibraryItemId("saltmarsh"), isAutomatic = true))

        assertEquals(listOf(LibraryItemId("saltmarsh")), scheduler.enqueued)
    }

    /**
     * Resuming a *completed* book does not drag it back to `Queued`.
     *
     * `markQueued` only undoes a pause. Without that guard a stale tap on a row that finished while the
     * screen was open would mark a playable book as pending, and the offline shelf would hide it.
     */
    @Test
    fun `a completed book keeps its state when the download path runs again`() = runTest {
        val repository = FakeDownloadRepository(listOf(offlineBook("tidewatch", state = DownloadState.Complete)))

        download(repository)(LibraryItemId("tidewatch"))

        assertEquals(DownloadState.Complete, repository.observe(TEST_SERVER, LibraryItemId("tidewatch")).first()?.state)
    }

    private suspend fun stateOf(id: String) = downloads.observe(TEST_SERVER, LibraryItemId(id)).first()?.state

    private fun pause() = PauseDownloadUseCase(
        profiles = FakeProfileRepository(profile().copy(canDownload = true)),
        downloads = downloads,
        scheduler = scheduler,
    )

    private fun download(repository: FakeDownloadRepository = downloads) = DownloadBookUseCase(
        profiles = FakeProfileRepository(profile().copy(canDownload = true)),
        assets = FakeBookAssetSource(),
        downloads = repository,
        scheduler = scheduler,
    )
}
