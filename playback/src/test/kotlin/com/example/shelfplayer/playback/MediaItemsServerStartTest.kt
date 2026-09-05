package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** SYNC-002 / R-61 — the resume baseline must use the same coordinate space as the Media3 queue. */
class MediaItemsServerStartTest {

    @Test
    fun `a book timeline can retain the server start as freshness evidence`() {
        val session = session(
            startAt = 9.hours + 25.minutes,
            tracks = listOf(
                track(index = 0, duration = 5.hours),
                track(index = 1, duration = 5.hours),
            ),
            duration = 10.hours,
        )

        assertEquals(9.hours + 25.minutes, MediaItems.serverStartPositionFor(session))
    }

    @Test
    fun `a single-file fallback cannot retain a book-global server start`() {
        val session = session(
            startAt = 9.hours + 25.minutes,
            // More than one unknown duration cannot be recovered from the total, so the queue falls back to
            // one file. A file-relative player position must not become evidence about a book-relative server
            // position.
            tracks = listOf(
                track(index = 0, duration = Duration.ZERO),
                track(index = 1, duration = Duration.ZERO),
            ),
            duration = 10.hours,
        )

        assertNull(MediaItems.serverStartPositionFor(session))
        assertEquals(0L, MediaItems.queueFor(session).startPositionMs)
    }

    private fun session(
        startAt: Duration,
        tracks: List<PlayableTrack>,
        duration: Duration,
    ) = PlaybackSession(
        id = "session-restored",
        profileId = ProfileId("profile-a"),
        bookId = BOOK,
        title = "Restored book",
        author = null,
        coverUrl = null,
        startAt = startAt,
        duration = duration,
        tracks = tracks,
        chapters = emptyList(),
    )

    private fun track(index: Int, duration: Duration) = PlayableTrack(
        index = index,
        url = "https://books.example/file/$index",
        startOffset = Duration.ZERO,
        duration = duration,
        mimeType = "audio/mpeg",
        isExcluded = false,
    )

    private companion object {
        val BOOK = LibraryItemId("restored-book")
    }
}
