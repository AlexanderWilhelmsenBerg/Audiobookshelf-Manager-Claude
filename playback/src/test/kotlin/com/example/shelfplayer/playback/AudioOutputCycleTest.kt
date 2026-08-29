package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC PLAY-002 — the car's output button steps, and this is the order it steps in.
 *
 * The whole reason the cycle is a pure function: what the button *does* is a decision about a list, and the
 * part that needs a car — that Android Auto draws the button at all — is the part a device test covers.
 */
class AudioOutputCycleTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val speaker = output("speaker:phone", "Phone speaker", DeviceKind.Speaker)
    private val car = output("car", "Car audio", DeviceKind.Car)

    /** *Automatic* first, then the platform's own order, then back. Every state is reachable from any other. */
    @Test
    fun `the cycle visits automatic and every output in turn`() {
        val outputs = listOf(buds, speaker)

        assertEquals("bluetooth:buds", AudioOutputCycle.next(outputs, null))
        assertEquals("speaker:phone", AudioOutputCycle.next(outputs, "bluetooth:buds"))
        assertNull(AudioOutputCycle.next(outputs, "speaker:phone"))
    }

    /**
     * A car on its own is the state the owner reported the tab missing in, and one press must still do
     * something a driver can understand: force the car, then hand routing back.
     */
    @Test
    fun `a single output still cycles against automatic`() {
        assertEquals("car", AudioOutputCycle.next(listOf(car), null))
        assertNull(AudioOutputCycle.next(listOf(car), "car"))
    }

    /** Nothing connected is nothing to step to. The button stays on *Automatic* rather than inventing one. */
    @Test
    fun `an empty list stays automatic`() {
        assertNull(AudioOutputCycle.next(emptyList(), null))
        assertNull(AudioOutputCycle.next(emptyList(), "bluetooth:gone"))
    }

    /**
     * A device that left between the press and the call returns *Automatic*, not a neighbour.
     *
     * The index it would have stepped from is gone, so any neighbour is an arbitrary device — and moving a
     * book's audio somewhere arbitrary is the failure this whole feature exists to avoid.
     */
    @Test
    fun `a selection that has disconnected falls back to automatic`() {
        assertNull(AudioOutputCycle.next(listOf(buds, speaker), "bluetooth:gone"))
    }

    /** The label is the **route**, which since ADR-0027's amendment is a fact the app can read. */
    @Test
    fun `the label names where the audio actually is`() {
        val outputs = listOf(buds.copy(isActive = true), speaker)

        assertEquals(buds.id, AudioOutputCycle.current(outputs, selectedId = "speaker:phone")?.id)
    }

    /**
     * With no route to read — below API 33, or a route this app does not recognise — the choice stands in.
     *
     * That is the pre-amendment behaviour, kept deliberately for the platforms that still have it: minSdk is
     * 26, so Android 8 to 12 never get an answer from `getAudioDevicesForAttributes`.
     */
    @Test
    fun `without a route the label falls back to the choice`() {
        val outputs = listOf(buds, speaker)

        assertEquals(speaker.id, AudioOutputCycle.current(outputs, selectedId = "speaker:phone")?.id)
        assertNull(AudioOutputCycle.current(outputs, selectedId = null))
    }

    private fun output(id: String, name: String, kind: DeviceKind = DeviceKind.Bluetooth) =
        AudioOutput(id = id, displayName = name, kind = kind)
}
