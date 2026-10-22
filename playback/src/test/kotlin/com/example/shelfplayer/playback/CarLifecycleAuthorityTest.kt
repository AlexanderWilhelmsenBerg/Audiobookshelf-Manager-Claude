package com.example.shelfplayer.playback

import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real lifecycle reconciliation without a fake player, output route, or recovery gate. */
internal class CarLifecycleAuthorityTest {
    private val connections = CarConnections(TestAppClock())
    private val authority = CarLifecycleAuthority(connections)

    private fun observe(
        from: AndroidAutoProjectionMonitor.State?,
        to: AndroidAutoProjectionMonitor.State,
    ): CarLifecycleAuthority.ProjectionEdge {
        authority.onProjectionReported(to)
        return authority.onProjectionBoundary(AndroidAutoProjectionMonitor.Update(from, to))
    }

    @Test
    fun `first controller establishes fallback session but only last departure retires it`() {
        authority.onControllerConnected()
        authority.onControllerConnected()
        assertTrue(authority.sessionEstablished)
        assertTrue(authority.connectedForSleepTimer)

        authority.onControllerDisconnected()
        assertTrue(connections.isConnected())
        assertTrue(authority.sessionEstablished)

        authority.onControllerDisconnected()
        assertFalse(connections.isConnected())
        authority.onFinalLegacyControllerDeparture()
        assertFalse(authority.sessionEstablished)
        assertFalse(authority.connectedForSleepTimer)
    }

    @Test
    fun `physical projection overrides stale controller disconnect and ends the interval exactly once`() {
        authority.onControllerConnected()
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.Arrival,
            observe(AndroidAutoProjectionMonitor.State.NotConnected, AndroidAutoProjectionMonitor.State.Projection),
        )
        authority.onControllerDisconnected()
        assertTrue(authority.projectionOwnsCarLifecycle)
        assertTrue(authority.connectedForSleepTimer)
        assertTrue(authority.sessionEstablished)

        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(AndroidAutoProjectionMonitor.State.Projection, AndroidAutoProjectionMonitor.State.Unknown),
        )
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(AndroidAutoProjectionMonitor.State.Unknown, AndroidAutoProjectionMonitor.State.Projection),
        )
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.PhysicalDeparture,
            observe(AndroidAutoProjectionMonitor.State.Projection, AndroidAutoProjectionMonitor.State.NotConnected),
        )
        assertFalse(authority.sessionEstablished)
        assertFalse(authority.projectionOwnsCarLifecycle)
        assertFalse(authority.connectedForSleepTimer)
        assertFalse(connections.isConnected())
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(AndroidAutoProjectionMonitor.State.NotConnected, AndroidAutoProjectionMonitor.State.NotConnected),
        )
    }

    @Test
    fun `initial positive projection is not a new arrival or a fabricated car session`() {
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(null, AndroidAutoProjectionMonitor.State.Projection),
        )
        assertTrue(authority.projectionOwnsCarLifecycle)
        assertTrue(authority.connectedForSleepTimer)
        assertFalse(authority.sessionEstablished)

        authority.onControllerConnected()
        assertTrue(authority.sessionEstablished)
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(AndroidAutoProjectionMonitor.State.Projection, AndroidAutoProjectionMonitor.State.Projection),
        )
    }

    @Test
    fun `initial controller plus positive projection remains established`() {
        authority.onControllerConnected()
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(null, AndroidAutoProjectionMonitor.State.Native),
        )
        assertTrue(authority.sessionEstablished)
        assertTrue(authority.projectionOwnsCarLifecycle)
    }

    @Test
    fun `unknown provider never invents departure even after final controller binding ends`() {
        authority.onControllerConnected()
        observe(null, AndroidAutoProjectionMonitor.State.Projection)
        authority.onControllerDisconnected()
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(AndroidAutoProjectionMonitor.State.Projection, AndroidAutoProjectionMonitor.State.Unknown),
        )
        assertTrue(authority.projectionOwnsCarLifecycle)
        assertTrue(authority.connectedForSleepTimer)
    }

    @Test
    fun `unobserved projection retains controller fallback without inventing physical arrival`() {
        authority.onControllerConnected()
        assertEquals(
            CarLifecycleAuthority.ProjectionEdge.None,
            observe(null, AndroidAutoProjectionMonitor.State.Unknown),
        )
        authority.onControllerDisconnected()
        assertFalse(connections.isConnected())
        authority.onFinalLegacyControllerDeparture()
        assertFalse(authority.sessionEstablished)
    }
}
