package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Product priority 1 — the device report that a car arriving stops the book: *"when listening to something
 * when android auto is connecting, it pauses the audio. If listening on a headset, it should not stop."*
 *
 * The cases worth writing down are the refusals. Resuming is the easy half; not resuming a book somebody
 * deliberately paused, and not putting one on the phone speaker, are what make the resume safe to have.
 */
class CarArrivalContinuityTest {

    private val continuity = CarArrivalContinuity()

    @Test
    fun `a platform pause moments before a car arrives is resumed`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds), selectedId = null))
    }

    /** The whole point: the book was on a headset, and it should still be playing on it. */
    @Test
    fun `the headset the book was held in counts as somewhere to resume`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds, inactiveSpeaker), selectedId = null))
    }

    @Test
    fun `a pause a person asked for is never resumed by a car`() {
        continuity.onUserPause()

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds), selectedId = null))
    }

    /**
     * A system pause, then a person pauses again before the car binds.
     *
     * The later intent is the one that counts, and it is a person's. Without this the car would undo it.
     */
    @Test
    fun `a person pausing after a platform pause cancels the resume`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        continuity.onUserPause()

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds), selectedId = null))
    }

    /** A book paused in a pocket half an hour ago must not start playing because a car connected. */
    @Test
    fun `a pause older than the window is left alone`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds), selectedId = null))
    }

    /**
     * PLAY-002 — the unplug case, which shares its mechanism with the hand-off and must not share its
     * outcome. Nothing is left but the phone speaker, so the book stays stopped.
     */
    @Test
    fun `a route that settled on nothing but a speaker is not resumed`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeSpeaker), selectedId = null))
    }

    /**
     * **A connected output is not a route, and two reviews were needed to settle it here.**
     *
     * Below API 33 `AudioOutputRouter` marks every output inactive under Automatic routing, so the first
     * version of this accepted a connected non-speaker instead — reasoning that Android would not pick the
     * built-in speaker while another output was connected. It does:
     * `getDevices(GET_DEVICES_OUTPUTS)` lists every connected *sink*, including a Bluetooth device
     * connected for hands-free only, an A2DP device the output switcher points away from, and an
     * unselected USB sink, all while media plays out of the phone.
     *
     * So connection alone leaves the book paused. That is the platform's limit, not a preference: starting
     * an audiobook aloud on the phone speaker is what PLAY-002 forbids, and it is worse than the paused
     * book it would be avoiding.
     */
    @Test
    fun `a connected but inactive headset is not evidence of a route`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(
            continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveSpeaker, inactiveBuds), selectedId = null),
        )
    }

    /**
     * The pre-API-33 case that does still resume: an explicit BookWave selection.
     *
     * `publish` marks the chosen id active even with no framework route to read, so the listener's own
     * choice is the route evidence. It is the one legacy path where the question can be answered honestly.
     */
    @Test
    fun `an explicitly chosen output is route evidence even with nothing else active`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveSpeaker, activeBuds), selectedId = null))
    }

    /** Nothing connected at all is not an invitation either. */
    @Test
    fun `with nothing connected there is nowhere to resume`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), emptyList(), selectedId = null))
    }

    @Test
    fun `with no pause to act on a car arriving changes nothing`() {
        assertFalse(continuity.shouldResume(AT, listOf(activeBuds), selectedId = null))
    }

    /**
     * Two car controllers bind for one car — `CarConnections` counts them for exactly that reason — so the
     * question is asked twice. The resume consumed the pause, so the second finds nothing.
     */
    @Test
    fun `the second car controller does not resume again`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds), selectedId = null))

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds), selectedId = null))
    }

    /**
     * **The ordering a review caught.** The car's controller binds *before* the platform pauses, so the
     * first ask happens while the book is still playing and there is no pause to act on. The pause then
     * arrives, and the route publication that follows is what has to resume it.
     *
     * The first ask is the service's `playWhenReady` check rather than this class, so what is asserted here
     * is the half this class owns: a pause recorded after an ask still resumes on the next one.
     */
    @Test
    fun `a pause recorded after the car bound is still resumed by a later route publication`() {
        continuity.onCarArrived(AT)
        assertFalse(continuity.shouldResume(AT, listOf(activeBuds), selectedId = null))

        continuity.onSystemPause(AT.plusSeconds(1))

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds), selectedId = null))
    }

    /**
     * The third ordering: both the pause and the binding have happened, and the route is still moving.
     *
     * Answering `false` must not spend the pause — the settle publication that follows is the one that can
     * say yes. The first version of this consumed the candidacy on every ask, which left the book stopped
     * exactly here.
     */
    @Test
    fun `an unsettled route keeps the pause for the next ask`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        // The route has not landed on anything BookWave will play yet: the speaker is live and the buds
        // are merely connected. Answering no must not spend the pause.
        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeSpeaker, inactiveBuds), selectedId = null))
        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds), selectedId = null))
    }

    /** A pause held across an unsettled route still expires; waiting is not a way around the window. */
    @Test
    fun `a pause held through an unsettled route still expires`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeSpeaker), selectedId = null))

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds), selectedId = null))
    }

    /*
     * The pause has to be *the car's*. A review found "a car is connected" was standing in for that, and it
     * stays true for a whole drive — so every system pause during one was a resume candidate.
     */

    /** An incoming call takes audio focus mid-drive. The book must stay paused. */
    @Test
    fun `a system pause with no car arrival to pair with is not resumed`() {
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds), selectedId = null))
    }

    /** The same call, an hour into the drive: the car arrived long ago and is not what stopped the book. */
    @Test
    fun `a system pause long after the car arrived is not resumed`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT.plus(Duration.ofHours(1)))

        assertFalse(
            continuity.shouldResume(AT.plus(Duration.ofHours(1)).plusSeconds(2), listOf(activeBuds), selectedId = null),
        )
    }

    /** Order does not matter, only closeness: the pause landing just before the binding still pairs. */
    @Test
    fun `a pause just before the car arrival still pairs with it`() {
        continuity.onSystemPause(AT)
        continuity.onCarArrived(AT.plusSeconds(3))

        assertTrue(continuity.shouldResume(AT.plusSeconds(4), listOf(activeBuds), selectedId = null))
    }

    /**
     * One resume per arrival.
     *
     * Without it, a resume the platform immediately undoes — focus still held elsewhere — is re-recorded as
     * a system pause and resumed again on the next route publication, and so on without end.
     */
    @Test
    fun `a second pause after a resume is not resumed by the same arrival`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds), selectedId = null))

        continuity.onSystemPause(AT.plusSeconds(2))

        assertFalse(continuity.shouldResume(AT.plusSeconds(3), listOf(activeBuds), selectedId = null))
    }

    /**
     * Media3 recovers from a *transient* focus loss by itself. The book is playing again, and the pause it
     * came from must not still be sitting there for a car arriving later to act on.
     */
    @Test
    fun `a book that started playing again leaves no pause behind`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        continuity.onPlaying()

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds), selectedId = null))
    }

    /*
     * A route that has been *asked* for. `AudioOutputRouter.select` publishes synchronously and applies
     * `setPreferredAudioDevice` on another coroutine, so the headset hold's own publication still carries
     * the framework's old route — the car's. A review found that starting the book on it defeats the hold.
     */

    /** The hold has asked for the earbuds and the platform is still on the dashboard. Wait. */
    @Test
    fun `a requested route that is not active yet is not somewhere to play`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(
            continuity.shouldResume(AT.plusSeconds(1), listOf(activeDashboard, inactiveBuds), BUDS),
        )
    }

    /** The settle publication: the preference was honoured, so this is the ask that says yes. */
    @Test
    fun `a requested route becoming active is what resumes the book`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertFalse(
            continuity.shouldResume(AT.plusSeconds(1), listOf(activeDashboard, inactiveBuds), BUDS),
        )

        assertTrue(
            continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveDashboard, activeBuds), BUDS),
        )
    }

    /**
     * The preference declined, which `setPreferredAudioDevice` is entitled to be.
     *
     * The book stays paused rather than starting in the car. That is the safe direction and the honest
     * one: the listener asked for a headset, and playing somewhere they did not choose is not a smaller
     * failure than leaving the book where it already was.
     */
    @Test
    fun `a request the platform never honours leaves the book paused`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(
            continuity.shouldResume(AT.plusSeconds(2), listOf(activeDashboard, inactiveBuds), BUDS),
        )
        assertFalse(
            continuity.shouldResume(AT.plusSeconds(5), listOf(activeDashboard, inactiveBuds), BUDS),
        )
    }

    /** With no request outstanding — Car pressed, or nothing held — the live route is the answer. */
    @Test
    fun `with no request outstanding the active car route resumes the book`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeDashboard), selectedId = null))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-07T07:00:00Z")

        val activeBuds = AudioOutput(
            id = "bluetooth:buds",
            displayName = "Earbuds",
            kind = DeviceKind.Bluetooth,
            isActive = true,
            role = AudioOutputRole.Ambiguous,
        )
        val inactiveBuds = activeBuds.copy(isActive = false)
        val activeSpeaker = AudioOutput(
            id = "speaker:phone",
            displayName = "Phone speaker",
            kind = DeviceKind.Speaker,
            isActive = true,
            role = AudioOutputRole.Speaker,
        )
        val inactiveSpeaker = activeSpeaker.copy(isActive = false)

        /** The projected dashboard: classic A2DP, so ambiguous rather than a speaker (ADR-0029 §4). */
        val activeDashboard = AudioOutput(
            id = "bluetooth:dashboard",
            displayName = "Dashboard",
            kind = DeviceKind.Bluetooth,
            isActive = true,
            role = AudioOutputRole.Ambiguous,
        )
        val inactiveDashboard = activeDashboard.copy(isActive = false)

        /** The id the headset hold asks for. */
        const val BUDS = "bluetooth:buds"
    }
}
