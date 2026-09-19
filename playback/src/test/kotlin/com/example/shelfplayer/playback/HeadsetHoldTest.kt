package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** PRODUCT_SPEC PLAY-002 / ROUTE-002 — route-heard ownership for the currently loaded book. */
class RouteHeardOwnershipTest {

    private val budsA = output("bluetooth:buds-a", "Buds A")
    private val budsB = output("bluetooth:buds-b", "Buds B")
    private val dashboard = output("bluetooth:dashboard", "Dashboard")
    private val wired = output(
        id = "wired",
        name = "Wired headphones",
        kind = DeviceKind.Wired,
        role = AudioOutputRole.Headset,
    )
    private val speaker = output(
        id = "speaker:phone",
        name = "Phone speaker",
        kind = DeviceKind.Speaker,
        role = AudioOutputRole.Speaker,
    )
    private val car = output("car", "Car audio", DeviceKind.Car, AudioOutputRole.Car)

    @Test
    fun `a merely connected headset is never remembered as heard`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)

        owner.onOutputsChanged(listOf(budsA.copy(isActive = true)), isPlaying = false)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `a headset carrying playback is remembered for a car arrival`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)

        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(budsA.id, owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `an ambiguous dashboard cannot erase the headset heard before the car arrived`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.onOutputsChanged(
            outputs = listOf(budsA, dashboard.copy(isActive = true)),
            isPlaying = true,
        )

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(budsA.id, owner.headsetForCar(listOf(budsA, dashboard)))
    }

    @Test
    fun `moving playback explicitly to the phone speaker supersedes an older headset`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), speaker))

        owner.onExplicitSelection(
            outputId = speaker.id,
            outputs = listOf(budsA.copy(isActive = true), speaker),
            isPlaying = true,
        )

        assertEquals(speaker.id, owner.heardRoute?.outputId)
        assertNull(owner.headsetForCar(listOf(budsA, speaker, car)))
    }

    @Test
    fun `another definite route heard later supersedes an older headset`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), wired))

        owner.onOutputsChanged(
            outputs = listOf(budsA, wired.copy(isActive = true)),
            isPlaying = true,
        )

        assertEquals(wired.id, owner.heardRoute?.outputId)
        assertEquals(AudioOutputRole.Headset, owner.heardRoute?.role)
    }

    @Test
    fun `explicit headset A outranks framework policy reporting candidate B`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        val outputs = listOf(budsA, budsB.copy(isActive = true))

        owner.onExplicitSelection(budsA.id, outputs, isPlaying = true)
        owner.onPlaybackObserved(outputs)

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(
            RouteHeardEvidence.ListenerSelectionWhilePlaying,
            owner.heardRoute?.evidence,
        )
    }

    @Test
    fun `multiple classic A2DP candidates never make enumeration order ownership`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)

        owner.onPlaybackObserved(
            listOf(
                budsA.copy(isActive = true),
                budsB.copy(isActive = true),
            ),
        )

        assertNull(owner.heardRoute)
    }

    @Test
    fun `framework enumeration cannot replace an explicit A2DP listener choice`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        val first = listOf(budsA.copy(isActive = true), budsB.copy(isActive = true))
        owner.onExplicitSelection(budsA.id, first, isPlaying = true)

        owner.onOutputsChanged(
            outputs = listOf(budsB.copy(isActive = true), budsA.copy(isActive = true)),
            isPlaying = true,
        )

        assertEquals(budsA.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `classic A2DP remains ambiguous when it becomes route evidence`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)

        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        assertEquals(AudioOutputRole.Ambiguous, owner.heardRoute?.role)
    }

    @Test
    fun `Car then immediate Headset leaves the newer headset intent in ownership`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), budsB))

        owner.onExplicitSelection(
            outputId = null,
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
        )
        owner.onExplicitSelection(
            outputId = budsB.id,
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
        )
        owner.onOutputsChanged(
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
        )

        assertEquals(budsB.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `Automatic does not immediately re-adopt the headset it just released`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.onExplicitSelection(
            outputId = null,
            outputs = listOf(budsA.copy(isActive = true)),
            isPlaying = true,
        )
        owner.onOutputsChanged(listOf(budsA.copy(isActive = true)), isPlaying = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `a released route can be owned again after Automatic actually moves elsewhere`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), speaker))
        owner.onExplicitSelection(null, listOf(budsA.copy(isActive = true), speaker), isPlaying = true)

        owner.onOutputsChanged(listOf(budsA, speaker.copy(isActive = true)), isPlaying = true)
        owner.onOutputsChanged(listOf(budsA.copy(isActive = true), speaker), isPlaying = true)

        assertEquals(budsA.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `a disconnected remembered headset is forgotten`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.onOutputsChanged(emptyList(), isPlaying = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(car)))
    }

    @Test
    fun `route evidence is bound to the loaded book generation`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))
        val firstGeneration = owner.heardRoute?.generation

        owner.onBookChanged(hasBook = true)

        assertNull(owner.heardRoute)
        owner.onPlaybackObserved(listOf(wired.copy(isActive = true)))
        assertNotEquals(firstGeneration, owner.heardRoute?.generation)
        assertEquals(wired.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `old book route evidence cannot influence the next book`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))
        assertEquals(budsA.id, owner.headsetForCar(listOf(budsA, car)))

        owner.onBookChanged(hasBook = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `emptying the queue invalidates route evidence`() {
        val owner = RouteHeardOwnership()
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.onQueueEmptied()

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
