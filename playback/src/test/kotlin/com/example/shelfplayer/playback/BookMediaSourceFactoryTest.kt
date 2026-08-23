package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003, ADR-0016 — a book becomes one timeline window, or degrades honestly.
 *
 * Robolectric because `MediaItem` and the ExoPlayer source classes are Android types. Nothing here
 * prepares a source, so the data source factory is never called — which is why it can throw.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class BookMediaSourceFactoryTest {

    private val logger = RecordingLogger()
    private val factory = BookMediaSourceFactory(
        dataSourceFactory = DataSource.Factory { error("no source is opened in this test") },
        logger = logger,
    )

    /**
     * The point of the whole change: several files, one window.
     *
     * `ConcatenatingMediaSource2` is what makes the player report book-global positions to every
     * controller. Asserting on the type rather than on a position is deliberate — a position needs a
     * prepared player, and the type is the thing that decides what the position will mean.
     */
    @Test
    fun `a book with known track durations becomes one concatenated source`() {
        val source = factory.createMediaSource(MediaItems.queueFor(session()).item)

        assertIs<ConcatenatingMediaSource2>(source)
        assertEquals(BOOK.value, source.mediaItem.mediaId)
    }

    /**
     * The unit, read back off the timeline the source actually builds.
     *
     * The first version of this factory passed **microseconds** to a builder that takes milliseconds and
     * converts with `Util.msToUs` itself. Every book came out a thousand times longer than it is: a device
     * run showed a 34-hour book as 527 hours in the notification — the product of the 1000× error and a
     * 32-bit truncation somewhere in the system UI — and the bar and clocks were meaningless.
     *
     * Asserting on the built timeline rather than on the argument is the point: it is the number the
     * player, the notification and the lock screen will all read.
     */
    @Test
    fun `the window is as long as the book, in milliseconds`() {
        val source = factory.createMediaSource(MediaItems.queueFor(session()).item)

        val window = (source as ConcatenatingMediaSource2).initialTimeline!!.getWindow(0, Timeline.Window())
        assertEquals(10_000L, window.durationMs)
    }

    @Test
    fun `building one window is logged with the track count and total duration`() {
        factory.createMediaSource(MediaItems.queueFor(session()).item)

        val event = logger.events.single()
        assertEquals(LogLevel.Info, event.level)
        assertTrue(event.fields.any { it.key == "tracks" })
        assertTrue(event.fields.any { it.key == "duration" })
    }

    /**
     * The fallback branch, and why it is not dead code.
     *
     * `ConcatenatingMediaSource2` needs every duration up front — that is what lets it present one window
     * without having prepared anything — so a track reporting zero cannot be built this way. Falling back
     * plays the book's first file only, which is a real degradation and the honest one: product priority 1
     * puts "do not interrupt playback" above being internally consistent.
     *
     * Whether Audiobookshelf can even report a zero duration is unverified — no capture has produced one
     * (PRODUCT_SPEC 22.5) — so this is written defensively rather than to a known case.
     */
    @Test
    fun `a book with a zero-duration track falls back to the plain factory`() {
        val ragged = session(
            tracks = listOf(
                track(1, 0.seconds, 6.seconds),
                track(2, 6.seconds, Duration.ZERO),
            ),
        )

        val source = factory.createMediaSource(MediaItems.queueFor(ragged).item)

        assertFalse(source is ConcatenatingMediaSource2)
    }

    /** And it says so, because a book playing only its first file is worth seeing in a support report. */
    @Test
    fun `the fallback is warned about, with a count and no urls`() {
        val ragged = session(tracks = listOf(track(1, 0.seconds, Duration.ZERO)))

        factory.createMediaSource(MediaItems.queueFor(ragged).item)

        val event = logger.events.single()
        assertEquals(LogLevel.Warn, event.level)
        // PRODUCT_SPEC 14.5 — a count is safe to log where a track URL, a path on someone's private
        // server, is not.
        assertEquals(listOf("tracks"), event.fields.map { it.key })
    }

    /**
     * An item this app did not build still has to play something.
     *
     * Media3 can hand the session an item from anywhere — a restored session after process death, a
     * controller that built its own. With no track list there is nothing to concatenate, so the plain
     * factory gets it and plays the item's own URI.
     */
    @Test
    fun `an item with no track list goes to the plain factory`() {
        val foreign = MediaItem.Builder()
            .setMediaId("elsewhere")
            .setUri("https://books.example/api/items/elsewhere/file/1")
            .build()

        val source = factory.createMediaSource(foreign)

        assertFalse(source is ConcatenatingMediaSource2)
        // Silent: an item that is not ours is not a defect, and warning on every one would bury the
        // fallback that *is* worth seeing.
        assertTrue(logger.events.isEmpty())
    }

    private class RecordingLogger : Logger {
        val events = mutableListOf<LogEvent>()

        override fun log(event: LogEvent) {
            events += event
        }
    }

    private companion object {
        val OWNER = ProfileId("prf_ada")
        val BOOK = LibraryItemId("tidewatch")
        val SERVER = ServerId("server-1")

        fun track(index: Int, startOffset: Duration, duration: Duration) = PlayableTrack(
            index = index,
            url = "https://books.example/api/items/tidewatch/file/$index",
            startOffset = startOffset,
            duration = duration,
            mimeType = "audio/mpeg",
            isExcluded = false,
        )

        fun session(
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
            coverUrl = null,
            startAt = Duration.ZERO,
            duration = 10.seconds,
            tracks = tracks,
            chapters = listOf(Chapter(SERVER, BOOK, 0, "The Ebb", 0.seconds, 10.seconds)),
        )
    }
}
