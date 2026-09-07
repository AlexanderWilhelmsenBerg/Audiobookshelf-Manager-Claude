package com.example.shelfplayer.playback

import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-002 / PLAY-001 — whether a car is bound to the media session **right now**.
 *
 * `CarConnections` already answered *"has one ever been"*, for the diagnostics screen. The car output button
 * needs the present tense, and the two are different questions: a phone that drove somewhere this morning
 * should not still be offering to put the book in a car at midnight.
 */
class CarConnectionsTest {

    private val connections = CarConnections(TestAppClock())

    @Test
    fun `nothing is connected before anything connects`() {
        assertFalse(connections.isConnected())
    }

    @Test
    fun `a car that connects and leaves is no longer connected`() {
        connections.onConnected()
        assertTrue(connections.isConnected())

        connections.onDisconnected()

        assertFalse(connections.isConnected())
    }

    /**
     * Two controllers, one car. Android Auto's projection host and the phone's own companion are separate
     * bindings and both match the car packages, so a flag would clear on the first disconnect and take the
     * button away while the car was still there.
     */
    @Test
    fun `two bindings need two disconnects`() {
        connections.onConnected()
        connections.onConnected()

        connections.onDisconnected()

        assertTrue(connections.isConnected())
    }

    /**
     * Product priority 1 — only the first of a car's bindings is a car *arriving*.
     *
     * `CarArrivalContinuity` pairs its resume to an arrival rather than to a car being connected, because
     * the latter stays true for a whole drive; a review found that resuming any recent system pause while
     * it held would undo an incoming call's.
     */
    @Test
    fun `only the first binding reports an arrival`() {
        assertTrue(connections.onConnected())
        assertFalse(connections.onConnected())
    }

    /** A car that left and came back has arrived again. */
    @Test
    fun `a car reconnecting reports a new arrival`() {
        connections.onConnected()
        connections.onDisconnected()

        assertTrue(connections.onConnected())
    }

    /**
     * A disconnect with nothing connected cannot push the count negative.
     *
     * Media3 does not promise a disconnect for every connect, and the reverse happens too: a service
     * recreated under an already-bound car sees the disconnect having never seen the connect. A negative
     * count would hide the button for the rest of the process's life.
     */
    @Test
    fun `an unmatched disconnect cannot go negative`() {
        connections.onDisconnected()
        connections.onDisconnected()

        connections.onConnected()

        assertTrue(connections.isConnected())
    }

    /** The diagnostics half is untouched: connecting still records *when*. */
    @Test
    fun `connecting still records the time for the readiness screen`() {
        connections.onConnected()

        assertNotNull(connections.lastConnectedAt())
    }
}
