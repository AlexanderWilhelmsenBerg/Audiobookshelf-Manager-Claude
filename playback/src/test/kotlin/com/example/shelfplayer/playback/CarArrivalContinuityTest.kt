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
        continuity.onSystemPause(AT)

        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
    }

    /** The whole point: the book was on a headset, and it should still be playing on it. */
    @Test
    fun `the headset the book was held in counts as somewhere to resume`() {
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
        continuity.onSystemPause(AT)
        continuity.onUserPause()

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(activeBuds)))
    }

    /** A book paused in a pocket half an hour ago must not start playing because a car connected. */
    @Test
    fun `a pause older than the window is left alone`() {
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds)))
    }

    /**
     * PLAY-002 — the unplug case, which shares its mechanism with the hand-off and must not share its
     * outcome. Nothing is left but the phone speaker, so the book stays stopped.
     */
    @Test
    fun `a route that settled on nothing but a speaker is not resumed`() {
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(activeSpeaker)))
    }

    /** No active route at all is not evidence that anywhere is safe to play. */
    @Test
    fun `a route that settled on nothing is not resumed`() {
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(2), listOf(inactiveSpeaker, inactiveBuds)))
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
        continuity.onSystemPause(AT)

        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(inactiveBuds)))
        assertTrue(continuity.shouldResume(AT.plusSeconds(2), listOf(activeBuds)))
    }

    /** A pause held across an unsettled route still expires; waiting is not a way around the window. */
    @Test
    fun `a pause held through an unsettled route still expires`() {
        continuity.onSystemPause(AT)
        assertFalse(continuity.shouldResume(AT.plusSeconds(1), listOf(inactiveBuds)))

        assertFalse(continuity.shouldResume(AT.plus(Duration.ofMinutes(30)), listOf(activeBuds)))
    }

    /**
     * Media3 recovers from a *transient* focus loss by itself. The book is playing again, and the pause it
     * came from must not still be sitting there for a car arriving later to act on.
     */
    @Test
    fun `a book that started playing again leaves no pause behind`() {
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
