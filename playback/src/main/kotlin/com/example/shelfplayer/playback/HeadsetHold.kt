package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — preserve the headset already carrying BookWave when a car arrives.
 *
 * This is normal routing behavior, not a preference. A merely connected headset is never selected. The
 * active route is remembered continuously because Android may move media to the car before the media-session
 * controller callback arrives.
 */
internal class HeadsetHold {

    /** The headset/candidate the book was last heard in, or `null`. */
    var remembered: String? = null
        private set

    /**
     * Takes a freshly published output list.
     *
     * [hasMedia] is whether BookWave is holding a book at all; with none, there is no "headset the book was
     * coming out of" to remember and any existing memory is dropped.
     *
     * Classic A2DP needs one extra guard. If a known active A2DP route has already been remembered and stays
     * connected, a *different* A2DP route becoming active without being explicitly selected must not replace
     * it. That is the race projected Android Auto creates: AirPods were active, then the dashboard becomes
     * active before `onConnect`. Definite wired/BLE/hearing-aid headsets can always replace the memory.
     */
    /**
     * The headset an explicit *Car* press gave up, refused until the route has actually left it.
     *
     * Clearing alone is not enough: `select(null)` publishes a selection change while the platform still
     * reports the headset as the active route, so the very next observation re-remembers it and the press is
     * undone. The refusal lifts the moment the observed route moves elsewhere or that device disconnects.
     */
    private var releasedToCar: String? = null

    fun observe(outputs: List<AudioOutput>, selectedId: String?, hasMedia: Boolean) {
        // On API 33+ the "active" route is the platform's media route for BookWave's attributes, which is
        // populated whether or not BookWave is holding a book. Without this gate, earbuds connected to an
        // idle app are remembered, and a car arriving then pins the player to a headset in somebody's
        // pocket — the failure this class exists to prevent, arrived at from the other direction.
        if (!hasMedia) {
            remembered = null
            return
        }

        val held = remembered
        if (held != null && outputs.none { it.id == held }) remembered = null

        // Lifted as soon as the route is no longer the released headset — measured against the *route*, not
        // against the active headset. A definite car is not a headset candidate, so narrowing first meant a
        // TYPE_BUS car never lifted the release and the listener's next Headset press was refused forever.
        releasedToCar?.let { released ->
            if (AudioOutputRoles.current(outputs, selectedId)?.id != released) releasedToCar = null
        }

        val active = AudioOutputRoles.activeHeadset(outputs, selectedId) ?: return
        if (active.id == releasedToCar) return
        if (active.role != AudioOutputRole.Ambiguous) {
            remembered = active.id
            return
        }

        val stillHeld = remembered?.let { id -> outputs.any { it.id == id } } == true
        if (!stillHeld || selectedId == active.id || remembered == active.id) {
            remembered = active.id
        }
    }

    /** The remembered headset to reassert on car arrival, if it is still connected. */
    fun holdOnCarArrival(outputs: List<AudioOutput>): String? {
        val held = remembered ?: return null
        return held.takeIf { id -> outputs.any { it.id == id && it.isHeadsetCandidate } }
    }

    /**
     * Drops the memory outright.
     *
     * Two callers, and both are cases the continuous observation cannot see: an explicit *Car* press, which
     * is a choice that must outlive the hold, and an emptied queue, which `PlaybackController.stop()`
     * produces without touching either output flow.
     */
    fun forget() {
        remembered = null
        releasedToCar = null
    }

    /**
     * An explicit *Car* press: give up the held headset and refuse to re-adopt it until the route moves.
     *
     * The headset given up is whichever this was holding, or the active one if the press arrived before
     * anything was remembered.
     */
    fun releaseToCar(outputs: List<AudioOutput>, selectedId: String?) {
        releasedToCar = remembered ?: AudioOutputRoles.activeHeadset(outputs, selectedId)?.id
        remembered = null
    }
}
