package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRODUCT_SPEC PLAY-002 — what the Android Auto Car and Headset actions mean. */
class AudioOutputRolesTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val overEars = output("bluetooth:studio", "Studio 3")
    private val wired = output(
        id = "wired",
        name = "Wired headphones",
        kind = DeviceKind.Wired,
        role = AudioOutputRole.Headset,
    )
    private val speaker = output(
        "speaker:phone",
        "Phone speaker",
        DeviceKind.Speaker,
        AudioOutputRole.Speaker,
    )
    private val car = output("car", "Car audio", DeviceKind.Car, AudioOutputRole.Car)
    private val dock = output("other:dock", "Dock", DeviceKind.Other, AudioOutputRole.Other)

    @Test
    fun `no routing action can select the phone speaker`() {
        val outputs = listOf(speaker, buds, car)

        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = null))
        assertNull(AudioOutputRoles.carTarget(outputs))
        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = speaker.id))
    }

    @Test
    fun `transport and role are separate facts`() {
        assertEquals(DeviceKind.Bluetooth, buds.kind)
        assertEquals(AudioOutputRole.Ambiguous, buds.role)
        assertFalse(buds.isHeadset)
        assertTrue(buds.isHeadsetCandidate)
        assertTrue(wired.isHeadset)
        assertFalse(car.isHeadsetCandidate)
        assertFalse(speaker.isHeadsetCandidate)
        assertFalse(dock.isHeadsetCandidate)
    }

    @Test
    fun `the headset button steps between candidates and wraps`() {
        val outputs = listOf(buds.copy(isActive = true), overEars, speaker)

        assertEquals(overEars.id, AudioOutputRoles.nextHeadset(outputs, selectedId = buds.id))
        assertEquals(
            buds.id,
            AudioOutputRoles.nextHeadset(
                listOf(buds, overEars.copy(isActive = true), speaker),
                selectedId = overEars.id,
            ),
        )
    }

    @Test
    fun `a single explicit headset reselects itself`() {
        assertEquals(
            buds.id,
            AudioOutputRoles.nextHeadset(listOf(buds.copy(isActive = true)), selectedId = buds.id),
        )
    }

    @Test
    fun `an active ambiguous car route is skipped when another candidate exists`() {
        val projectedCar = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)
        val outputs = listOf(projectedCar, buds)

        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = null))
    }

    @Test
    fun `no headset candidate is not automatic`() {
        assertNull(AudioOutputRoles.nextHeadset(listOf(car, speaker), selectedId = null))
        assertNull(AudioOutputRoles.nextHeadset(emptyList(), selectedId = null))
    }

    @Test
    fun `car always releases the preferred route`() {
        assertNull(AudioOutputRoles.carTarget(listOf(buds, speaker)))
        assertNull(AudioOutputRoles.carTarget(listOf(buds, car, speaker)))
    }

    @Test
    fun `the headset label names a confirmed headset route`() {
        val definite = wired.copy(displayName = "USB headphones", isActive = true)
        val state = AudioOutputRoles.buttons(listOf(definite, buds), selectedId = null, carConnected = false)

        assertEquals("USB headphones", state.headsetName)
    }

    @Test
    fun `an explicitly selected ambiguous headset can name the active route`() {
        val activeBuds = buds.copy(isActive = true)

        val state = AudioOutputRoles.buttons(listOf(activeBuds), buds.id, carConnected = true)

        assertEquals("Buds", state.headsetName)
        assertTrue(state.showHeadset)
    }

    @Test
    fun `an active ambiguous dashboard is not labelled as a headset in the car`() {
        val dashboard = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(dashboard), selectedId = null, carConnected = true)

        assertNull(state.headsetName)
        assertFalse(state.showHeadset)
        assertTrue(state.showCar)
    }

    @Test
    fun `a bound car publishes car and an inactive a2dp headset remains available`() {
        val dashboard = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(dashboard, buds), selectedId = null, carConnected = true)

        assertTrue(state.showCar)
        assertTrue(state.showHeadset)
    }

    @Test
    fun `with nothing to act on neither button is published`() {
        val state = AudioOutputRoles.buttons(listOf(speaker), selectedId = null, carConnected = false)
        assertEquals(OutputButtons.None, state)
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
