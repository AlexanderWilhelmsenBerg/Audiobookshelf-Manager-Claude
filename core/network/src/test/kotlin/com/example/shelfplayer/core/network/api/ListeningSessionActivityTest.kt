package com.example.shelfplayer.core.network.api

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/** The resume chooser needs the server's last activity, not merely when a session began. */
class ListeningSessionActivityTest {

    @Test
    fun `mapper keeps session updatedAt separately from startedAt`() {
        val startedAt = 1_700_000_000_000L
        val updatedAt = startedAt + 90_000L
        val dto = ListeningSessionDto(
            id = "session-1",
            libraryItemId = "book-1",
            startedAt = startedAt,
            updatedAt = updatedAt,
        )

        val mapped = ListeningSessionMapper.toSessions(
            ListeningSessionsResponseDto(sessions = listOf(dto)),
        ).single()

        assertEquals(Instant.ofEpochMilli(startedAt), mapped.startedAt)
        assertEquals(Instant.ofEpochMilli(updatedAt), mapped.updatedAt)
    }

    @Test
    fun `missing updatedAt falls back to startedAt`() {
        val startedAt = 1_700_000_000_000L
        val dto = ListeningSessionDto(
            id = "session-1",
            libraryItemId = "book-1",
            startedAt = startedAt,
        )

        val mapped = ListeningSessionMapper.toSessions(
            ListeningSessionsResponseDto(sessions = listOf(dto)),
        ).single()

        assertEquals(mapped.startedAt, mapped.updatedAt)
    }
}
