package com.example.shelfplayer.playback

import androidx.media3.session.CommandButton

/**
 * PRODUCT_SPEC PLAY-002 — publishing a button set so that a **car** actually sees it.
 *
 * ### The defect this exists for
 *
 * A device run reported the Car action never lighting up when the car was the output. The icon selection
 * was right, the state was right, and the drawable was right — but `MediaSession.setMediaButtonPreferences`
 * does not deliver a changed button set to Android Auto at all.
 *
 * Measured against `media3-session-1.11.0` rather than inferred. Android Auto reads custom actions out of
 * the legacy `PlaybackStateCompat`, which `MediaSessionLegacyStub.createPlaybackStateCompat` builds, and
 * only `updateLegacySessionPlaybackState` pushes. Inside `MediaSessionImpl`:
 *
 * - `setMediaButtonPreferences(List)` — the whole-session call — recomputes the stub's internal layout and
 *   then dispatches to **Media3** controllers only. It never touches the legacy playback state, so a car
 *   keeps drawing the previous icons until some unrelated player event happens to rebuild the state.
 * - `setMediaButtonPreferences(ControllerInfo, List)` **does** call `updateLegacySessionPlaybackState`, but
 *   only when the controller is the media notification controller.
 *
 * So the second call is the one that reaches the car, and `MediaSession.getMediaNotificationControllerInfo`
 * is the public handle on the controller that unlocks it. Both calls are needed: the first is what other
 * Media3 controllers and the stored session state read, the second is what refreshes the legacy layout.
 *
 * ### Why this is a named function with a test
 *
 * Because the second call looks redundant. It publishes the same list to a controller that already received
 * it, and every reason it is not redundant lives in decompiled third-party bytecode rather than in this
 * repository. That is precisely the shape of edit that gets tidied away — `OutputActionIcons` exists for the
 * same reason, after two `if`s vanished in a revert with nothing failing (`docs/risks.md` R-100).
 */
internal object MediaButtonPublishing {

    /**
     * Tracks the last output/car-binding state that was actually published.
     *
     * The car-bound bit is intentionally part of the key even when [OutputButtons] is byte-for-byte the
     * same: issue #38 changes list priority on that transition. Conversely, a second car controller binding
     * while one is already bound must not cause another publish merely because onConnect fired again.
     */
    internal class Tracker {
        private var published: State? = null

        fun needsPublish(outputButtons: OutputButtons, carBound: Boolean): Boolean =
            published != State(outputButtons, carBound)

        fun markPublished(outputButtons: OutputButtons, carBound: Boolean) {
            published = State(outputButtons, carBound)
        }

        private data class State(
            val outputButtons: OutputButtons,
            val carBound: Boolean,
        )
    }

    /**
     * Publishes [buttons] everywhere they have to go, in the order they have to go.
     *
     * @param toAllControllers the whole-session publish; what Media3 controllers and the session's own
     *   stored preferences read.
     * @param toNotificationController the per-controller publish for the media notification controller,
     *   which is the only path that refreshes the legacy `PlaybackStateCompat` a car draws from. `null` when
     *   that controller is not connected — early in a session's life, and never a reason to skip the first
     *   call.
     */
    fun publish(
        buttons: List<CommandButton>,
        toAllControllers: (List<CommandButton>) -> Unit,
        toNotificationController: ((List<CommandButton>) -> Unit)?,
    ) {
        toAllControllers(buttons)
        toNotificationController?.invoke(buttons)
    }
}
