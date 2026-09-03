package com.example.shelfplayer.feature.player

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * PRODUCT_SPEC SYNC-002 — whether the next resume needs to ask the server where the book is.
 *
 * ### The rule, and the device run that corrected it
 *
 * A pause of a few seconds cannot have been followed by somebody else listening on another device, so
 * asking is a round trip spent to learn nothing. Absorb draws the same line at two minutes and it is the
 * single biggest reason its Play feels instant.
 *
 * That reasoning has a hole, and a device found it. The whole point of the check is the listener who
 * **leaves BookWave, moves the book somewhere else, and comes back** — and that trip takes seconds, not
 * minutes. A device log showed a Play with no check recorded at all: paused in-app at 12:18, resumed at
 * 12:19, under the gate, so the freshness check never ran and the position from the web player was never
 * adopted. The gate was suppressing precisely the case it was written to serve.
 *
 * So **leaving the app forgets the pause**. [onLeftApp] clears the mark, and a cleared mark means *check*
 * — the same thing an unknown pause means. What is left behind the gate is the resume that genuinely
 * cannot have been overtaken: paused and resumed inside [minimumPause] without ever leaving the screen,
 * by somebody who was holding the phone the whole time.
 *
 * ### Why a mark and not a clock
 *
 * The question is "how long since", which is an interval, and an interval read from wall-clock time is
 * wrong across a time-zone change or an NTP correction. [timeSource] is a parameter so a test can move
 * time deliberately rather than sleep; production uses the monotonic source.
 *
 * ### What `null` means, in all three of its cases
 *
 * *Check.* Nothing has paused through the app yet — every resume after a cold start; a pause that came
 * from the notification, the car or a headset, which never reaches the ViewModel this belongs to; or a
 * pause the listener has since left the app on. Erring towards asking is the safe direction: the cost is
 * two seconds at worst, and the alternative is resuming on a position somebody else has moved.
 */
internal class ResumeFreshness(
    private val minimumPause: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var pausedAt: TimeMark? = null

    /**
     * A pause the listener performed here, in the app, with the screen in front of them.
     *
     * Only these are remembered. A pause from anywhere else does not reach this, and the resulting `null`
     * is the honest answer rather than a gap — see the header.
     */
    fun onPausedInApp() {
        pausedAt = timeSource.markNow()
    }

    /**
     * The app went to the background, so no pause is short any more.
     *
     * A rotation also stops the activity, so this can fire without the listener having gone anywhere. That
     * costs one capped request on the next Play, and the alternative — inferring a real background
     * transition from state — is the kind of guess that misses the case it exists for.
     */
    fun onLeftApp() {
        pausedAt = null
    }

    /** Whether this resume could plausibly have been overtaken by another device. */
    fun isCheckNeeded(): Boolean = pausedAt?.let { mark -> mark.elapsedNow() >= minimumPause } ?: true
}
