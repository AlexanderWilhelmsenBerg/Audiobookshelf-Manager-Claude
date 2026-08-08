package com.example.shelfplayer.domain.playback

import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-004 — how much audio was actually played, as opposed to how far the position moved.
 *
 * ### Why this cannot be a position delta
 *
 * A seek moves the position without anybody having listened to the difference. A server that derived
 * listening time from position deltas would credit a listener who dragged the bar across a ten-hour book
 * with ten hours of reading, and would credit a re-listen of the same chapter with nothing. So the app
 * measures elapsed time while the player was *playing*, which is the only definition that matches what the
 * word means.
 *
 * ### Monotonic, not wall clock
 *
 * Every reading is a monotonic one (`AppClock.elapsed`). A network time correction mid-chapter would
 * otherwise add or remove hours of "listening" from a session that ran for twenty minutes.
 *
 * ### Two answers, deliberately
 *
 * [total] is everything since the session opened. [drain] is what has accumulated since the last time it
 * was asked, and returns it *and* forgets it. Both exist because the two routes out of the outbox want
 * different things — see `AbsPlaybackApi` — and because whether the live sync route accumulates its
 * `timeListened` or replaces it **is not settled by any capture we hold**. Keeping the two apart means the
 * question can be answered by observation later without changing what either route sends today
 * (PRODUCT_SPEC 22.5).
 *
 * Not thread-safe, and not meant to be: it is driven from the player's own thread, which is where every
 * `isPlaying` transition is observed.
 */
class ListenedTime {

    private var accumulated: Duration = Duration.ZERO
    private var drained: Duration = Duration.ZERO
    private var playingSince: Duration? = null

    /** Everything played since this session opened. */
    val total: Duration get() = accumulated

    /**
     * Records that playback started or stopped at monotonic reading [at].
     *
     * Idempotent in both directions. Media3 reports `isPlaying` on more events than transitions — a
     * repeated `true` while already playing must not restart the interval and lose the time before it, and a
     * repeated `false` must not double-count the interval that already closed.
     */
    fun onPlayingChanged(isPlaying: Boolean, at: Duration) {
        val since = playingSince
        when {
            isPlaying && since == null -> playingSince = at
            !isPlaying && since != null -> {
                accumulated += (at - since).coerceAtLeast(Duration.ZERO)
                playingSince = null
            }
        }
    }

    /**
     * The total as of [at], including the interval currently in progress.
     *
     * Reading it does not close that interval: a sync in the middle of a chapter must not make the player
     * look paused to the next reading.
     */
    fun totalAt(at: Duration): Duration {
        val since = playingSince ?: return accumulated
        return accumulated + (at - since).coerceAtLeast(Duration.ZERO)
    }

    /** What has been played since the previous [drain], as of [at]. Forgets it on the way out. */
    fun drain(at: Duration): Duration {
        val now = totalAt(at)
        val delta = (now - drained).coerceAtLeast(Duration.ZERO)
        drained = now
        return delta
    }

    /**
     * Starts a fresh session at [at].
     *
     * `playingSince` is carried rather than cleared when the player is already playing: a book that
     * transitions straight into another one does so without a pause, and clearing here would lose the
     * seconds between the last transition and this call.
     */
    fun reset(at: Duration) {
        accumulated = Duration.ZERO
        drained = Duration.ZERO
        if (playingSince != null) playingSince = at
    }
}
