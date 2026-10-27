package com.example.shelfplayer.playback

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ColdStartToggleGateTest {
    private fun gate() = ColdStartToggleGate()

    @Test fun `virtual toggle on first Bluetooth powered-on cold service is consumed on down and up`() {
        val g = gate()
        assertTrue(g.consume(900.milliseconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, false))
        assertTrue(g.consume(950.milliseconds, 85, KeyEvent.ACTION_UP, -1, true, true, false))
        assertFalse(g.consume(4.seconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, false))
    }

    @Test fun `only the first suspect toggle is consumed during a process cold start`() {
        val g = gate()
        assertTrue(g.consume(900.milliseconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, false))
        assertTrue(g.consume(950.milliseconds, 85, KeyEvent.ACTION_UP, -1, true, true, false))

        assertFalse(g.consume(1.seconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, false))
    }

    @Test fun `explicit headset PLAY stays valid from a cold service`() {
        assertFalse(gate().consume(800.milliseconds, 126, KeyEvent.ACTION_DOWN, -1, true, true, false))
    }

    @Test fun `car requests are never intercepted by cold headset guard`() {
        assertFalse(gate().consume(800.milliseconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, true))
    }

    @Test fun `normal warm and loaded session controls pass through`() {
        assertFalse(gate().consume(5.seconds, 85, KeyEvent.ACTION_DOWN, -1, true, true, false))
        assertFalse(gate().consume(500.milliseconds, 85, KeyEvent.ACTION_DOWN, -1, true, false, false))
    }

    @Test fun `no Bluetooth or attributable hardware key is not a cold Bluetooth trigger`() {
        assertFalse(gate().consume(900.milliseconds, 85, KeyEvent.ACTION_DOWN, -1, false, true, false))
        assertFalse(gate().consume(900.milliseconds, 85, KeyEvent.ACTION_DOWN, 42, true, true, false))
    }
}
