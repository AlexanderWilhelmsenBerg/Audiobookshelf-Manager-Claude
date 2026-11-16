package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Gate-owned record of the first positive measurement of another route while a headset was still owned.
 *
 * APK 2272 measured the playing track on the phone speaker for tens of milliseconds while Android swapped
 * the active Bluetooth media device, immediately before the headset endpoint left the inventory. One such
 * sample is not a listener's route move: it is resolved by the safety pause or focus loss that follows, or
 * becomes a real move only when no interruption arrives within [GRACE]. Never observes devices or calls Play.
 */
internal class HeadsetDisplacementGrace(private val now: () -> Duration) {
    private var since: Duration? = null

    /** Diagnostics only: a sample was resolved as part of the interruption that followed. */
    var onKept: () -> Unit = {}

    /** Starts the grace on the first other-route sample; later samples cannot extend it. */
    fun holds(): Boolean {
        val started = since ?: now().also { since = it }
        return now() - started in Duration.ZERO..<GRACE
    }

    fun clear() {
        since = null
    }

    /**
     * True when the sample outlived the grace and the headset must be forgotten. A car boundary is not an
     * interruption of playback, so it leaves a recent sample undecided.
     */
    fun settle(at: Duration, interruption: Boolean): Boolean {
        val started = since ?: return false
        val recent = at - started in Duration.ZERO..<GRACE
        if (recent && !interruption) return false
        since = null
        if (recent) onKept()
        return !recent
    }

    private companion object {
        /** Matches the router's missing-headset grace; a longer-lived other route is a real move. */
        val GRACE: Duration = 2.seconds
    }
}
