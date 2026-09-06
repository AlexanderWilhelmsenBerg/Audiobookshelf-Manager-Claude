package com.example.shelfplayer.core.network.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #90 — the current Audiobookshelf playback-progress socket envelope.
 *
 * The shape is source-derived from `PlaybackSessionManager.syncSession` / `syncLocalSession`: the wrapper
 * carries `sessionId` and `deviceDescription`, while `data` is `MediaProgress.getOldMediaProgress()`.
 * `deviceDescription` is intentionally absent from BookWave's model.
 */
class RealtimeProgressFramesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `current progress envelope keeps session identity and maps old media progress units`() {
        val body = json.parseToJsonElement(
            """
            {
              "id":"progress-id",
              "sessionId":"session-id",
              "deviceDescription":"Browser on another device",
              "data":{
                "id":"progress-id",
                "userId":"user-id",
                "libraryItemId":"book-id",
                "mediaItemId":"book-media-id",
                "mediaItemType":"book",
                "duration":98316.504,
                "progress":0.1426,
                "currentTime":14022.513,
                "isFinished":false,
                "lastUpdate":1788643210123,
                "startedAt":1788643000000,
                "finishedAt":null
              }
            }
            """.trimIndent(),
        ).jsonObject

        val event = requireNotNull(RealtimeProgressFrames.parse(json, body))

        assertEquals("session-id", event.sessionId)
        assertEquals("book-id", event.progress.bookId.value)
        assertEquals(14_022_513L, event.progress.position.inWholeMilliseconds)
        assertEquals(98_316_504L, event.progress.duration.inWholeMilliseconds)
        assertEquals(false, event.progress.isFinished)
        assertEquals(Instant.ofEpochMilli(1_788_643_210_123L), event.progress.updatedAt)
    }

    @Test
    fun `blank session id stays optional but valid progress is retained`() {
        val body = json.parseToJsonElement(
            """
            {
              "sessionId":"",
              "data":{
                "libraryItemId":"book-id",
                "currentTime":42.5,
                "duration":100.0,
                "lastUpdate":10
              }
            }
            """.trimIndent(),
        ).jsonObject

        val event = requireNotNull(RealtimeProgressFrames.parse(json, body))

        assertNull(event.sessionId)
        assertEquals(42_500L, event.progress.position.inWholeMilliseconds)
    }

    @Test
    fun `missing data or book identity is inert`() {
        val noData = json.parseToJsonElement("""{"sessionId":"session-id"}""").jsonObject
        val noBook = json.parseToJsonElement(
            """{"sessionId":"session-id","data":{"currentTime":42.5,"duration":100.0,"lastUpdate":10}}""",
        ).jsonObject

        assertNull(RealtimeProgressFrames.parse(json, noData))
        assertNull(RealtimeProgressFrames.parse(json, noBook))
    }
}
