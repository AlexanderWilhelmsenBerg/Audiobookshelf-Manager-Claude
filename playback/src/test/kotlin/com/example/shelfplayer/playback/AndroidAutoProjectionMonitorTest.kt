package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAutoProjectionMonitorTest {
    @Test
    fun `public car connection states map without treating unknown values as departure`() {
        assertEquals(
            AndroidAutoProjectionMonitor.State.NotConnected,
            AndroidAutoProjectionMonitor.stateOf(0),
        )
        assertEquals(AndroidAutoProjectionMonitor.State.Native, AndroidAutoProjectionMonitor.stateOf(1))
        assertEquals(AndroidAutoProjectionMonitor.State.Projection, AndroidAutoProjectionMonitor.stateOf(2))
        assertEquals(AndroidAutoProjectionMonitor.State.Unknown, AndroidAutoProjectionMonitor.stateOf(99))
    }

    @Test
    fun `only native and projection states count as a positive car connection`() {
        assertFalse(AndroidAutoProjectionMonitor.State.Unknown.carConnected)
        assertFalse(AndroidAutoProjectionMonitor.State.NotConnected.carConnected)
        assertTrue(AndroidAutoProjectionMonitor.State.Native.carConnected)
        assertTrue(AndroidAutoProjectionMonitor.State.Projection.carConnected)
    }

    @Test
    fun `first projection observation is marked initial rather than a physical edge`() {
        val initial = AndroidAutoProjectionMonitor.Update(
            previous = null,
            current = AndroidAutoProjectionMonitor.State.Projection,
        )
        val later = AndroidAutoProjectionMonitor.Update(
            previous = AndroidAutoProjectionMonitor.State.NotConnected,
            current = AndroidAutoProjectionMonitor.State.Projection,
        )

        assertTrue(initial.initial)
        assertFalse(later.initial)
    }
}
