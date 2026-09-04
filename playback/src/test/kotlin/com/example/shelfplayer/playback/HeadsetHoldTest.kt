package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — *Keep sound in the headset*, and the four times it must do nothing.
 *
 * The interesting cases are all negative. Holding a route is the app overriding a decision the platform
 * would otherwise make, so every condition has to be true before it happens — and the one positive test
 * below is the only shape in which it may.
 */
class HeadsetHoldTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val car = output("car", "Car audio", DeviceKind.Car)
    private val speaker = output("speaker:phone", "Phone speaker", DeviceKind.Speaker)

    /**
     * **The feature.** The book was in the earbuds, the car arrives and takes the route, and the remembered
     * headset is still what the hold names.
     *
     * The second `observe` is the race this class exists for: by the time a car binds, the platform has
     * usually already moved the route, so nothing readable at that moment still says "headset".
     */
    @Test
    fun `a headset in use survives the car taking the route`() {
        val hold = HeadsetHold()

        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)
        hold.observe(listOf(buds, car.copy(isActive = true)), selectedId = null)

        assertEquals(buds.id, hold.holdOnCarArrival(listOf(buds, car), enabled = true))
    }

    /** Off is off. Nothing is pinned and the platform keeps every routing decision it had. */
    @Test
    fun `the setting off holds nothing`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        assertNull(hold.holdOnCarArrival(listOf(buds, car), enabled = false))
    }

    /**
     * A headset that is connected but not playing is earbuds in a pocket.
     *
     * Moving audio *into* one because a car door opened is a worse failure than the one this feature
     * prevents, so a headset only ever becomes the held device by being the live route first.
     */
    @Test
    fun `a headset that was never in use is not held`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds, speaker.copy(isActive = true)), selectedId = null)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car), enabled = true))
    }

    /** A headset that disconnects is forgotten, so a reconnecting namesake does not inherit the hold. */
    @Test
    fun `a headset that disconnects is forgotten`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        hold.observe(listOf(speaker.copy(isActive = true)), selectedId = null)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car), enabled = true))
    }

    /** Gone between the last list and the car's arrival: nothing to pin, and no invented neighbour. */
    @Test
    fun `a headset missing at car time is not pinned`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        assertNull(hold.holdOnCarArrival(listOf(car), enabled = true))
    }

    /**
     * Below API 33 there is no route to read, so the explicit choice stands in — the same fallback the
     * label uses, and the same one ADR-0027's amendment kept for Android 8 to 12.
     */
    @Test
    fun `without a readable route an explicit choice is remembered`() {
        val hold = HeadsetHold()

        hold.observe(listOf(buds, speaker), selectedId = buds.id)

        assertEquals(buds.id, hold.remembered)
    }

    private fun output(id: String, name: String, kind: DeviceKind = DeviceKind.Bluetooth) =
        AudioOutput(id = id, displayName = name, kind = kind)
}
