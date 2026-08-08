package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003 — an open session as a Media3 playlist.
 *
 * Robolectric because `MediaItem` and `Bundle` are Android types; nothing here touches a player.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemsTest {

    /**
     * The property the progress journal depends on: whichever track is playing, the item says which
     * *book* it is.
     *
     * If the ids were made unique per track — the obvious-looking thing to do — the service would have
     * to parse a book id back out of a composite string, and would be writing progress against whatever
     * that parse produced.
     */
    @Test
    fun `every track carries the book's id, not the file's`() {
        val queue = MediaItems.queueFor(session())

        assertEquals(listOf(BOOK.value, BOOK.value), queue.items.map { it.mediaId })
        assertTrue(queue.items.all { MediaItems.bookIdOf(it) == BOOK })
    }

    /** The notification shows the book, not `02 - Tidewatch.mp3`. */
    @Test
    fun `every track carries the book's title and author`() {
        val queue = MediaItems.queueFor(session())

        queue.items.forEach { item ->
            assertEquals("The Tidewatch Cycle", item.mediaMetadata.title?.toString())
            assertEquals("Marisol Holt", item.mediaMetadata.artist?.toString())
        }
    }

    @Test
    fun `the cover url becomes the artwork uri`() {
        val queue = MediaItems.queueFor(session())

        assertEquals(COVER, queue.items.first().mediaMetadata.artworkUri?.toString())
    }

    @Test
    fun `a book with no cover has no artwork uri`() {
        val queue = MediaItems.queueFor(session(coverUrl = null))

        assertNull(queue.items.first().mediaMetadata.artworkUri)
    }

    /**
     * PRODUCT_SPEC PLAY-001 — the playlist starts where the server said to resume.
     *
     * Seven seconds into a six-then-four-second book is one second into the second file. A player
     * handed `(0, 7000)` would seek past the end of the first track and land nowhere.
     */
    @Test
    fun `the queue starts at the track and offset the resume position falls in`() {
        val queue = MediaItems.queueFor(session(startAt = 7.seconds))

        assertEquals(1, queue.startIndex)
        assertEquals(1_000L, queue.startPositionMs)
    }

    @Test
    fun `a book with no stored position starts at the beginning`() {
        val queue = MediaItems.queueFor(session(startAt = Duration.ZERO))

        assertEquals(0, queue.startIndex)
        assertEquals(0L, queue.startPositionMs)
    }

    /**
     * PRODUCT_SPEC PLAY-003 — "excluded server tracks are not played".
     *
     * Dropped from the playlist rather than skipped at playback time: a playlist that does not contain
     * the file cannot play it by accident, from any control surface.
     */
    @Test
    fun `an excluded track is not in the playlist at all`() {
        val withExcluded = session(
            tracks = listOf(
                track(1, 0.seconds, 6.seconds),
                track(2, 6.seconds, 4.seconds, isExcluded = true),
            ),
        )

        val queue = MediaItems.queueFor(withExcluded)

        assertEquals(1, queue.items.size)
    }

    // --- The facts that travel with the item -------------------------------------------------------

    /**
     * The conversion the service performs after the app process is gone.
     *
     * One second into the second track is seven seconds into the book, and the only place that fact
     * exists at that point is the item's own metadata.
     */
    @Test
    fun `a player position converts to a global book position`() {
        val queue = MediaItems.queueFor(session())

        assertEquals(7.seconds, MediaItems.globalPositionOf(queue.items[1], playerPositionMs = 1_000))
        assertEquals(2.seconds, MediaItems.globalPositionOf(queue.items[0], playerPositionMs = 2_000))
    }

    /** The book's duration, which no single track's duration gives. */
    @Test
    fun `the book duration travels with every item`() {
        val queue = MediaItems.queueFor(session())

        assertTrue(queue.items.all { MediaItems.bookDurationOf(it) == 10.seconds })
    }

    /** A negative player position — Media3 reports `-1` for "unset" — must not read as a rewind. */
    @Test
    fun `an unset player position reads as the start of the track`() {
        val queue = MediaItems.queueFor(session())

        assertEquals(6.seconds, MediaItems.globalPositionOf(queue.items[1], playerPositionMs = -1))
    }

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        val SERVER = ServerId("server-1")
        const val COVER = "https://books.example/api/items/tidewatch/cover"

        fun track(index: Int, startOffset: Duration, duration: Duration, isExcluded: Boolean = false) = PlayableTrack(
            index = index,
            url = "https://books.example/api/items/tidewatch/file/$index",
            startOffset = startOffset,
            duration = duration,
            mimeType = "audio/mpeg",
            isExcluded = isExcluded,
        )

        /** `multi-item-play.json`, as a model. */
        fun session(
            startAt: Duration = Duration.ZERO,
            coverUrl: String? = COVER,
            tracks: List<PlayableTrack> = listOf(
                track(1, 0.seconds, 6.seconds),
                track(2, 6.seconds, 4.seconds),
            ),
        ) = PlaybackSession(
            id = "session-1",
            bookId = BOOK,
            title = "The Tidewatch Cycle",
            author = "Marisol Holt",
            coverUrl = coverUrl,
            startAt = startAt,
            duration = 10.seconds,
            tracks = tracks,
            chapters = listOf(
                Chapter(SERVER, BOOK, 0, "The Ebb", 0.seconds, 6.seconds),
                Chapter(SERVER, BOOK, 1, "The Flood", 6.seconds, 10.seconds),
            ),
        )
    }
}
