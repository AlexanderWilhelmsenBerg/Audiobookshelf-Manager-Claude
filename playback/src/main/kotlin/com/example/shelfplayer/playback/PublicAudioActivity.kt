package com.example.shelfplayer.playback

import android.media.AudioManager

/** Anonymous public attributes only; no UID/package, tags, session IDs, or inference of focus ownership. */
internal object PublicAudioActivity {
    fun read(manager: AudioManager?): String {
        val configs = runCatching { manager?.activePlaybackConfigurations }.getOrNull()
            ?: return "activityStatus=unavailable visiblePlayers=unknown usageContent=unavailable"
        val attributes = runCatching {
            configs.take(MAX_PLAYERS).map { "${it.audioAttributes.usage}/${it.audioAttributes.contentType}" }
                .groupingBy { it }.eachCount().toSortedMap().entries.joinToString("+") { "${it.key}:${it.value}" }
                .ifEmpty { "none" }
        }.getOrDefault("unavailable")
        return "activityStatus=observed visiblePlayers=${configs.size} " +
            "usageContent=$attributes activityTruncated=${configs.size > MAX_PLAYERS}"
    }

    private const val MAX_PLAYERS = 32
}
