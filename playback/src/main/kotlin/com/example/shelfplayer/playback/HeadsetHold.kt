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
     * Classic A2DP needs one extra guard. If a known active A2DP route has already been remembered and stays
     * connected, a *different* A2DP route becoming active without being explicitly selected must not replace
     * it. That is the race projected Android Auto creates: AirPods were active, then the dashboard becomes
     * active before `onConnect`. Definite wired/BLE/hearing-aid headsets can always replace the memory.
     */
    fun observe(outputs: List<AudioOutput>, selectedId: String?) {
        val held = remembered
        if (held != null && outputs.none { it.id == held }) remembered = null

        val active = AudioOutputRoles.activeHeadset(outputs, selectedId) ?: return
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
     * Compatibility with #78's first implementation while PlaybackService is being simplified.
     * The old setting argument is intentionally ignored: preserving an already-active headset is now the
     * product default and requires no opt-in.
     */
    @Suppress("UNUSED_PARAMETER")
    fun holdOnCarArrival(outputs: List<AudioOutput>, enabled: Boolean): String? = holdOnCarArrival(outputs)
}
