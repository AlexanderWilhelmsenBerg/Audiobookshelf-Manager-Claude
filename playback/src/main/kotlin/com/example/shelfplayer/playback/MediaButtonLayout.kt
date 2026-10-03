package com.example.shelfplayer.playback

import androidx.media3.session.CommandButton

/**
 * PRODUCT_SPEC PLAY-002 / PLAY-007 — the order the button list is published in.
 *
 * Android Auto and API-33+ System UI read the same media-button preferences, so BookWave cannot publish one
 * simultaneous layout for the car and another for the phone. It can, however, make the shared layout follow
 * the state that matters: whether an Android Auto controller is actually bound.
 *
 * - no car bound: skips lead, so the phone keeps skip back/forward in the compact slots;
 * - car bound: output actions lead, so Car/Headset take those slots when present.
 *
 * The sleep timer button is the exception to "shared": while a car is bound it is left out entirely
 * (PD-002, 2026-10-03), so the compact forward slot falls back to the car or skip priority.
 *
 * Every contender also names overflow as its fallback. Losing a contested slot therefore relocates a button
 * instead of dropping it. [MediaButtonLayoutTest] runs this order through Media3's real legacy conversion.
 */
internal object MediaButtonLayout {

    /**
     * Orders the two contested groups from the actual car-controller binding state.
     *
     * [carBound] deliberately does not mean "a car-like audio route exists". A projected dashboard can be
     * present as ordinary A2DP before or after Android Auto binds, while a phone notification should keep its
     * skips until the car controller really owns the session surface.
     */
    fun inPriorityOrder(
        outputActions: List<CommandButton>,
        skipActions: List<CommandButton>,
        activeTimerActions: List<CommandButton> = emptyList(),
        overflowActions: List<CommandButton>,
        carBound: Boolean,
    ): List<CommandButton> {
        val surfacePriority = if (carBound) outputActions + skipActions else skipActions + outputActions
        // BW-SLEEP-01 — while active, the timer owns its requested compact slot on every shared system
        // surface, except while a car controller is bound (PD-002, 2026-10-03: the sleep timer never shows in
        // Android Auto). It asks only for SLOT_FORWARD, so SLOT_BACK remains occupied by the highest-priority
        // car/skip action and raw Previous can never leak back in, with or without the timer.
        val timer = if (carBound) emptyList() else activeTimerActions
        return timer + surfacePriority + overflowActions
    }
}
