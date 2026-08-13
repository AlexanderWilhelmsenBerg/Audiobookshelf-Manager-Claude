package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * PRODUCT_SPEC PLAY-001 / PLAY-003, ADR-0016 — an open session as **one** Media3 item.
 *
 * Robolectric because `MediaItem` and `Bundle` are Android types; nothing here touches a player.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemsTest {

    /**
     * ADR-0016's whole point, as an assertion: a book is one item, not one per file.
     *
     * If this ever produced a playlist again, Media3 would go back to reporting the *file's* position and
     * duration to the notification and the lock screen, which is the defect the ADR was written for.
     */
    @Test
    fun `a book is a single item`() {
        val queue = MediaItems.queueFor(session())

        assertEquals(BOOK.value, queue.item.mediaId)
        assertEquals(BOOK, MediaItems.bookIdOf(queue.item))
    }

    /** The notification shows the book, not `02 - Tidewatch.mp3`. */
    @Test
    fun `the item carries the book's title and author`() {
        val item = MediaItems.queueFor(session()).item

        assertEquals("The Tidewatch Cycle", item.mediaMetadata.title?.toString())
        assertEquals("Marisol Holt", item.mediaMetadata.artist?.toString())
    }

    @Test
    fun `the cover url becomes the artwork uri`() {
        val item = MediaItems.queueFor(session()).item

        assertEquals(COVER, item.mediaMetadata.artworkUri?.toString())
    }

    @Test
    fun `a book with no cover has no artwork uri`() {
        val item = MediaItems.queueFor(session(coverUrl = null)).item

        assertNull(item.mediaMetadata.artworkUri)
    }

    /**
     * PRODUCT_SPEC PLAY-001 — playback starts where the server said to resume.
     *
     * No index and no per-file arithmetic any more: seven seconds into the book is seven seconds into the
     * player's timeline, because since ADR-0016 they are the same timeline.
     */
    @Test
    fun `the queue starts at the book position the server reported`() {
        val queue = MediaItems.queueFor(session(startAt = 7.seconds))

        assertEquals(7_000L, queue.startPositionMs)
    }

    @Test
    fun `a book with no stored position starts at the beginning`() {
        val queue = MediaItems.queueFor(session(startAt = Duration.ZERO))

        assertEquals(0L, queue.startPositionMs)
    }

    /** A negative stored position — reachable from a corrupted row — must not seek backwards. */
    @Test
    fun `a negative stored position starts at the beginning`() {
        val queue = MediaItems.queueFor(session(startAt = (-5).seconds))

        assertEquals(0L, queue.startPositionMs)
    }

    // --- The track list the item carries -----------------------------------------------------------

    /**
     * The facts [BookMediaSourceFactory] needs, in the order it plays them.
     *
     * They travel in the item rather than in a back channel because a controller that has never seen this
     * app's session types — Android Auto, a headset resume, Media3 restoring after process death — has
     * nothing but the item.
     */
    @Test
    fun `the item carries every playable track, in order`() {
        val tracks = MediaItems.tracksOf(MediaItems.queueFor(session()).item)

        assertEquals(2, tracks.size)
        assertEquals(listOf(6.seconds, 4.seconds), tracks.map { it.duration })
        assertEquals(listOf(url(1), url(2)), tracks.map { it.url })
        assertTrue(tracks.all { it.mimeType == "audio/mpeg" })
    }

    /**
     * PRODUCT_SPEC PLAY-003 — "excluded server tracks are not played".
     *
     * Dropped from the source rather than skipped at playback time: a source that does not contain the
     * file cannot play it by accident, from any control surface.
     */
    @Test
    fun `an excluded track is not in the item at all`() {
        val withExcluded = session(
            tracks = listOf(
                track(1, 0.seconds, 6.seconds),
                track(2, 6.seconds, 4.seconds, isExcluded = true),
            ),
        )

        val tracks = MediaItems.tracksOf(MediaItems.queueFor(withExcluded).item)

        assertEquals(1, tracks.size)
        assertEquals(url(1), tracks.single().url)
    }

    /** A server that sent no mime type leaves the slot empty rather than shifting the others along. */
    @Test
    fun `a track with no mime type keeps its place`() {
        val session = session(
            tracks = listOf(
                track(1, 0.seconds, 6.seconds, mimeType = null),
                track(2, 6.seconds, 4.seconds),
            ),
        )

        val tracks = MediaItems.tracksOf(MediaItems.queueFor(session).item)

        assertEquals(listOf(null, "audio/mpeg"), tracks.map { it.mimeType })
    }

    /** The book's duration, summed — used only before the player has prepared and can report its own. */
    @Test
    fun `the book duration is the sum of its tracks`() {
        assertEquals(10.seconds, MediaItems.bookDurationOf(MediaItems.queueFor(session()).item))
    }

    // --- Items that are not ours -------------------------------------------------------------------

    /**
     * A foreign item reads as no tracks rather than throwing.
     *
     * [BookMediaSourceFactory] runs on the player's thread, where an exception stops playback instead of
     * surfacing (product priority 1). Anything it cannot understand has to come back empty so it can fall
     * back to the plain factory.
     */
    @Test
    fun `an item from somewhere else has no tracks`() {
        assertEquals(emptyList(), MediaItems.tracksOf(MediaItem.Builder().setMediaId("elsewhere").build()))
        assertEquals(Duration.ZERO, MediaItems.bookDurationOf(MediaItem.Builder().setMediaId("elsewhere").build()))
    }

    /**
     * Truncated extras read as no tracks rather than indexing out of bounds.
     *
     * The three arrays cross a binder and are parallel by convention only. Validating them against each
     * other is cheaper than the crash that a mismatch would otherwise cause inside the factory.
     */
    @Test
    fun `extras whose arrays disagree have no tracks`() {
        val mismatched = Bundle().apply {
            putStringArray(MediaItems.KEY_TRACK_URLS, arrayOf(url(1), url(2)))
            putLongArray(MediaItems.KEY_TRACK_DURATIONS_MS, longArrayOf(6_000))
            putStringArray(MediaItems.KEY_TRACK_MIME_TYPES, arrayOf("audio/mpeg", "audio/mpeg"))
        }
        val item = MediaItem.Builder()
            .setMediaId(BOOK.value)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(mismatched).build())
            .build()

        assertEquals(emptyList(), MediaItems.tracksOf(item))
    }

    @Test
    fun `a book with no playable tracks has no tracks`() {
        val queue = MediaItems.queueFor(session(tracks = emptyList()))

        assertEquals(emptyList(), MediaItems.tracksOf(queue.item))
    }

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        val SERVER = ServerId("server-1")
        const val COVER = "https://books.example/api/items/tidewatch/cover"

        fun url(index: Int) = "https://books.example/api/items/tidewatch/file/$index"

        fun track(
            index: Int,
            startOffset: Duration,
            duration: Duration,
            isExcluded: Boolean = false,
            mimeType: String? = "audio/mpeg",
        ) = PlayableTrack(
            index = index,
            url = url(index),
            startOffset = startOffset,
            duration = duration,
            mimeType = mimeType,
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
