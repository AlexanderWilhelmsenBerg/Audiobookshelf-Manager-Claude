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

    /*
     * PRODUCT_SPEC PLAY-002 — which button is lit, which is the only way the car's player can show the
     * current output. The device report was that it showed nowhere at all.
     */

    @Test
    fun `the headset button is lit while the book is coming out of a headset`() {
        val active = buds.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(active), selectedId = active.id, carConnected = true)

        assertTrue(state.onHeadset)
        assertFalse(state.onCar)
    }

    /** The car-arrival hold's own state: a car is bound, and the book is still in the listener's ears. */
    @Test
    fun `a held headset keeps the headset lit even with a car connected`() {
        val held = wired.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(held, car), selectedId = held.id, carConnected = true)

        assertTrue(state.onHeadset)
        assertFalse(state.onCar)
    }

    /**
     * The defect a review found in the complement: `onCar` was `!onHeadset`, so the phone speaker carrying
     * the audio lit the car. `AudioOutputRouter.select` accepts the speaker so the phone's own chooser
     * works, which is what makes this reachable rather than theoretical.
     */
    @Test
    fun `a speaker carrying the audio lights neither action`() {
        val active = speaker.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(active, buds), selectedId = active.id, carConnected = true)

        assertFalse(state.onHeadset)
        assertFalse(state.onCar)
    }

    /** A route the platform has not reported is not evidence for either glyph. */
    @Test
    fun `an unknown route lights neither action`() {
        val state = AudioOutputRoles.buttons(listOf(buds, car), selectedId = null, carConnected = true)

        assertFalse(state.onHeadset)
        assertFalse(state.onCar)
    }

    /**
     * The dashboard case. An ambiguous A2DP route nobody selected, with a car bound, is not a headset —
     * ADR-0029 §4 — and it is not a speaker either, so the car is what lights up.
     */
    @Test
    fun `an unselected ambiguous route with a car bound lights the car`() {
        val dashboard = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(dashboard), selectedId = null, carConnected = true)

        assertFalse(state.onHeadset)
        assertTrue(state.onCar)
    }

    /** The other half of the evidence: a `TYPE_BUS` route is the car's own bus and needs no controller. */
    @Test
    fun `a car audio bus lights the car`() {
        val bus = car.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(bus), selectedId = bus.id, carConnected = false)

        assertFalse(state.onHeadset)
        assertTrue(state.onCar)
    }

    /**
     * The second narrowing of the same mistake. Excluding speakers and unknown routes was not enough:
     * `OutputDevices.roleOf` sends USB devices, USB accessories, docks and HDMI to `Other` through its
     * `else`, and every one of them was lighting a confident car glyph over something that was not the car.
     */
    @Test
    fun `a dock carrying the audio lights neither action`() {
        val active = dock.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(active, buds), selectedId = active.id, carConnected = true)

        assertFalse(state.onHeadset)
        assertFalse(state.onCar)
    }

    /** And a dock is still not a car when a head unit is bound and has its own bus alongside it. */
    @Test
    fun `a dock does not borrow the car glyph from a connected car`() {
        val active = dock.copy(isActive = true)
        val state = AudioOutputRoles.buttons(listOf(active, car), selectedId = active.id, carConnected = true)

        assertFalse(state.onCar)
        assertTrue(state.showCar)
    }

    /*
     * PLAY-002 — "if play button comes from headset, start in that headset". The pressing device is not
     * knowable (AVRCP discards it before the framework sees it), so the policy reads the route and can only
     * ever retract BookWave's own disagreement with it. These pin that it never moves audio on its own.
     */

    @Test
    fun `a book starting on the routed headset corrects a stale selection`() {
        val active = buds.copy(isActive = true)

        val target = AudioOutputRoles.startTarget(
            outputs = listOf(active, overEars),
            selectedId = overEars.id,
            carConnected = false,
        )

        assertEquals(active.id, target)
    }

    /** Automatic is not a disagreement — the platform is already in charge and must stay there. */
    @Test
    fun `an automatic selection is left alone`() {
        val active = buds.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(active), selectedId = null, carConnected = false))
    }

    @Test
    fun `a selection that already agrees is left alone`() {
        val active = buds.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(active), selectedId = active.id, carConnected = false))
    }

    /** The speaker is never a start target, which is what keeps a book out of the room. */
    @Test
    fun `a book starting on the phone speaker moves nothing`() {
        val active = speaker.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(active, buds), selectedId = buds.id, carConnected = false))
    }

    @Test
    fun `a book starting on the car bus moves nothing`() {
        val active = car.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(active, buds), selectedId = buds.id, carConnected = true))
    }

    @Test
    fun `a dock is not a headset to start in`() {
        val active = dock.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(active, buds), selectedId = buds.id, carConnected = false))
    }

    /**
     * The case that would be a real defect: an unselected ambiguous A2DP route with a car bound is the
     * dashboard, and pinning it as "the headset" would hand the book to the car and call it earbuds.
     */
    @Test
    fun `a projected car's dashboard is never mistaken for the headset to start in`() {
        val dashboard = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)

        assertNull(
            AudioOutputRoles.startTarget(
                outputs = listOf(dashboard, buds),
                selectedId = buds.id,
                carConnected = true,
            ),
        )
    }

    /**
     * Below API 33 `isActive` degenerates to "the output this app chose", so the disagreement test can
     * never fire and the policy is inert. That is deliberate: those releases report no route at all, and
     * acting anyway would move a book to a device nobody asked for.
     */
    @Test
    fun `the policy is inert when the only active output is the chosen one`() {
        val chosen = buds.copy(isActive = true)

        assertNull(AudioOutputRoles.startTarget(listOf(chosen, overEars), chosen.id, carConnected = false))
    }

    @Test
    fun `nothing routed means nothing to do`() {
        assertNull(AudioOutputRoles.startTarget(listOf(buds, overEars), selectedId = buds.id, carConnected = false))
    }

    /*
     * `current()` no longer lets a reported speaker mask another active route — one of the ways a device run
     * found both output glyphs dark while a car was carrying the book.
     */

    @Test
    fun `an active speaker does not mask an active headset`() {
        val state = AudioOutputRoles.buttons(
            outputs = listOf(speaker.copy(isActive = true), buds.copy(isActive = true)),
            selectedId = null,
            carConnected = false,
        )

        assertTrue(state.onHeadset)
    }

    @Test
    fun `an active speaker does not mask the dashboard`() {
        val dashboard = output("bluetooth:dashboard", "Dashboard").copy(isActive = true)
        val state = AudioOutputRoles.buttons(
            outputs = listOf(speaker.copy(isActive = true), dashboard),
            selectedId = null,
            carConnected = true,
        )

        assertTrue(state.onCar)
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
