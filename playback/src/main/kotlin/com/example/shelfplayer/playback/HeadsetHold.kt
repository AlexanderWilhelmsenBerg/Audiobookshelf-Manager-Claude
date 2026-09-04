package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — *Keep sound in the headset*, as the one decision it actually is.
 *
 * ### The race this exists to lose gracefully
 *
 * When a car connects, two things happen in an order this app does not control: the car binds to the media
 * session, and the platform moves the media route to the car. If the second happens first — and on the
 * hardware this was written against it usually does — then by the time anything here runs, *"which headset
 * is the sound in"* has no answer, because the sound is already in the car.
 *
 * So the answer is remembered continuously instead of asked for at the moment it is needed. Every published
 * output list updates [remembered], and the car's arrival only reads it. A headset that disconnects clears
 * it, so the app never pins a route to something that has gone.
 *
 * ### What it refuses to do
 *
 * It will not move audio **to** a headset. A headset that is connected but not playing is somebody's earbuds
 * in a pocket, and starting to play into a pocket because a car door opened is a worse failure than the one
 * this feature prevents. [remembered] is only ever set from the *active* route (or, below API 33 where there
 * is no route to read, from the explicit choice — see `AudioOutputRoles.current`).
 *
 * Not thread-confined by construction: `PlaybackService` touches it from its own single collector and from
 * the session callback, and both are dispatched on the main looper. Stated rather than assumed, because
 * `docs/risks.md` R-66 is the last time this file's neighbour assumed it.
 */
internal class HeadsetHold {

    /** The headset the book was last heard in, or `null`. Never one that is merely connected. */
    var remembered: String? = null
        private set

    /**
     * Takes a freshly published output list.
     *
     * Forgetting comes first: a list that no longer contains the remembered device means it disconnected,
     * and holding an id that names nothing would let a later reconnection of a *different* device with the
     * same advertised name inherit the hold.
     */
    fun observe(outputs: List<AudioOutput>, selectedId: String?) {
        val held = remembered
        if (held != null && outputs.none { it.id == held }) remembered = null
        AudioOutputRoles.activeHeadset(outputs, selectedId)?.let { headset -> remembered = headset.id }
    }

    /**
     * The headset id to pin now that a car has arrived, or `null` to leave routing entirely alone.
     *
     * `null` is the common answer and the safe one: the setting is off, or nothing was in a headset, or the
     * headset has since gone. Returning an id is the app taking over a routing decision the platform would
     * otherwise make, which is why every one of those has to be true first.
     */
    fun holdOnCarArrival(outputs: List<AudioOutput>, enabled: Boolean): String? {
        if (!enabled) return null
        val held = remembered ?: return null
        return held.takeIf { id -> outputs.any { it.id == id } }
    }
}
