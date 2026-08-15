package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeOfflineFiles
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.FakeSettingsRepository
import com.example.shelfplayer.domain.RecordingLogger
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.offlineBook
import com.example.shelfplayer.domain.progress
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-006 / ADR-0018 decision 7 — automatic removal, and the four things it refuses to touch.
 *
 * Every refusal has a test, because a cleanup that deletes the wrong book is worse than no cleanup: the
 * user loses a download they did not agree to lose, and in the playing case they lose the audio mid-
 * sentence. The happy path is one test; the refusals are six.
 */
class CleanUpDownloadsUseCaseTest {

    private val clock = TestAppClock(instant = NOW)
    private val files = FakeOfflineFiles()

    @Test
    fun `removes nothing while retention is off`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch")),
            housekeeping = DownloadHousekeeping.Default,
        )

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    @Test
    fun `removes a finished book once the retention period has passed`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch")),
        )

        assertEquals(AppResult.Success(1), useCase())
        assertEquals(listOf(LibraryItemId("tidewatch")), files.removed)
    }

    @Test
    fun `keeps a book finished more recently than the cutoff`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = NOW.minusSeconds(HOUR))),
            stored = listOf(offlineBook("tidewatch")),
        )

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    @Test
    fun `keeps a book that has not been finished`() = runTest {
        val unfinished = book("tidewatch").copy(
            progress = progress("tidewatch", isFinished = false, updatedAt = LONG_AGO),
        )
        val useCase = useCase(books = listOf(unfinished), stored = listOf(offlineBook("tidewatch")))

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    /** Product priority 1. Deleting the file under a running player is the worst outcome this app has. */
    @Test
    fun `keeps the book that is playing`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch")),
        )

        assertEquals(AppResult.Success(0), useCase(playingBookId = LibraryItemId("tidewatch")))
        assertTrue(files.removed.isEmpty())
    }

    /** The user said no, once, deliberately. */
    @Test
    fun `keeps a pinned download`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch", isPinned = true)),
        )

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    /**
     * Product priority 2. The listening exists only on this device until the outbox drains, and the local
     * files are what a re-sync would be about.
     */
    @Test
    fun `keeps a book whose progress has not reached the server`() = runTest {
        val unsynced = book("tidewatch").copy(
            progress = progress("tidewatch", updatedAt = LONG_AGO, hasUnsyncedChanges = true),
        )
        val useCase = useCase(books = listOf(unsynced), stored = listOf(offlineBook("tidewatch")))

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    /**
     * A download whose catalogue row is absent — removed upstream, or in a library this profile has lost
     * access to. PRODUCT_SPEC 5.2 says the profile may not *see* it, and DL-003 says losing access does not
     * delete downloads. It is left for a person to remove from the storage screen.
     */
    @Test
    fun `keeps a download the catalogue no longer knows about`() = runTest {
        val useCase = useCase(books = emptyList(), stored = listOf(offlineBook("tidewatch")))

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    @Test
    fun `ignores an incomplete download`() = runTest {
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch", state = DownloadState.Running)),
        )

        assertEquals(AppResult.Success(0), useCase())
        assertTrue(files.removed.isEmpty())
    }

    /** Removals report per book, so a shared copy that freed nothing is not counted as reclaimed space. */
    @Test
    fun `does not count a copy another profile still wants`() = runTest {
        files.refusals += LibraryItemId("tidewatch")
        val useCase = useCase(
            books = listOf(finished("tidewatch", finishedAt = LONG_AGO)),
            stored = listOf(offlineBook("tidewatch")),
        )

        assertEquals(AppResult.Success(0), useCase())
        assertEquals(listOf(LibraryItemId("tidewatch")), files.removed)
    }

    private fun useCase(
        books: List<Book>,
        stored: List<OfflineBook>,
        housekeeping: DownloadHousekeeping = DownloadHousekeeping(deleteFinishedAfterDays = 7),
    ) = CleanUpDownloadsUseCase(
        profiles = FakeProfileRepository(),
        library = FakeLibraryRepository(books),
        downloads = FakeDownloadRepository(stored),
        files = files,
        settings = FakeSettingsRepository(housekeeping),
        clock = clock,
        logger = RecordingLogger(),
    )

    private fun finished(id: String, finishedAt: Instant): Book =
        book(id).copy(progress = finishedProgress(id, finishedAt))

    private fun finishedProgress(id: String, finishedAt: Instant): MediaProgress =
        progress(id, isFinished = true, updatedAt = finishedAt)

    private companion object {
        const val HOUR = 3_600L

        val NOW: Instant = TEST_INSTANT.plusSeconds(HOUR * 24 * 365)

        /** Comfortably past a seven-day cutoff. */
        val LONG_AGO: Instant = NOW.minusSeconds(HOUR * 24 * 30)
    }
}
