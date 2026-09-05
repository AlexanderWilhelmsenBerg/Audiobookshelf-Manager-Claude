package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PRODUCT_SPEC PLAY-002 — preserve what was already in the listener's ears when a car arrives. */
class HeadsetHoldTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val dashboard = output("bluetooth:dashboard", "Dashboard")
    private val car = output("car", "Car audio", DeviceKind.Car, AudioOutputRole.Car)
    private val speaker = output("speaker:phone", "Phone speaker", DeviceKind.Speaker, AudioOutputRole.Speaker)

    @Test
    fun `an a2dp headset in use survives a projected car taking the route`() {
        val hold = HeadsetHold()

        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)
        hold.observe(listOf(buds, dashboard.copy(isActive = true)), selectedId = null)

        assertEquals(buds.id, hold.remembered)
        assertEquals(buds.id, hold.holdOnCarArrival(listOf(buds, dashboard)))
    }

    @Test
    fun `legacy setting value no longer disables preservation`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        assertEquals(buds.id, hold.holdOnCarArrival(listOf(buds, car), enabled = false))
    }

    @Test
    fun `a headset that was never in use is not held`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds, speaker.copy(isActive = true)), selectedId = null)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `a headset that disconnects is forgotten`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        hold.observe(listOf(speaker.copy(isActive = true)), selectedId = null)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `a headset missing at car time is not pinned`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null)

        assertNull(hold.holdOnCarArrival(listOf(car)))
    }

    @Test
    fun `without a readable route an explicit a2dp choice is remembered`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds, speaker), selectedId = buds.id)

        assertEquals(buds.id, hold.remembered)
    }

    @Test
    fun `a definite headset can replace an ambiguous remembered route`() {
        val wired = output("wired", "Wired", DeviceKind.Wired, AudioOutputRole.Headset)
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), null)

        hold.observe(listOf(buds, wired.copy(isActive = true)), null)

        assertEquals(wired.id, hold.remembered)
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
