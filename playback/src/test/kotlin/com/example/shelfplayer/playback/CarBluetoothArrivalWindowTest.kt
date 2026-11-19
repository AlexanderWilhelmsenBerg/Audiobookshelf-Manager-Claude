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
    fun `one take-back per car link, independent of the arrival inference`() {
        assertFalse(window.claimTakeBack(), "no car link, no take-back")
        window.onCarHandsFreeConnected("car")
        assertTrue(window.consume())

        assertTrue(window.claimTakeBack())
        assertFalse(window.claimTakeBack())

        now = 40.seconds
        window.onCarHandsFreeConnected("car")
        now = 40.seconds + CarBluetoothArrivalWindow.LIFETIME
        assertFalse(window.claimTakeBack(), "an expired window cannot be claimed")
    }

    @Test
    fun `deferred take-back uses the original link and spends only on claim`() {
        window.onCarHandsFreeConnected("car")
        val deferred = requireNotNull(window.takeBackScope())
        assertTrue(window.consume(), "inferred Play and system transfer have separate allowances")
        now = 29.seconds
        assertTrue(deferred.isCurrent())
        assertTrue(deferred.claim())
        assertFalse(deferred.claim())
        assertNull(window.takeBackScope())
        now = 30.seconds
        assertFalse(deferred.isCurrent())
    }

    @Test
    fun `old take-back scope cannot claim a replacement connection`() {
        window.onCarHandsFreeConnected("car")
        val old = requireNotNull(window.takeBackScope())
        window.close()
        window.onCarHandsFreeConnected("car")
        assertFalse(old.isCurrent())
        assertFalse(old.claim())
        assertTrue(requireNotNull(window.takeBackScope()).claim())
    }

    @Test
    fun `a clock that moved backwards cannot reopen the window`() {
        now = 10.seconds
        window.onCarHandsFreeConnected("car")
        now = 5.seconds
        assertNull(window.age())
    }
}
