package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** PD-009 refinement 2026-11-16: the car hands-free window is bounded, single-use and peer-scoped. */
class CarBluetoothArrivalWindowTest {
    private var now: Duration = Duration.ZERO
    private val window = CarBluetoothArrivalWindow { now }

    @Test
    fun `window is closed until a car hands-free link connects`() {
        assertNull(window.age())
        assertFalse(window.consume())
    }

    @Test
    fun `open window reports its age and expires at its lifetime`() {
        now = 5.seconds
        window.onCarHandsFreeConnected("car")
        now = 15.seconds
        assertEquals(10.seconds, window.age())
        now = 5.seconds + CarBluetoothArrivalWindow.LIFETIME
        assertNull(window.age())
        assertFalse(window.consume())
    }

    @Test
    fun `one window supports one inferred arrival`() {
        window.onCarHandsFreeConnected("car")
        now = 10.seconds
        assertTrue(window.consume())
        assertNull(window.age())
        assertFalse(window.consume())
    }

    @Test
    fun `a new connection opens a fresh window`() {
        window.onCarHandsFreeConnected("car")
        assertTrue(window.consume())
        now = 60.seconds
        window.onCarHandsFreeConnected("car")
        assertEquals(Duration.ZERO, window.age())
    }

    @Test
    fun `only the same car leaving closes the window`() {
        window.onCarHandsFreeConnected("car")
        window.onHandsFreeDisconnected("headset")
        window.onHandsFreeDisconnected(null)
        assertEquals(Duration.ZERO, window.age())
        window.onHandsFreeDisconnected("car")
        assertNull(window.age())
    }

    @Test
    fun `a clock that moved backwards cannot reopen the window`() {
        now = 10.seconds
        window.onCarHandsFreeConnected("car")
        now = 5.seconds
        assertNull(window.age())
    }
}
