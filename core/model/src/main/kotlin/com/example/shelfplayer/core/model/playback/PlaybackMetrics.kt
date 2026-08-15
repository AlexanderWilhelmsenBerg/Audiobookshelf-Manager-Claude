package com.example.shelfplayer.core.model.playback

import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-006 — *"Rebuffer count and startup latency are available in local diagnostics without
 * media titles unless user opts in."*
 *
 * ### What these two numbers are for
 *
 * PLAY-006 lets the listener choose a buffer preset, and a preset is a guess until something measures it.
 * These are that measurement, and they pull in opposite directions on purpose: a larger buffer should push
 * [rebuffers] down and [lastStartup] up. Somebody deciding between *Standard* and *High* on a slow
 * connection needs both, and a screen that showed one would recommend the wrong preset.
 *
 * ### No titles, ever
 *
 * Counts and durations only — there is nowhere here to put a book's name, which is the point rather than an
 * omission. The requirement's *"unless user opts in"* is about the diagnostics *log*, which already has its
 * own opt-in (PRODUCT_SPEC 14.5); this type simply has nothing to opt into.
 *
 * @property rebuffers how many times playback ran dry and had to wait, since the process started. Reset by
 *   a restart because that is what it is: a reading of this session, not a lifetime statistic.
 * @property lastStartup how long the most recent book took from prepare to first sound. `null` before
 *   anything has been played.
 * @property slowestStartup the worst of this session, which is the number a listener actually notices —
 *   an average hides the one that took eleven seconds.
 * @property startupsMeasured how many starts the two figures above are drawn from, so a single unlucky
 *   reading is recognisable as one.
 */
data class PlaybackMetrics(
    val rebuffers: Int = 0,
    val lastStartup: Duration? = null,
    val slowestStartup: Duration? = null,
    val startupsMeasured: Int = 0,
) {

    /** Nothing has played yet, so the screen should say that rather than show three zeroes. */
    val isEmpty: Boolean get() = startupsMeasured == 0 && rebuffers == 0

    companion object {
        val Empty: PlaybackMetrics = PlaybackMetrics()
    }
}
