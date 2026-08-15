package com.example.shelfplayer.playback

import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC ROUTE-002 — *"Duplicate connection callbacks within ten seconds trigger at most one action."*
 *
 * The failure being prevented is not cosmetic: one pair of headphones going in produces several callbacks,
 * and without the gate that is a book started, restarted, and seeked back to wherever the second callback
 * thought it was.
 */
class DeviceConnectionsTest {

    private val connections = DeviceConnections()

    @Test
    fun `the first connection is acted on`() {
        assertTrue(connections.shouldAct("bluetooth:earbuds", AT))
    }

    /** A2DP and SCO announce themselves separately. This is the ordinary case, not an edge one. */
    @Test
    fun `a duplicate within the window is ignored`() {
        connections.shouldAct("bluetooth:earbuds", AT)

        assertFalse(connections.shouldAct("bluetooth:earbuds", AT.plusSeconds(3)))
    }

    @Test
    fun `a connection after the window is acted on again`() {
        connections.shouldAct("bluetooth:earbuds", AT)

        assertTrue(connections.shouldAct("bluetooth:earbuds", AT.plusSeconds(11)))
    }

    /** Exactly ten seconds is outside the window: "within ten seconds" is the part that is suppressed. */
    @Test
    fun `the boundary is not inside the window`() {
        connections.shouldAct("bluetooth:earbuds", AT)

        assertTrue(connections.shouldAct("bluetooth:earbuds", AT.plusSeconds(10)))
    }

    /**
     * Plugging in headphones and switching on a speaker within a few seconds is something people do, and
     * the second must not be swallowed by the first.
     */
    @Test
    fun `two different devices are independent`() {
        connections.shouldAct("bluetooth:earbuds", AT)

        assertTrue(connections.shouldAct("wired", AT.plusSeconds(1)))
    }

    /** Unplugging and plugging back in is a deliberate act, and is not a duplicate of the first one. */
    @Test
    fun `a disconnect reopens the gate`() {
        connections.shouldAct("wired", AT)
        connections.onDisconnected("wired")

        assertTrue(connections.shouldAct("wired", AT.plusSeconds(2)))
    }

    /** A burst is one action, not one per callback. */
    @Test
    fun `a burst of callbacks produces one action`() {
        val acted = (0..5).count { offset -> connections.shouldAct("bluetooth:earbuds", AT.plusMillis(offset * 200L)) }

        assertTrue(acted == 1, "acted $acted times")
    }

    /** The window is configurable, and the default is the requirement's ten seconds. */
    @Test
    fun `the window is what the caller asks for`() {
        val brief = DeviceConnections(window = Duration.ofSeconds(2))
        brief.shouldAct("wired", AT)

        assertTrue(brief.shouldAct("wired", AT.plusSeconds(3)))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-08-15T09:00:00Z")
    }
}
