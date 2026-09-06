package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.LibraryItemId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract coverage for Audiobookshelf's current `user_item_progress_updated` payload.
 *
 * The envelope shape is source-derived from Audiobookshelf 2.36.x's PlaybackSessionManager and matches
 * Absorb's production listener: the media progress is nested under `data`, while `sessionId` identifies the
 * session that caused it. Device descriptions are intentionally ignored by BookWave.
 */
class ProgressFramesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `current Audiobookshelf item progress event maps seconds and millis correctly`() {
        val body = json.parseToJsonElement(
            """
            {
              "id": "progress-id",
              "sessionId": "session-id",
              "deviceDescription": "Browser on somebody's laptop",
              "data": {
                "libraryItemId": "book-id",
                "mediaItemId": "media-id",
                "mediaItemType": "book",
                "duration": 7200.25,
                "currentTime": 42.5,
                "isFinished": false,
                "lastUpdate": 1788640000123
              }
            }
            """.trimIndent(),
        ).jsonObject

        val event = requireNotNull(ProgressFrames.parse(body))

        assertEquals(LibraryItemId("book-id"), event.progress.bookId)
        assertEquals(42_500L, event.progress.position.inWholeMilliseconds)
        assertEquals(7_200_250L, event.progress.duration.inWholeMilliseconds)
        assertEquals(false, event.progress.isFinished)
        assertEquals(Instant.ofEpochMilli(1_788_640_000_123), event.progress.updatedAt)
        assertEquals("session-id", event.sessionId)
    }

    @Test
    fun `an event without nested progress data is ignored`() {
        val body = json.parseToJsonElement("""{"sessionId":"session-id"}""").jsonObject

        assertNull(ProgressFrames.parse(body))
    }

    @Test
    fun `a progress object that names no library item is ignored`() {
        val body = json.parseToJsonElement(
            """{"data":{"currentTime":42.5,"duration":100.0,"lastUpdate":1788640000123}}""",
        ).jsonObject

        assertNull(ProgressFrames.parse(body))
    }
}
