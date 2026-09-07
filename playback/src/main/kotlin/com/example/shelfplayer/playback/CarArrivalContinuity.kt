package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import java.time.Duration
import java.time.Instant

/**
 * Product priority 1 — a car arriving must not leave the book paused.
 *
 * ### The defect this exists for
 *
 * A device run reported it plainly: *"when listening to something when android auto is connecting, it
 * pauses the audio."* Nothing in this app pauses on car arrival. The platform does, and by one of two
 * routes:
 *
 * - **`ACTION_AUDIO_BECOMING_NOISY`.** Android broadcasts it when an A2DP sink is deactivated, which is
 *   what happens to earbuds when a car takes the active A2DP slot. `setHandleAudioBecomingNoisy(true)` in
 *   [PlayerFactory] then pauses. Media3 has **no** matching resume for it.
 * - **A permanent audio-focus loss** while the projection host starts.
 *
 * Both arrive as a `playWhenReady` change with a reason that is not `USER_REQUEST`, and neither is a
 * person pressing pause. `PlaybackService`'s listener already draws exactly that line for PLAY-009's
 * auto-rewind, so the fact needed here is one the service already has.
 *
 * ### Why a resume rather than suppressing the pause
 *
 * `setHandleAudioBecomingNoisy` is all or nothing: Media3 offers no way to pause for an unplug and not for
 * a hand-off. Turning it off and re-implementing it here would have to *predict*, at the instant of a
 * broadcast that says the route is only *about* to change, whether the book is headed for another output
 * or for the phone speaker — and guessing wrong puts a book on the phone speaker, which PLAY-002 forbids
 * outright.
 *
 * Reacting instead of predicting keeps that guarantee whole. The platform's pause stands, the route
 * settles, and the book resumes only once the settled route is somewhere BookWave is willing to play. The
 * cost is a gap of well under a second instead of a book that stays stopped until the driver notices —
 * and a stopped book is the failure the report described.
 *
 * ### What it refuses to resume
 *
 * - **A pause a person asked for.** Pressing pause and *then* plugging into the car must stay paused;
 *   [onUserPause] drops the candidacy outright.
 * - **A stale pause.** Only within [window] of the pause, so a book paused twenty minutes ago in a pocket
 *   does not start playing because a car connected.
 * - **A speaker.** If the settled route offers nothing but speakers, PLAY-002 wins and the book stays
 *   paused; that is the unplug case, not a hand-off.
 * - **Anything at all without a car.** The car binding is what makes this a hand-off rather than an
 *   ordinary pause, so [shouldResume] is only ever asked when one has arrived.
 *
 * Not thread-safe, and does not need to be: every caller is on the main thread, which is where the
 * player's own state has to be read anyway.
 */
internal class CarArrivalContinuity(private val window: Duration = DEFAULT_WINDOW) {

    private var systemPausedAt: Instant? = null

    /**
     * A pause nobody asked for — an audio-focus loss, or the route going noisy.
     *
     * Recording it is not a decision to resume. [shouldResume] is what decides, and only a car arriving
     * asks it.
     */
    fun onSystemPause(at: Instant) {
        systemPausedAt = at
    }

    /** A pause a person asked for, from anywhere. It ends candidacy for good, not until the window shuts. */
    fun onUserPause() {
        systemPausedAt = null
    }

    /**
     * The book is playing again, however that came about.
     *
     * Needed because a resume this class did not cause — the driver pressing play, or Media3 recovering
     * from a *transient* focus loss on its own — must not leave a pause behind for a car arriving later to
     * act on a second time.
     */
    fun onPlaying() {
        systemPausedAt = null
    }

    /**
     * Whether the book the platform paused should be started again, now that a car is here.
     *
     * Consumes the candidacy whatever the answer: a car binding is a single event, two controllers can
     * report it (`CarConnections` counts them for that reason), and the second must not resume a book the
     * driver paused in the seconds after the first.
     */
    fun shouldResume(at: Instant, outputs: List<AudioOutput>): Boolean {
        val paused = systemPausedAt ?: return false
        systemPausedAt = null
        if (Duration.between(paused, at) > window) return false
        return outputs.any { output -> output.isActive && !output.isSpeaker }
    }

    private companion object {
        /**
         * Long enough for a projection host to start and the route to settle, short enough that the pause
         * being resumed is recognisably the one the car caused.
         */
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(12)
    }
}
