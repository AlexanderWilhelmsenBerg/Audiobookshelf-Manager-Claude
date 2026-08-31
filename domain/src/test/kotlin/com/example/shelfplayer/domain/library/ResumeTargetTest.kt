package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.domain.book
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class ResumeTargetTest {

    @Test
    fun `newer server activity selects the server book`() {
        val localAt = Instant.parse("2026-08-30T12:00:00Z")
        val serverAt = Instant.parse("2026-08-30T13:00:00Z")
        val books = listOf(
            book("local", playedAt = localAt),
            book("remote"),
        )

        val chosen = reconciledResumeTarget(books, session("remote", serverAt, 7.minutes))

        assertEquals("remote", chosen?.book?.id?.value)
        assertEquals(7.minutes, chosen?.position)
    }

    @Test
    fun `newer local progress wins over stale server activity`() {
        val localAt = Instant.parse("2026-08-30T14:00:00Z")
        val serverAt = Instant.parse("2026-08-30T13:00:00Z")
        val books = listOf(
            book("local", playedAt = localAt),
            book("remote"),
        )

        val chosen = reconciledResumeTarget(books, session("remote", serverAt, 7.minutes))

        assertEquals("local", chosen?.book?.id?.value)
    }

    @Test
    fun `finished server book cannot replace local resumable book`() {
        val localAt = Instant.parse("2026-08-30T12:00:00Z")
        val serverAt = Instant.parse("2026-08-30T13:00:00Z")
        val books = listOf(
            book("local", playedAt = localAt),
            book("remote", playedAt = serverAt, isFinished = true),
        )

        val chosen = reconciledResumeTarget(books, session("remote", serverAt, 7.minutes))

        assertEquals("local", chosen?.book?.id?.value)
    }

    @Test
    fun `zero-listening server session is ignored`() {
        val localAt = Instant.parse("2026-08-30T12:00:00Z")
        val books = listOf(book("local", playedAt = localAt), book("remote"))
        val session = session("remote", Instant.parse("2026-08-30T13:00:00Z"), 7.minutes)
            .copy(listened = kotlin.time.Duration.ZERO)

        assertEquals("local", reconciledResumeTarget(books, session)?.book?.id?.value)
    }

    private fun session(bookId: String, updatedAt: Instant, reachedAt: kotlin.time.Duration) = ListeningSession(
        id = "session-$bookId",
        bookId = LibraryItemId(bookId),
        deviceId = "other-device",
        deviceName = null,
        clientName = null,
        listened = 10.minutes,
        startedFrom = 5.minutes,
        reachedAt = reachedAt,
        startedAt = updatedAt.minusSeconds(600),
        updatedAt = updatedAt,
    )
}
