package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.network.api.MediaProgressDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Maps Audiobookshelf's current `user_item_progress_updated` payload without inventing a second wire shape.
 *
 * Current server source emits an envelope containing `id`, `sessionId`, `deviceDescription` and `data`,
 * where `data` is `MediaProgress.getOldMediaProgress()`. Absorb consumes the same event by extracting that
 * nested `data` object. BookWave keeps `sessionId` for later resume coordination but deliberately drops the
 * device description before the event reaches the domain layer.
 */
internal object ProgressFrames {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: JsonObject): RealtimeEvent.ProgressChanged? {
        val data = body["data"] as? JsonObject ?: return null
        val dto = runCatching { json.decodeFromJsonElement<MediaProgressDto>(data) }.getOrNull() ?: return null
        val bookId = dto.libraryItemId?.takeIf(String::isNotBlank) ?: return null
        val sessionId = body["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

        return RealtimeEvent.ProgressChanged(
            progress = AccountProgress(
                bookId = LibraryItemId(bookId),
                position = dto.currentTime.seconds,
                duration = dto.duration.seconds,
                isFinished = dto.isFinished,
                updatedAt = Instant.ofEpochMilli(dto.lastUpdate ?: 0L),
            ),
            sessionId = sessionId,
        )
    }
}
