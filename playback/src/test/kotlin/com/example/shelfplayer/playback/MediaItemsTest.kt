package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- Which items a session callback may hand straight to the player ----------------------------

    /**
     * The regression test for the defect that stopped book switching working at all.
     *
     * A `MediaController` — which is how the app itself talks to its own session — routes `setMediaItem`
     * through `MediaSession.Callback`, the same callback a car goes through. Wave 5 answered that callback
     * by resolving browse ids only, so the app's own item was dropped, the player received an empty list,
     * and the book already playing carried on. If this assertion ever goes false again, pressing a second
     * book does nothing.
     */
    @Test
    fun `the app's own item is ready to play as it stands`() {
        assertTrue(MediaItems.isReadyToPlay(MediaItems.queueFor(session()).item))
    }

    /**
     * The same item after a round trip that kept the metadata and lost the URI.
     *
     * Media3 has two bundlings for a `MediaItem` and only one of them carries `localConfiguration`. The
     * track list is the app's own answer, and it is the one [BookMediaSourceFactory] actually reads, so a
     * book stays playable either way rather than depending on which bundling something chose.
     */
    @Test
    fun `an item that kept its tracks but lost its uri is still ready to play`() {
        val withoutUri = MediaItems.queueFor(session()).item.buildUpon().setUri(null as android.net.Uri?).build()

        assertNull(withoutUri.localConfiguration)
        assertTrue(MediaItems.isReadyToPlay(withoutUri))
    }

    /**
     * A browse row is not ready: it is an id and a title, and the URLs behind it need a session opening.
     *
     * Passing one to the player would produce an error a driver cannot act on, which is why the callback
     * resolves these rather than forwarding them.
     */
    @Test
    fun `a browse row is not ready to play`() {
        val browsed = MediaItem.Builder()
            .setMediaId("book/${BOOK.value}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("The Tidewatch Cycle").build())
            .build()

        assertFalse(MediaItems.isReadyToPlay(browsed))
    }

    /** Anything with a URI is somebody else's business and is forwarded, which is Media3's own default. */
    @Test
    fun `an item from somewhere else with a uri is ready to play`() {
        val foreign = MediaItem.fromUri("https://elsewhere.example/clip.mp3")

        assertTrue(MediaItems.isReadyToPlay(foreign))
    }

    // ------------------------------------------------ PRODUCT_SPEC 6.5, whose book this is

    /**
     * The item names the account that opened it, and names it for as long as it stays loaded.
     *
     * That is what makes a journal tick, a headset pause and a car's transport control able to write to the
     * right row without asking who is signed in — an answer that is wrong for the moment either side of a
     * profile switch, which is exactly when a switch is happening.
     */
    @Test
    fun `an item carries the profile whose session built it`() {
        val item = MediaItems.queueFor(session()).item

        assertEquals(OWNER, MediaItems.ownerOf(item))
    }

    /**
     * An item this app did not build has no owner, and that is a `null` rather than a guess.
     *
     * The writers fall back to the active profile for one, which is what every write did before the owner
     * existed. Inventing an owner here would turn a missing fact into a confident wrong one.
     */
    @Test
    fun `an item from somewhere else has no owner`() {
        val foreign = MediaItem.Builder().setMediaId(BOOK.value).build()

        assertNull(MediaItems.ownerOf(foreign))
    }

    private companion object {
        val OWNER = ProfileId("prf_ada")
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
            profileId = OWNER,
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
