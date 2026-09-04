package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-002 — what the car's two output buttons mean, and what each press selects.
 *
 * The whole reason these are pure functions: what a button *does* is a decision about a list, and the part
 * that needs a car — that Android Auto draws the buttons at all — is what §2.11's device pass covers.
 */
class AudioOutputRolesTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val overEars = output("bluetooth:studio", "Studio 3")
    private val wired = output(id = "wired", name = "Wired headphones", kind = DeviceKind.Wired)
    private val speaker = output("speaker:phone", "Phone speaker", DeviceKind.Speaker)
    private val car = output("car", "Car audio", DeviceKind.Car)
    private val dock = output("other:dock", "Dock", DeviceKind.Other)

    /**
     * **The defect this change exists to close.** The phone's own speaker is not reachable from either
     * button.
     *
     * Not by a filter — by construction: the headset button steps over things a person wears and the car
     * button names the car, so there is no press that lands a book in the phone's speaker while somebody is
     * driving. Asserted on the two functions that decide a press rather than on a `filter` call, because a
     * filter is what a later edit drops.
     */
    @Test
    fun `no press can select the phone speaker`() {
        val outputs = listOf(speaker, buds, car)

        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = null))
        assertEquals(car.id, AudioOutputRoles.carTarget(outputs))
        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = speaker.id))
    }

    /** A car, a speaker and a dock are not things anybody is wearing. */
    @Test
    fun `only wearable outputs count as headsets`() {
        val outputs = listOf(wired, car, speaker, buds, dock)

        assertEquals(listOf(wired.id, buds.id), AudioOutputRoles.headsets(outputs).map(AudioOutput::id))
        assertTrue(DeviceKind.HearingAid.isHeadset)
        assertFalse(DeviceKind.Car.isHeadset)
        assertFalse(DeviceKind.Speaker.isHeadset)
        assertFalse(DeviceKind.Other.isHeadset)
    }

    /** *"Cycles the headset if more than one headset."* Two pairs, and the button wraps between them. */
    @Test
    fun `the headset button steps between headsets and wraps`() {
        val outputs = listOf(buds.copy(isActive = true), overEars, speaker)

        val second = AudioOutputRoles.nextHeadset(outputs, selectedId = null)
        assertEquals(overEars.id, second)
        assertEquals(
            buds.id,
            AudioOutputRoles.nextHeadset(listOf(buds, overEars.copy(isActive = true), speaker), null),
        )
    }

    /**
     * One pair of earbuds re-selects itself rather than doing nothing.
     *
     * A press that changes nothing is not a bug here: the platform may have declined the preference or the
     * route may have wandered, and re-asserting the one headset is what the listener meant by pressing a
     * button labelled with it.
     */
    @Test
    fun `a single headset re-selects itself`() {
        assertEquals(buds.id, AudioOutputRoles.nextHeadset(listOf(buds.copy(isActive = true)), null))
    }

    /**
     * From the car, the press means *put it in my ears* and lands on the first headset.
     *
     * Stepping from a position that is not on the headset list would be a guess, and the guess would be
     * wrong exactly when a driver is trying to hand the book to a passenger.
     */
    @Test
    fun `from somewhere that is not a headset the first headset wins`() {
        val outputs = listOf(car.copy(isActive = true), buds, overEars)

        assertEquals(buds.id, AudioOutputRoles.nextHeadset(outputs, selectedId = null))
    }

    /** Nothing to step to is `null`, never *Automatic*: the button would not have been published. */
    @Test
    fun `no headset is not automatic`() {
        assertNull(AudioOutputRoles.nextHeadset(listOf(car, speaker), selectedId = null))
        assertNull(AudioOutputRoles.nextHeadset(emptyList(), selectedId = null))
    }

    /**
     * A car that reports no audio bus of its own gets *Automatic*, which while it is connected **is** the car.
     *
     * Projected Android Auto reaches this app as a media controller and its audio link need not appear as
     * `TYPE_BUS` at all. Inventing a device id for it would be guessing; handing routing back to the
     * platform is the one thing that reliably puts the sound where the button says.
     */
    @Test
    fun `the car button falls back to automatic when no bus is reported`() {
        assertNull(AudioOutputRoles.carTarget(listOf(buds, speaker)))
        assertEquals(car.id, AudioOutputRoles.carTarget(listOf(buds, car, speaker)))
    }

    /** The headset button's label is the **route**, which since ADR-0027's amendment the app can read. */
    @Test
    fun `the headset label names where the audio actually is`() {
        val outputs = listOf(buds.copy(isActive = true), overEars)

        val state = AudioOutputRoles.buttons(outputs, selectedId = overEars.id, carConnected = false)

        assertEquals("Buds", state.headsetName)
    }

    /**
     * With no route to read — below API 33 — the choice stands in, and a choice that is not a headset
     * leaves the button generically labelled rather than naming the car.
     */
    @Test
    fun `without a route the label falls back to the choice, and only to a headset`() {
        val outputs = listOf(buds, car)

        assertEquals("Buds", AudioOutputRoles.buttons(outputs, buds.id, carConnected = false).headsetName)
        assertNull(AudioOutputRoles.buttons(outputs, car.id, carConnected = false).headsetName)
    }

    /**
     * A car bound to the media session publishes the car button even when it reports no audio device.
     *
     * This is the wireless-Android-Auto case, and it is the one where the button matters most: nothing in
     * the output list says "car", so without the controller fact the button would be missing from the only
     * screen it was asked for.
     */
    @Test
    fun `a bound car publishes the car button with no car device present`() {
        val state = AudioOutputRoles.buttons(listOf(buds), selectedId = null, carConnected = true)

        assertTrue(state.showCar)
        assertTrue(state.showHeadset)
    }

    /** No car anywhere and nothing wearable: neither button is published rather than two dead ones. */
    @Test
    fun `with nothing to act on neither button is published`() {
        val state = AudioOutputRoles.buttons(listOf(speaker), selectedId = null, carConnected = false)

        assertEquals(OutputButtons.None, state)
    }

    private fun output(id: String, name: String, kind: DeviceKind = DeviceKind.Bluetooth) =
        AudioOutput(id = id, displayName = name, kind = kind)
}
