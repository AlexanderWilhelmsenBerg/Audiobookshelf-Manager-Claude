package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.PlaybackEvent
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #139 — one-shot cause handoff for the next transport stop recorded by [PlaybackService].
 *
 * The service remains the only owner of Play/Pause history. A sleep timer marks its cause immediately
 * before asking Media3 to pause; the resulting `onPlayWhenReadyChanged(false)` consumes that mark and
 * records `SleepTimerExpired` instead of an additional generic `Pause`. A subsequent Play clears any
 * unconsumed mark so a no-op/aborted timer pause can never leak into a later user pause.
 */
@Singleton
class PlaybackStopHistoryCause @Inject constructor() {
    private val pending = AtomicReference<PlaybackEvent?>(null)

    fun markSleepTimerExpiry() {
        pending.set(PlaybackEvent.SleepTimerExpired)
    }

    fun eventFor(playWhenReady: Boolean): PlaybackEvent {
        if (playWhenReady) {
            pending.set(null)
            return PlaybackEvent.Play
        }
        return pending.getAndSet(null) ?: PlaybackEvent.Pause
    }
}
