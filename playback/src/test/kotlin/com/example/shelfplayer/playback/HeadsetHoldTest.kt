package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** PRODUCT_SPEC PLAY-002 — preserve what was already in the listener's ears when a car arrives. */
class HeadsetHoldTest {

    private val buds = output("bluetooth:buds", "Buds")
    private val dashboard = output("bluetooth:dashboard", "Dashboard")
    private val car = output("car", "Car audio", DeviceKind.Car, AudioOutputRole.Car)
    private val speaker = output("speaker:phone", "Phone speaker", DeviceKind.Speaker, AudioOutputRole.Speaker)

    /**
     * The case the class exists to prevent, reached from the other direction.
     *
     * On API 33+ an idle BookWave still sees the platform's media route, so connected earbuds look active
     * with no book loaded. Remembering them means a car arriving pins the player to a headset in a pocket,
     * and Android Auto then auto-plays into it.
     */
    /**
     * An explicit *Car* press has to outlive the memory.
     *
     * Without [HeadsetHold.forget] the ambiguous-route guard keeps the earbuds across the dashboard becoming
     * active — that guard is the whole point of the class — and the next car binding reasserts them, undoing
     * the press.
     *
     * What is asserted is that the **earbuds** are not reasserted, not that nothing is. The dashboard is
     * classic A2DP and therefore a headset candidate this app cannot tell from earbuds (`docs/risks.md`
     * R-103), so it is re-adopted on the next observation — and re-pinning the route to the dashboard is
     * where the listener just asked to be, which makes it harmless rather than a second defect.
     */
    @Test
    fun `an explicit car choice is not undone by the remembered headset`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)
        assertEquals(buds.id, hold.remembered)

        hold.releaseToCar(listOf(buds.copy(isActive = true)), selectedId = null)
        hold.observe(listOf(buds, dashboard.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertNotEquals(buds.id, hold.holdOnCarArrival(listOf(buds, dashboard)))
    }

    /**
     * The press has to outlive the emission it causes.
     *
     * `select(null)` publishes a selection change while the platform still reports the headset as the active
     * route, so the very next observation sees exactly the state that was just given up. Clearing the memory
     * is not enough — it is re-remembered before Automatic routing settles.
     */
    @Test
    fun `the released headset is refused while it is still the active route`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = buds.id, hasMedia = true)

        hold.releaseToCar(listOf(buds.copy(isActive = true)), selectedId = buds.id)
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    /**
     * The refusal is not permanent — it lasts only while the given-up headset is still there.
     *
     * Disconnecting is the unambiguous end of the release. (The other lift, the route moving to a *different*
     * headset, is deliberately not asserted here: the A2DP guard then keeps that second headset, so the
     * observable answer is about the guard rather than about the release.)
     */
    @Test
    fun `a released headset can be held again once it has disconnected and returned`() {
        val hold = HeadsetHold()
        hold.releaseToCar(listOf(buds.copy(isActive = true)), selectedId = buds.id)

        hold.observe(listOf(car.copy(isActive = true)), selectedId = null, hasMedia = true)
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertEquals(buds.id, hold.remembered)
    }

    @Test
    fun `earbuds connected to an idle app are not remembered`() {
        val hold = HeadsetHold()

        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = false)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    /** Emptying the queue ends the hold: there is no longer a book that was coming out of anything. */
    @Test
    fun `a remembered headset is dropped when the book is unloaded`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)
        assertEquals(buds.id, hold.remembered)

        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = false)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `an a2dp headset in use survives a projected car taking the route`() {
        val hold = HeadsetHold()

        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)
        hold.observe(listOf(buds, dashboard.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertEquals(buds.id, hold.remembered)
        assertEquals(buds.id, hold.holdOnCarArrival(listOf(buds, dashboard)))
    }

    @Test
    fun `legacy setting value no longer disables preservation`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertEquals(buds.id, hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `a headset that was never in use is not held`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds, speaker.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `a headset that disconnects is forgotten`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)

        hold.observe(listOf(speaker.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertNull(hold.remembered)
        assertNull(hold.holdOnCarArrival(listOf(buds, car)))
    }

    @Test
    fun `a headset missing at car time is not pinned`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), selectedId = null, hasMedia = true)

        assertNull(hold.holdOnCarArrival(listOf(car)))
    }

    @Test
    fun `without a readable route an explicit a2dp choice is remembered`() {
        val hold = HeadsetHold()
        hold.observe(listOf(buds, speaker), selectedId = buds.id, hasMedia = true)

        assertEquals(buds.id, hold.remembered)
    }

    @Test
    fun `a definite headset can replace an ambiguous remembered route`() {
        val wired = output("wired", "Wired", DeviceKind.Wired, AudioOutputRole.Headset)
        val hold = HeadsetHold()
        hold.observe(listOf(buds.copy(isActive = true)), null, hasMedia = true)

        hold.observe(listOf(buds, wired.copy(isActive = true)), null, hasMedia = true)

        assertEquals(wired.id, hold.remembered)
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
