package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.playback.PlaybackUiState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GarminSnapshotProjector @Inject constructor(private val clock: AppClock) {
    fun project(
        playback: PlaybackUiState,
        profile: Profile,
        updatedAtEpochMs: Long = clock.now().toEpochMilli(),
    ): GarminPlaybackSnapshot? {
        val bookId = playback.bookId?.value?.takeIf { it.isNotBlank() } ?: return null
        val owned = playback.ownerProfileId == profile.id && updatedAtEpochMs > 0L
        if (!owned || bookId.length > MAX_ID_LENGTH || profile.id.value.length > MAX_ID_LENGTH) return null
        val title = playback.title.trim().takeIf { it.isNotEmpty() }?.take(MAX_TITLE_LENGTH) ?: return null

        val duration = playback.duration.inWholeMilliseconds.takeIf { it > 0L }
        val position = playback.position.inWholeMilliseconds
            .coerceAtLeast(0L)
            .let { value -> duration?.let { value.coerceAtMost(it) } ?: value }

        return GarminPlaybackSnapshot(
            protocolVersion = GarminBridgeConfig.PROTOCOL_MAJOR,
            profileId = profile.id.value,
            bookId = bookId,
            title = title,
            author = playback.author?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_AUTHOR_LENGTH),
            chapterTitle = playback.currentChapter?.title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CHAPTER_LENGTH),
            positionMs = position,
            durationMs = duration,
            updatedAt = updatedAtEpochMs,
            playing = playback.isPlaying,
            source = "PHONE",
            speed = playback.speed.value,
        )
    }

    private companion object {
        const val MAX_ID_LENGTH = 96
        const val MAX_TITLE_LENGTH = 160
        const val MAX_AUTHOR_LENGTH = 120
        const val MAX_CHAPTER_LENGTH = 160
    }
}
