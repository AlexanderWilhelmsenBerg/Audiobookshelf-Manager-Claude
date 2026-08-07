package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.book
import org.junit.Test
import kotlin.test.assertEquals

/** PRODUCT_SPEC LIB-002 — the named shelves, as filters rather than as sorts. */
class BookFilterTest {

    private val unopened = book(id = "b1", title = "Unopened")
    private val started = book(id = "b2", title = "Started", playedAt = TEST_INSTANT)
    private val finished = book(id = "b3", title = "Finished", playedAt = TEST_INSTANT, isFinished = true)
    private val downloaded = book(id = "b4", title = "Downloaded")
        .copy(localAvailability = LocalAvailability.Complete)
    private val downloading = book(id = "b5", title = "Downloading")
        .copy(localAvailability = LocalAvailability.Partial)

    private val shelf = listOf(unopened, started, finished, downloaded, downloading)

    @Test
    fun `all keeps everything`() {
        assertEquals(shelf, filterBooks(shelf, BookFilter.All))
    }

    /**
     * Started and not finished — not "everything unfinished", which is the whole library.
     *
     * A finished book slipping in is the visible failure: this shelf is where a user looks to pick up
     * where they left off, and last month's finished book is not that.
     */
    @Test
    fun `continue listening is started and not finished`() {
        assertEquals(listOf("Started"), filterBooks(shelf, BookFilter.ContinueListening).map { it.title })
    }

    /** PRODUCT_SPEC DL-001 — a part-downloaded book is not playable offline, so it is not downloaded. */
    @Test
    fun `downloaded excludes a partial download`() {
        assertEquals(listOf("Downloaded"), filterBooks(shelf, BookFilter.Downloaded).map { it.title })
    }
}
