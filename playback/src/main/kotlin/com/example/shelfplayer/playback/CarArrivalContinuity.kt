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

    private var carArrivedAt: Instant? = null

    /**
     * A car *arrived* — the 0-to-1 binding, not a second controller joining one already bound.
     *
     * A review found the difference matters more than it looks. "A car is connected" stays true for a whole
     * drive, so resuming any recent system pause while it held would have restarted the book after an
     * incoming call took audio focus. The resume is therefore paired to an arrival, and consumed by one.
     */
    fun onCarArrived(at: Instant) {
        carArrivedAt = at
    }

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
     * **Asked more than once on purpose, and that is why an unsettled route keeps the candidacy.** A review
     * found the first version of this asked only when the car's controller bound, which is one of three
     * orderings: the pause can arrive after the binding, and the route can still be moving when both have
     * happened. So the caller asks on the binding *and* on every route publication, and this answers `false`
     * without spending the pause while there is nowhere yet to play — the settle publication that follows is
     * the one that says yes.
     *
     * Only a resume and a stale pause consume it. Nothing else needs to: [onUserPause] is what stops a
     * driver's own pause being undone, including by a second car controller, and [onPlaying] retires a book
     * that is already playing again.
     */
    fun shouldResume(at: Instant, outputs: List<AudioOutput>): Boolean {
        val paused = systemPausedAt ?: return false
        if (Duration.between(paused, at) > window) {
            systemPausedAt = null
            return false
        }
        // Both clauses keep the pause rather than spending it. The first is a pause that has nothing to do
        // with a car and may yet be paired with one; the second is the hand-off still in flight, and
        // dropping it there is what left the book stopped in the ordering the first review found.
        if (!pairsWithACarArrival(paused) || !somewhereToPlay(outputs)) return false
        systemPausedAt = null
        // One resume per arrival. Without this, a resume the platform immediately undoes — audio focus is
        // still held elsewhere — would be re-recorded as a system pause and resumed again on the next route
        // publication, indefinitely.
        carArrivedAt = null
        return true
    }

    /**
     * Whether a car arriving is a plausible cause of this pause, in either order.
     *
     * The two events are the same happening seen twice, and which one the app hears first is not fixed:
     * the platform can move the route before the controller binds or after it. So they are paired by being
     * close together rather than by an order, within a window tighter than [window] — the point is to
     * exclude a pause that merely *happened during a drive*, which is what a phone call is.
     */
    private fun pairsWithACarArrival(paused: Instant): Boolean {
        val arrived = carArrivedAt ?: return false
        return Duration.between(paused, arrived).abs() <= PAIRING_WINDOW
    }

    /**
     * Whether the book is on a route BookWave is willing to play, which PLAY-002 makes a real question:
     * the phone speaker is never an answer.
     *
     * **Route evidence only, and two reviews got it here.** The first observed that below API 33
     * `AudioOutputRouter` cannot ask the platform which route is live, and under Automatic routing has no
     * selection to fall back on either, so it marks *every* output inactive and this can never pass —
     * minSdk is 26, so the resume does nothing across six API levels. The obvious remedy was to accept a
     * connected non-speaker output instead, on the reasoning that Android does not pick the built-in
     * speaker while another output is connected.
     *
     * **That reasoning was wrong, and the second review caught it.** `getDevices(GET_DEVICES_OUTPUTS)`
     * reports every connected *sink*, which is not the same as a route: a Bluetooth device connected for
     * hands-free only, an A2DP device the system output switcher has been pointed away from, or an
     * unselected USB sink all sit in that list while media plays out of the phone. So the fallback could
     * start an audiobook aloud on the speaker — the outcome PLAY-002 exists to forbid, and a worse one than
     * the paused book it was trying to avoid.
     *
     * So the platform limitation stands rather than being papered over. Below API 33, a listener who has
     * explicitly chosen an output in BookWave still gets the resume, because that choice *is* the evidence
     * — `publish` marks the chosen id active. Everyone else keeps a paused book, which is where it already
     * was. R-106 records it, and `isBluetoothA2dpOn` is the one documented pre-33 call that reports media
     * *routing* rather than connection if the case is ever worth recovering; it needs a device to validate,
     * and guessing it from here is what produced this entry twice.
     */
    private fun somewhereToPlay(outputs: List<AudioOutput>): Boolean =
        outputs.any { output -> output.isActive && !output.isSpeaker }

    private companion object {
        /**
         * Long enough for a projection host to start and the route to settle, short enough that the pause
         * being resumed is recognisably the one the car caused.
         */
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(12)

        /**
         * How far apart the pause and the arrival may be and still be one event.
         *
         * Tighter than [DEFAULT_WINDOW], which measures how long afterwards the resume may still be
         * attempted while the route settles. This one decides whether there is anything to attempt at all,
         * and every second of it is a second in which an unrelated pause can be mistaken for the car's.
         */
        val PAIRING_WINDOW: Duration = Duration.ofSeconds(6)
    }
}
