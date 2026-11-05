package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — route-heard ownership for the currently loaded book.
 *
 * The historical filename predates issue #11; this class exercises [RouteHeardOwnership], not a HeadsetHold.
 */
class HeadsetHoldTest {
    @Test
    fun `explicit Car intent excludes headset recovery until a newer output choice`() {
        val intent = OutputIntentOwner()
        val owner = RouteHeardOwnership(intent).apply { onBookChanged(true) }
        val active = listOf(budsA.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack))
        owner.onPlaybackObserved(active)
        val car = intent.select(null, OutputIntentOwner.Destination.Car)
        owner.onExplicitSelection(car, active, isAudible = true)
        owner.onOutputsChanged(active, isPlaying = true)

        assertEquals(budsA.id, owner.heardRoute?.outputId, "actual evidence remains truthful")
        assertNull(owner.headsetForCar(active), "actual output is not permission to override explicit Car intent")
        assertNull(owner.measuredPlayingHeadsetForContinuity(active))

        val headset = intent.select(budsA.id, OutputIntentOwner.Destination.Output)
        owner.onExplicitSelection(headset, active, isAudible = true)
        assertEquals(budsA.id, owner.headsetForCar(active))
    }

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
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)

        owner.onOutputsChanged(listOf(budsA.copy(isActive = true)), isPlaying = false)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `a headset carrying playback is remembered for a car arrival`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)

        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(budsA.id, owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `an ambiguous dashboard cannot erase the headset heard before the car arrived`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
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
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), speaker))

        owner.explicitSelection(
            outputId = speaker.id,
            outputs = listOf(budsA.copy(isActive = true), speaker),
            isPlaying = true,
        )

        assertEquals(speaker.id, owner.heardRoute?.outputId)
        assertNull(owner.headsetForCar(listOf(budsA, speaker, car)))
    }

    @Test
    fun `another definite route heard later supersedes an older headset`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
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
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        val outputs = listOf(budsA, budsB.copy(isActive = true))

        owner.explicitSelection(budsA.id, outputs, isPlaying = true)
        owner.onPlaybackObserved(outputs)

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(
            RouteHeardEvidence.ListenerSelectionWhilePlaying,
            owner.heardRoute?.evidence,
        )
    }

    @Test
    fun `route ownership preserves each activity source without changing the headset candidate`() {
        val cases = mapOf(
            AudioOutputActivitySource.PlayingAudioTrack to RouteHeardEvidence.ActualAudioTrackWhilePlaying,
            AudioOutputActivitySource.FrameworkMediaPolicy to RouteHeardEvidence.FrameworkPolicyWhilePlaying,
            AudioOutputActivitySource.SelectedPreference to RouteHeardEvidence.SelectedPreferenceWhilePlaying,
            AudioOutputActivitySource.Unmeasured to RouteHeardEvidence.UnmeasuredActivityWhilePlaying,
        )
        for ((source, evidence) in cases) {
            val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
            val outputs = listOf(budsA.copy(isActive = true, activitySource = source))

            owner.onPlaybackObserved(outputs)

            assertEquals(evidence, owner.heardRoute?.evidence)
            assertEquals(budsA.id, owner.headsetForCar(outputs))
        }
    }

    @Test
    fun `only one current generation measured playing endpoint can arm transient continuity`() {
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
        val exact = budsA.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack)
        owner.onPlaybackObserved(listOf(exact))

        assertEquals(budsA.id, owner.measuredPlayingHeadsetForContinuity(listOf(exact)))
        assertEquals(budsA.id, owner.measuredPlayingHeadsetForContinuity(listOf(exact, speaker)))
        assertNull(
            owner.measuredPlayingHeadsetForContinuity(
                listOf(
                    exact,
                    speaker.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack),
                ),
            ),
        )
        assertNull(
            owner.measuredPlayingHeadsetForContinuity(
                listOf(
                    exact,
                    budsB.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack),
                ),
            ),
        )

        owner.onBookChanged(hasBook = true)
        assertNull(owner.measuredPlayingHeadsetForContinuity(listOf(exact)))
    }

    @Test
    fun `explicit Automatic release excludes its old route from transient protection`() {
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
        val exact = budsA.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack)
        owner.onPlaybackObserved(listOf(exact))
        owner.explicitSelection(outputId = null, outputs = listOf(exact), isPlaying = true)

        assertNull(owner.measuredPlayingHeadsetForContinuity(listOf(exact)))

        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(exact))
        assertEquals(budsA.id, owner.measuredPlayingHeadsetForContinuity(listOf(exact)))
    }

    @Test
    fun `a measured competing route cannot rewrite an explicit headset choice as physical proof`() {
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
        val outputs = listOf(
            budsA,
            budsB.copy(isActive = true, activitySource = AudioOutputActivitySource.PlayingAudioTrack),
        )
        owner.explicitSelection(budsA.id, outputs, isPlaying = true)

        owner.onOutputsChanged(outputs, isPlaying = true)

        assertEquals(budsA.id, owner.heardRoute?.outputId)
        assertEquals(RouteHeardEvidence.ListenerSelectionWhilePlaying, owner.heardRoute?.evidence)
    }

    @Test
    fun `multiple classic A2DP candidates never make enumeration order ownership`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
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
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        val first = listOf(budsA.copy(isActive = true), budsB.copy(isActive = true))
        owner.explicitSelection(budsA.id, first, isPlaying = true)

        owner.onOutputsChanged(
            outputs = listOf(budsB.copy(isActive = true), budsA.copy(isActive = true)),
            isPlaying = true,
        )

        assertEquals(budsA.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `classic A2DP remains ambiguous when it becomes route evidence`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)

        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        assertEquals(AudioOutputRole.Ambiguous, owner.heardRoute?.role)
    }

    @Test
    fun `Car then immediate Headset leaves the newer headset intent in ownership`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), budsB))

        owner.explicitSelection(
            outputId = null,
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
            selectionSequence = 1,
        )
        owner.explicitSelection(
            outputId = budsB.id,
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
            selectionSequence = 2,
        )
        // A late collector replay of the older Car/Automatic event must not beat the newer Headset press.
        owner.explicitSelection(
            outputId = null,
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
            selectionSequence = 1,
        )
        owner.onOutputsChanged(
            outputs = listOf(budsA.copy(isActive = true), budsB),
            isPlaying = true,
        )

        assertEquals(budsB.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `Automatic does not immediately re-adopt the headset it just released`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.explicitSelection(
            outputId = null,
            outputs = listOf(budsA.copy(isActive = true)),
            isPlaying = true,
        )
        owner.onOutputsChanged(listOf(budsA.copy(isActive = true)), isPlaying = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(budsA, car)))
    }

    @Test
    fun `replayed Automatic and an unknown route cannot resurrect the released headset`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        val active = listOf(budsA.copy(isActive = true))
        owner.onPlaybackObserved(active)

        owner.explicitSelection(null, active, isPlaying = true, selectionSequence = 1)
        owner.explicitSelection(null, active, isPlaying = true, selectionSequence = 1)
        // Inventory still contains the endpoint, but the playing track supplies no positive sink.
        owner.onOutputsChanged(active.map { it.copy(isActive = false) }, isPlaying = true)
        owner.onOutputsChanged(active, isPlaying = true)

        assertNull(owner.heardRoute)
    }

    @Test
    fun `a released route can be owned again after Automatic actually moves elsewhere`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), speaker))
        owner.explicitSelection(null, listOf(budsA.copy(isActive = true), speaker), isPlaying = true)

        owner.onOutputsChanged(listOf(budsA, speaker.copy(isActive = true)), isPlaying = true)
        owner.onOutputsChanged(listOf(budsA.copy(isActive = true), speaker), isPlaying = true)

        assertEquals(budsA.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `a disconnected remembered headset is forgotten`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true)))

        owner.onOutputsChanged(emptyList(), isPlaying = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(car)))
    }

    /** Issue #100 — the system output chooser moves audio without going through BookWave's own chooser. */
    @Test
    fun `a system chooser move to the speaker supersedes a headset heard by framework policy`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(budsA.copy(isActive = true), speaker))
        assertEquals(RouteHeardEvidence.FrameworkPolicyWhilePlaying, owner.heardRoute?.evidence)
        assertEquals(budsA.id, owner.headsetForCar(listOf(budsA, speaker, car)))

        // No onExplicitSelection: only the framework reports that the speaker now carries playback.
        owner.onOutputsChanged(listOf(budsA, speaker.copy(isActive = true)), isPlaying = true)

        assertEquals(speaker.id, owner.heardRoute?.outputId)
        assertNull(owner.headsetForCar(listOf(budsA, speaker, car)))
    }

    private fun RouteHeardOwnership.explicitSelection(
        outputId: String?,
        outputs: List<AudioOutput>,
        isPlaying: Boolean,
        selectionSequence: Long? = null,
    ): Boolean {
        val current = intentOwner.state.value.selection
        val isNewerSelection = selectionSequence == null || current == null || selectionSequence > current.sequence
        val selection = if (isNewerSelection) {
            intentOwner.select(
                outputId,
                if (outputId == null) OutputIntentOwner.Destination.Automatic else OutputIntentOwner.Destination.Output,
                releasedRouteId = if (outputId == null) heardRoute?.outputId else null,
            )
        } else {
            current
        }
        return onExplicitSelection(
            selection = selection,
            outputs = outputs,
            isAudible = isPlaying,
        )
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(
        id = id,
        displayName = name,
        kind = kind,
        role = role,
        activitySource = AudioOutputActivitySource.FrameworkMediaPolicy,
    )
}

class RouteHeardOwnershipGenerationTest {
    private val buds = output("bluetooth:buds", "Buds")
    private val wired = output(
        id = "wired",
        name = "Wired headphones",
        kind = DeviceKind.Wired,
        role = AudioOutputRole.Headset,
    )
    private val car = output("car", "Car audio", DeviceKind.Car, AudioOutputRole.Car)

    @Test
    fun `route evidence is bound to the loaded book generation`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(buds.copy(isActive = true)))
        val firstGeneration = owner.heardRoute?.generation

        owner.onBookChanged(hasBook = true)

        assertNull(owner.heardRoute)
        owner.onPlaybackObserved(listOf(wired.copy(isActive = true)))
        assertNotEquals(firstGeneration, owner.heardRoute?.generation)
        assertEquals(wired.id, owner.heardRoute?.outputId)
    }

    @Test
    fun `old book route evidence cannot influence the next book`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(buds.copy(isActive = true)))
        assertEquals(buds.id, owner.headsetForCar(listOf(buds, car)))

        owner.onBookChanged(hasBook = true)

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(buds, car)))
    }

    @Test
    fun `emptying the queue invalidates route evidence`() {
        val owner = RouteHeardOwnership(OutputIntentOwner())
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(buds.copy(isActive = true)))

        owner.onQueueEmptied()

        assertNull(owner.heardRoute)
        assertNull(owner.headsetForCar(listOf(buds, car)))
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
