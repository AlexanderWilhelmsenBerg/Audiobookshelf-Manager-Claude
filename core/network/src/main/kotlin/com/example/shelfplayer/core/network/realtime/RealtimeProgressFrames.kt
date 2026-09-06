package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.network.api.AuthMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Current Audiobookshelf `user_item_progress_updated` payload.
 *
 * The server's `PlaybackSessionManager` emits an envelope containing its media-progress id, the playback
 * session id, a human-readable device description and `data = MediaProgress.getOldMediaProgress()`.
 * BookWave needs only the progress row and session identity. `deviceDescription` is deliberately ignored:
 * it can contain a user's device name and is unnecessary for conflict resolution or resume freshness.
 */
internal object RealtimeProgressFrames {
    fun parse(json: Json, body: JsonObject): RealtimeEvent.ProgressChanged? {
        val progressBody = body[DATA] as? JsonObject ?: return null
        val progress = AuthMapper.toProgress(json, progressBody) ?: return null
        val sessionId = (body[SESSION_ID] as? JsonPrimitive)
            ?.content
            ?.takeIf(String::isNotBlank)
        return RealtimeEvent.ProgressChanged(progress = progress, sessionId = sessionId)
    }

    private const val DATA = "data"
    private const val SESSION_ID = "sessionId"
}
