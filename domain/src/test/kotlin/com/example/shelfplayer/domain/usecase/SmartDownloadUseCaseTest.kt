package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.TrafficCategory
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.FakeBookAssetSource
import com.example.shelfplayer.domain.FakeDownloadRepository
import com.example.shelfplayer.domain.FakeDownloadScheduler
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeOfflineFiles
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.FakeSettingsRepository
import com.example.shelfplayer.domain.RecordingLogger
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.profile
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-005 / ADR-0018 decisions 1 and 7 — the halfway trigger.
 *
 * The durations here are in whatever unit the caller journals in; the use case only compares them with each
 * other, so a hundred-unit book with its mark at fifty is the clearest way to write these.
 */
class SmartDownloadUseCaseTest {

    private val scheduler = FakeDownloadScheduler()
    private val files = FakeOfflineFiles()

    @Test
    fun `does nothing while the setting is off`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping.Default)

        useCase(LibraryItemId("book-2"), previousPosition = 40, position = 60, duration = 100)

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `fetches the next book in the series when the halfway mark is crossed`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-2"), previousPosition = 40, position = 60, duration = 100)

        assertEquals(listOf(LibraryItemId("book-3")), scheduler.enqueued)
    }

    /**
     * PRODUCT_SPEC DL-004 — **and it is queued as a smart download, not as a manual one.**
     *
     * `NetworkPolicy` keeps `smartDownloadsOnCellular` separate from `downloadsOnCellular` on the stated
     * reasoning that "a manual download is a decision the user just made and a smart one is the app
     * deciding for them". The scheduler is where that becomes a WorkManager constraint, and it can only
     * apply the right one if it is told which this is — so the category travelling with the enqueue is the
     * whole of the enforcement.
     *
     * Before this assertion existed the scheduler was hard-coded to `ManualDownload`, so a listener who
     * allowed cellular for downloads but not for smart downloads got smart downloads on cellular anyway:
     * a setting that could be seen, changed and stored, and that nothing read.
     */
    @Test
    fun `the book it fetches is queued as a smart download`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-2"), previousPosition = 40, position = 60, duration = 100)

        assertEquals(listOf(TrafficCategory.SmartDownload), scheduler.categories)
    }

    /**
     * The trigger is the *crossing*, not the state of being past it.
     *
     * A listener two-thirds through who seeks back a chapter and forward again journals a dozen positions
     * that are all past halfway. Only the one that moved across it counts.
     */
    @Test
    fun `does not fetch again for a position that was already past halfway`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-2"), previousPosition = 60, position = 70, duration = 100)

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `does nothing for a book that is not in a series`() = runTest {
        val useCase = useCase(listOf(book("loner")), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("loner"), previousPosition = 40, position = 60, duration = 100)

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `does nothing for the last book in a series`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-3"), previousPosition = 40, position = 60, duration = 100)

        assertTrue(scheduler.enqueued.isEmpty())
    }

    /**
     * PRODUCT_SPEC LIB-003 — the classic audiobook-series bug.
     *
     * Sorted as text, `"10"` comes before `"2"`, and book 2's successor would be book 3 rather than book 10.
     * `SeriesSequence` is `Comparable` and knows better, and this is the test that proves the use case uses
     * it rather than the title.
     */
    @Test
    fun `orders the series numerically rather than alphabetically`() = runTest {
        val books = listOf(
            book("book-2", sequence = "2"),
            book("book-10", sequence = "10"),
            book("book-3", sequence = "3"),
        )
        val useCase = useCase(books, DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-3"), previousPosition = 40, position = 60, duration = 100)

        assertEquals(listOf(LibraryItemId("book-10")), scheduler.enqueued)
    }

    @Test
    fun `leaves the previous book alone unless asked to remove it`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-2"), previousPosition = 40, position = 60, duration = 100)

        assertTrue(files.removed.isEmpty())
    }

    /**
     * Decision 7's second half: the book **before** the one being listened to, never the current one.
     *
     * Halfway through book 2 the listener may well go back a chapter; book 1 is the one they have
     * demonstrably moved on from, and deleting book 2 would be the app removing what is playing.
     */
    @Test
    fun `removes the book before the current one when asked`() = runTest {
        val useCase = useCase(
            series(),
            DownloadHousekeeping(smartDownload = true, deletePreviousOnSmartDownload = true),
        )

        useCase(LibraryItemId("book-2"), previousPosition = 40, position = 60, duration = 100)

        assertEquals(listOf(LibraryItemId("book-1")), files.removed)
    }

    @Test
    fun `removes nothing when the current book is the first in its series`() = runTest {
        val useCase = useCase(
            series(),
            DownloadHousekeeping(smartDownload = true, deletePreviousOnSmartDownload = true),
        )

        useCase(LibraryItemId("book-1"), previousPosition = 40, position = 60, duration = 100)

        assertEquals(listOf(LibraryItemId("book-2")), scheduler.enqueued)
        assertTrue(files.removed.isEmpty())
    }

    /** A duration of zero is a book whose length is not known yet; there is no halfway mark to cross. */
    @Test
    fun `does nothing for a book of unknown length`() = runTest {
        val useCase = useCase(series(), DownloadHousekeeping(smartDownload = true))

        useCase(LibraryItemId("book-2"), previousPosition = 0, position = 60, duration = 0)

        assertTrue(scheduler.enqueued.isEmpty())
    }

    private fun useCase(books: List<Book>, housekeeping: DownloadHousekeeping): SmartDownloadUseCase {
        val profiles = FakeProfileRepository(profile().copy(canDownload = true))
        val library = FakeLibraryRepository(books)
        return SmartDownloadUseCase(
            profiles = profiles,
            library = library,
            settings = FakeSettingsRepository(housekeeping),
            downloadBook = DownloadBookUseCase(
                profiles = profiles,
                assets = FakeBookAssetSource(),
                downloads = FakeDownloadRepository(),
                scheduler = scheduler,
            ),
            files = files,
            logger = RecordingLogger(),
        )
    }

    private fun series() = listOf(
        book("book-1", sequence = "1"),
        book("book-2", sequence = "2"),
        book("book-3", sequence = "3"),
    )
}
