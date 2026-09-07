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

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
    }

    /** The whole point: the book was on a headset, and it should still be playing on it. */
    @Test
    fun `the headset the book was held in counts as somewhere to resume`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds, inactiveSpeaker)))
    }

    @Test
    fun `a pause a person asked for is never resumed by a car`() {
        continuity.onUserPause()

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))
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

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))
    }

    /** A book paused in a pocket half an hour ago must not start playing because a car connected. */
    @Test
    fun `a pause older than the window is left alone`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds)))
    }

    /**
     * PLAY-002 — the unplug case, which shares its mechanism with the hand-off and must not share its
     * outcome. Nothing is left but the phone speaker, so the book stays stopped.
     */
    @Test
    fun `a route that settled on nothing but a speaker is not resumed`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeSpeaker)))
    }

    /**
     * **API 26–32, which a review found this had silently excluded.** `AudioOutputRouter` cannot ask the
     * platform which route is live below API 33, and under Automatic routing it has no selection to fall
     * back on either, so it marks *every* output inactive. An `isActive` test could never pass there and
     * the resume was dead across six API levels.
     *
     * With no route information at all, a connected headset is the whole of what is knowable, and it is
     * enough: Android does not choose the built-in speaker while another output is connected.
     */
    @Test
    fun `with no route information a connected headset is enough`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveSpeaker, inactiveBuds)))
    }

    /** The same platform, the unplug case: no route information and nothing but a speaker connected. */
    @Test
    fun `with no route information a speaker alone is not enough`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveSpeaker)))
    }

    /** Nothing connected at all is not an invitation either. */
    @Test
    fun `with nothing connected there is nowhere to resume`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), emptyList()))
    }

    @Test
    fun `with no pause to act on a car arriving changes nothing`() {
        assertFalse(continuity.shouldResume(AT, listOf(activeBuds)))
    }

    /**
     * Two car controllers bind for one car — `CarConnections` counts them for exactly that reason — so the
     * question is asked twice. The resume consumed the pause, so the second finds nothing.
     */
    @Test
    fun `the second car controller does not resume again`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
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
        assertFalse(continuity.shouldResume(AT, listOf(activeBuds)))

        continuity.onSystemPause(AT.plusSeconds(1))

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
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

        // Only the speaker connected: the headset has gone and the car's audio is not up yet, so there is
        // genuinely nowhere to play. Answering no must not spend the pause.
        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeSpeaker)))
        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
    }

    /** A pause held across an unsettled route still expires; waiting is not a way around the window. */
    @Test
    fun `a pause held through an unsettled route still expires`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT)
        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeSpeaker)))

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds)))
    }

    /*
     * The pause has to be *the car's*. A review found "a car is connected" was standing in for that, and it
     * stays true for a whole drive — so every system pause during one was a resume candidate.
     */

    /** An incoming call takes audio focus mid-drive. The book must stay paused. */
    @Test
    fun `a system pause with no car arrival to pair with is not resumed`() {
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
    }

    /** The same call, an hour into the drive: the car arrived long ago and is not what stopped the book. */
    @Test
    fun `a system pause long after the car arrived is not resumed`() {
        continuity.onCarArrived(AT)
        continuity.onSystemPause(AT.plus(Duration.ofHours(1)))

        assertFalse(
            continuity.shouldResume(AT.plus(Duration.ofHours(1)).plusSeconds(2), listOf(activeBuds)),
        )
    }

    /** Order does not matter, only closeness: the pause landing just before the binding still pairs. */
    @Test
    fun `a pause just before the car arrival still pairs with it`() {
        continuity.onSystemPause(AT)
        continuity.onCarArrived(AT.plusSeconds(3))

        assertTrue(continuity.shouldResume(AT.plusSeconds(4), listOf(activeBuds)))
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
        assertTrue(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))

        continuity.onSystemPause(AT.plusSeconds(2))

        assertFalse(continuity.shouldResume(AT.plusSeconds(3), listOf(activeBuds)))
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

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))
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
    }
}
