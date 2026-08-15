package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.playback.PlaybackMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-006 — *"Rebuffer count and startup latency are available in local diagnostics."*
 *
 * ### Why a rebuffer is not simply `STATE_BUFFERING`
 *
 * Media3 reports `STATE_BUFFERING` for the *first* fill as well as for running dry mid-sentence, and those
 * are different events: the first is startup latency, which this also measures, and counting it as a
 * rebuffer would report one on every book that ever played. A rebuffer is buffering that happens **after**
 * the player has been ready at least once for this item.
 *
 * ### Why the monotonic clock
 *
 * Startup latency is an elapsed duration, and wall-clock time can move sideways under it — an NTP
 * correction mid-load would otherwise produce a negative or absurd reading and put it in the diagnostics
 * as the slowest start of the session.
 *
 * ### There is nowhere to put a title
 *
 * PLAY-006 asks for these figures "without media titles unless user opts in". [PlaybackMetrics] has no
 * field one could go in, which is the point rather than an omission.
 */
@Singleton
class PlaybackMetricsRecorder @Inject constructor(private val clock: AppClock) {

    private val _metrics = MutableStateFlow(PlaybackMetrics.Empty)
    val metrics: StateFlow<PlaybackMetrics> = _metrics.asStateFlow()

    /** When the current item started loading, or `null` once it has been ready. */
    private var loadingSince: Duration? = null

    /** Whether this item has ever been ready, which is what tells a first fill from a rebuffer. */
    private var hasBeenReady: Boolean = false

    /**
     * A new item was prepared. Starts the startup-latency stopwatch.
     *
     * Called on every item change, not only on the first — the wait to hear a book is the wait *for that
     * book*, and a listener who switches to a different one after ten minutes is starting again.
     */
    fun onItemPrepared() {
        loadingSince = clock.elapsed()
        hasBeenReady = false
    }

    /** The player began waiting for data. Counts only once this item has already played. */
    fun onBuffering() {
        if (!hasBeenReady) return
        _metrics.value = _metrics.value.copy(rebuffers = _metrics.value.rebuffers + 1)
    }

    /**
     * The player became ready. Stops the stopwatch, the first time only.
     *
     * `STATE_READY` arrives again after every rebuffer, and treating those as starts would fill the
     * diagnostics with fast "startups" that are really recoveries — hiding the slow first load that is the
     * number somebody is trying to see.
     */
    fun onReady() {
        val since = loadingSince ?: return
        loadingSince = null
        hasBeenReady = true

        val latency = clock.elapsed() - since
        // A negative or wild reading means the monotonic source was not monotonic. Dropping it is better
        // than publishing a figure that would make a buffer preset look responsible for nonsense.
        if (latency.isNegative()) return

        val current = _metrics.value
        _metrics.value = current.copy(
            lastStartup = latency,
            slowestStartup = maxOf(latency, current.slowestStartup ?: latency),
            startupsMeasured = current.startupsMeasured + 1,
        )
    }

    /** The player was released. The next item starts a fresh measurement, but the session's counts stand. */
    fun onReleased() {
        loadingSince = null
        hasBeenReady = false
    }
}
