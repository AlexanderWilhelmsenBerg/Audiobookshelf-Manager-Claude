package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlayableTrack
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.playback.ResumeBaseline
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Issue #91 — only a real server `/play` response may manufacture an acknowledged start baseline. */
class BookChangesServerEvidenceTest {

    @Test
    fun `blank id offline session cannot become a server acknowledged baseline`() {
        val baseline = ResumeBaseline()
        val session = session(id = "", startAtMinutes = 60)

        baseline.stageServerPosition(session.bookId, session.serverAcknowledgedStartPosition())
        baseline.onBookClosed()

        assertNull(baseline.acknowledged(BOOK))
    }

    @Test
    fun `server session can stage its authoritative start as a baseline`() {
        val baseline = ResumeBaseline()
        val session = session(id = "server-session", startAtMinutes = 60)

        baseline.stageServerPosition(session.bookId, session.serverAcknowledgedStartPosition())
        baseline.onBookClosed()

        assertEquals(60.minutes, baseline.acknowledged(BOOK)?.position)
    }

    private fun session(id: String, startAtMinutes: Int) = PlaybackSession(
        id = id,
        profileId = PROFILE,
        bookId = BOOK,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = startAtMinutes.minutes,
        duration = 2.hours,
        tracks = listOf(
            PlayableTrack(
                index = 0,
                url = "file:///book.m4b",
                startOffset = 0.minutes,
                duration = 2.hours,
                mimeType = "audio/mp4",
                isExcluded = false,
            ),
        ),
        chapters = emptyList(),
    )

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val BOOK = LibraryItemId("book-a")
    }
}
