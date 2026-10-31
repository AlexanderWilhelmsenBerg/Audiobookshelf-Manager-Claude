package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real router/guard/position effects; external track reports and playback-thread delivery are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputHandoffEffectsTest {
    @Test
    @Config(sdk = [26, 32])
    fun `older platforms can verify lock-free Car Play from actual projection-path proof`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.playWhenReady = false
        assertTrue(fixture.router.selectCarFromGenericController())
        runCurrent()
        val selection = fixture.router.explicitSelection.value

        assertTrue(
            fixture.playWithProtection {
                assertEquals(0f, fixture.volume, "the one intentional Play must begin muted")
            },
        )
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()

        assertEquals(selection, fixture.router.explicitSelection.value)
        assertEquals(
            OutputIntentOwner.TransferPhase.Delegated,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(1f, fixture.volume)
        assertEquals(1, fixture.plays)
        assertNull(fixture.activeProbe())
    }

    @Test
    @Config(sdk = [26, 32])
    fun `older platforms still refuse unknown output and absent projection while preserving heard progress`() =
        runTest {
            for (projection in listOf(true, false)) {
                val fixture = MutedPositionEffectsTest.Fixture(this)
                fixture.projection = projection
                fixture.playWhenReady = false
                assertTrue(fixture.router.selectCarFromGenericController())
                runCurrent()
                assertTrue(fixture.playWithProtection { assertEquals(0f, fixture.volume) })
                fixture.rawPosition = 25_000L
                fixture.report(if (projection) AudioTrackPath.Unresolved else AudioTrackPath.RemoteSubmix)
                runCurrent()
                assertEquals(0f, fixture.volume, "unproved output cannot become audible")
                advanceTimeBy(5_100L)
                runCurrent()

                assertFalse(fixture.playWhenReady)
                assertEquals(
                    OutputIntentOwner.TransferPhase.Failed,
                    fixture.router.outputIntentOwner.state.value.transfer?.phase,
                )
                assertEquals(12_000L, fixture.rawPosition)
                assertEquals(1f, fixture.volume)
                assertEquals(1, fixture.plays)
                assertNull(fixture.activeProbe())
            }
        }

    @Test
    fun `route proof lost inside success publication cannot release audio or the prior lock`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        assertTrue(fixture.router.keepAudioHere())
        val priorLock = assertNotNull(owner.lock.value)
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.transfer?.phase == OutputIntentOwner.TransferPhase.Delegated) {
                    delivered = true
                    fixture.rawPosition = 25_000L
                    fixture.report(AudioTrackPath.Endpoint(fixture.speakerId))
                }
            }
        }
        fixture.router.selectCar()
        runCurrent()
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()

        assertTrue(delivered, "the synchronous observer must have invalidated the formerly successful path")
        assertFalse(fixture.playWhenReady, "success publication must not permit audio on a newly wrong output")
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(OutputIntentOwner.TransferPhase.Failed, owner.state.value.transfer?.phase)
        assertEquals(priorLock.copy(phase = OutputIntentOwner.LockPhase.Waiting), owner.lock.value)
        assertEquals(1f, fixture.volume)
        assertNull(fixture.activeProbe())
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `policy reassertion reuses a pending explicit request without invalidating muted proof`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        runCurrent()
        fixture.rawPosition = 25_000L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        val active = assertNotNull(fixture.activeProbe())
        fixture.report(AudioTrackPath.Unresolved)
        val requests = fixture.preferenceRequests.size

        assertTrue(fixture.router.reassert(fixture.outputId))
        fixture.deliver(0)
        runCurrent()

        assertSame(active, fixture.activeProbe())
        assertEquals(requests, fixture.preferenceRequests.size, "policy must reuse the pending physical request")
        assertEquals(0f, fixture.volume, "unknown output remains muted after the old barrier")
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertEquals(0f, fixture.volume, "destination proof cannot expose the preparation interval")
        fixture.rawPosition = 12_000L
        fixture.deliver(1)
        runCurrent()
        assertEquals(1f, fixture.volume)
        assertEquals(
            OutputIntentOwner.TransferPhase.Verified,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
    }

    @Test
    fun `policy reassertion cannot override an explicit Car destination`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        val active = assertNotNull(fixture.activeProbe())
        val selection = fixture.router.explicitSelection.value
        val requests = fixture.preferenceRequests.size

        assertFalse(fixture.router.reassert(fixture.outputId))
        runCurrent()

        assertSame(active, fixture.activeProbe())
        assertEquals(selection, fixture.router.explicitSelection.value)
        assertEquals(requests, fixture.preferenceRequests.size)
        assertEquals(0f, fixture.volume)
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `Car transfer retains the old lock and unheard point until current delegated proof`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        assertTrue(fixture.router.keepAudioHere())
        val lock = assertNotNull(owner.lock.value)

        fixture.router.selectCar()
        assertEquals(0f, fixture.volume, "intent publication follows mute acquisition")
        assertEquals(lock, owner.lock.value)
        assertTrue(fixture.preferenceRequests.isEmpty(), "preference execution remains serialized")
        runCurrent()
        assertEquals(listOf<AudioDeviceInfo?>(null), fixture.preferenceRequests)
        assertEquals(OutputIntentOwner.TransferPhase.Verifying, owner.state.value.transfer?.phase)
        assertEquals(lock, owner.lock.value, "the old route observed during settling is not destination proof")
        fixture.rawPosition = 25_000L
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(11_750L, fixture.rawPosition)
        assertEquals(12_000L, fixture.effects.positionMs(), "shared progress stays held during preparation")
        assertEquals(0f, fixture.volume)
        assertEquals(1, fixture.messages.size)
        fixture.deliver(0)
        runCurrent()

        assertEquals(lock, owner.lock.value)
        assertEquals(0f, fixture.volume)
        fixture.rawPosition = 12_000L
        fixture.deliver(1)
        runCurrent()

        assertEquals(OutputIntentOwner.TransferPhase.Delegated, owner.state.value.transfer?.phase)
        assertNull(owner.lock.value)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays, "destination selection cannot manufacture transport Play")
    }

    @Test
    fun `Car Headset Car round trip uses current proof without restarting the book`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        runCurrent()
        assertEquals(0f, fixture.volume)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertEquals(OutputIntentOwner.TransferPhase.Verified, owner.state.value.transfer?.phase)
        assertEquals(1f, fixture.volume)
        assertEquals(12_000L, fixture.rawPosition)

        fixture.router.selectCar()
        runCurrent()
        assertEquals(0f, fixture.volume)
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(OutputIntentOwner.TransferPhase.Delegated, owner.state.value.transfer?.phase)
        assertEquals(1f, fixture.volume)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(0, fixture.plays)
        assertEquals(0, fixture.pauses)
    }

    @Test
    fun `paused destination choice only arms later intentional Play`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.playWhenReady = false
        fixture.router.selectCar()
        runCurrent()
        val owner = fixture.router.outputIntentOwner
        val selection = owner.selection.value
        assertEquals(OutputIntentOwner.TransferPhase.Waiting, owner.state.value.transfer?.phase)
        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)

        fixture.command++
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset(fixture.media))
        fixture.media.play()
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()

        assertEquals(selection, owner.selection.value, "transport reuses the existing destination intent")
        assertEquals(OutputIntentOwner.TransferPhase.Delegated, owner.state.value.transfer?.phase)
        assertEquals(1, fixture.plays, "only the test's intentional raw Play is issued")
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `timeout on the unchanged original route rolls back its preference while muted and stays paused`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        assertTrue(fixture.router.keepAudioHere())
        fixture.router.selectCar()
        runCurrent()
        fixture.rawPosition = 25_000L

        advanceTimeBy(1_001L)
        runCurrent()

        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(OutputIntentOwner.TransferPhase.Failed, owner.state.value.transfer?.phase)
        assertEquals(fixture.outputId, owner.lock.value?.outputId)
        assertEquals(OutputIntentOwner.LockPhase.Waiting, owner.lock.value?.phase)
        assertEquals(2, fixture.preferenceRequests.size)
        assertNotNull(
            fixture.preferenceRequests.last(),
            "rollback requests only the freshly measured original endpoint",
        )
        assertEquals(0, fixture.plays)
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `a mismatched speaker cannot unmute Car or roll back without original route proof`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        assertTrue(fixture.router.keepAudioHere())
        fixture.router.selectCar()
        runCurrent()
        fixture.report(AudioTrackPath.Endpoint(fixture.speakerId))
        runCurrent()

        assertEquals(OutputIntentOwner.TransferPhase.Failed, owner.state.value.transfer?.phase)
        assertFalse(fixture.playWhenReady)
        assertEquals(fixture.outputId, owner.lock.value?.outputId)
        assertEquals(
            listOf<AudioDeviceInfo?>(null),
            fixture.preferenceRequests,
            "wrong output cannot authorize rollback",
        )
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `explicit Speaker is verified as a destination rather than treated as fallback`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.select(fixture.speakerId)
        runCurrent()
        fixture.report(AudioTrackPath.Endpoint(fixture.speakerId))
        runCurrent()

        assertEquals(
            OutputIntentOwner.TransferPhase.Verified,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertTrue(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `book replacement retires suspended transfer without allowing old seek pause or unmute`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        fixture.rawPosition = 25_000L
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(1, fixture.messages.size)
        fixture.replaceBook()
        val pauses = fixture.pauses

        fixture.deliver(0)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("successor-fixture", fixture.item.mediaId)
        assertEquals(5_000L, fixture.rawPosition)
        assertEquals(pauses, fixture.pauses)
        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `newer destination replaces a suspended barrier and old delivery cannot affect it`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        fixture.rawPosition = 25_000L
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        val older = assertNotNull(fixture.activeProbe())
        fixture.router.select(fixture.outputId)
        val successor = assertNotNull(fixture.activeProbe())
        assertFalse(successor === older)
        runCurrent()
        fixture.deliver(0)
        runCurrent()

        assertSame(successor, fixture.activeProbe())
        assertEquals(0f, fixture.volume)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(fixture.messages.lastIndex)
        runCurrent()
        assertEquals(
            OutputIntentOwner.TransferPhase.Verified,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `ordinary Play revalidates the kept output without releasing its lock`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        assertTrue(fixture.router.keepAudioHere())
        val selection = fixture.router.explicitSelection.value
        fixture.playWhenReady = false
        fixture.command++

        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset(fixture.media))
        fixture.media.play()
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()

        val lock = assertNotNull(fixture.router.outputLock.value)
        assertEquals(selection, fixture.router.explicitSelection.value)
        assertEquals(fixture.outputId, lock.outputId)
        assertEquals(OutputIntentOwner.LockPhase.Active, lock.phase)
        assertEquals(1f, fixture.volume)
        assertEquals(1, fixture.plays)
    }

    @Test
    fun `projection presence must become positive before RemoteSubmix can confirm delegation`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.projection = false
        fixture.router.selectCar()
        runCurrent()
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(0f, fixture.volume)
        assertEquals(
            OutputIntentOwner.TransferPhase.Verifying,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )

        fixture.projection = true
        fixture.router.resettle()
        runCurrent()

        assertEquals(
            OutputIntentOwner.TransferPhase.Delegated,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `unknown or multiple target paths stay paused without a guessed success`() = runTest {
        for (path in listOf<AudioTrackPath?>(null, AudioTrackPath.Multiple)) {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            assertTrue(fixture.router.keepAudioHere())
            fixture.router.selectCar()
            runCurrent()
            fixture.rawPosition = 25_000L
            fixture.report(path)
            runCurrent()
            advanceTimeBy(1_001L)
            runCurrent()

            assertEquals(
                OutputIntentOwner.TransferPhase.Failed,
                fixture.router.outputIntentOwner.state.value.transfer?.phase,
            )
            assertEquals(fixture.outputId, fixture.router.outputLock.value?.outputId)
            assertFalse(fixture.playWhenReady)
            assertEquals(12_000L, fixture.rawPosition)
            assertEquals(1f, fixture.volume)
            assertEquals(0, fixture.plays)
        }
    }

    @Test
    fun `a rejected preference cannot strand mute or release the prior logical target`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        assertTrue(fixture.router.keepAudioHere())
        fixture.failPreference = true
        fixture.router.selectCar()
        runCurrent()

        assertEquals(
            OutputIntentOwner.TransferPhase.Failed,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(fixture.outputId, fixture.router.outputLock.value?.outputId)
        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `a newer destination during success publication keeps its own mute and intent`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.transfer?.phase == OutputIntentOwner.TransferPhase.Verified) {
                    delivered = true
                    fixture.router.selectCar()
                }
            }
        }
        fixture.router.select(fixture.outputId)
        runCurrent()

        assertTrue(delivered)
        assertEquals(OutputIntentOwner.Destination.Car, owner.selection.value?.destination)
        assertEquals(0f, fixture.volume, "old completion cannot release the successor's claim")
        assertEquals(0, fixture.pauses)
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(OutputIntentOwner.TransferPhase.Delegated, owner.state.value.transfer?.phase)
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `a legitimate seek inside success publication requires new proof of the same probe`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        assertTrue(fixture.router.keepAudioHere())
        val priorLock = assertNotNull(owner.lock.value)
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.transfer?.phase == OutputIntentOwner.TransferPhase.Verified) {
                    delivered = true
                    fixture.media.seekTo(14_000L)
                }
            }
        }
        // Exercise a real destination change; an already healthy same-target choice now preserves output.
        fixture.observation = AudioTrackObservation(1, AudioTrackPath.RemoteSubmix)
        fixture.routes = emptySet()
        fixture.router.select(fixture.outputId)
        runCurrent()
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()

        assertTrue(delivered)
        assertEquals(14_000L, fixture.rawPosition)
        assertEquals(0f, fixture.volume, "rebasing retains the same lease until its new barrier and proof")
        assertEquals(priorLock, owner.lock.value, "the superseded success cannot release the prior recovery lock")
        assertEquals(1, fixture.messages.size)
        fixture.deliver(0)
        runCurrent()
        assertEquals(OutputIntentOwner.TransferPhase.Verified, owner.state.value.transfer?.phase)
        assertEquals(14_000L, fixture.rawPosition)
        assertEquals(1f, fixture.volume)
        assertNull(owner.lock.value)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `a colliding kept target is denied before any raw Play or new mute claim`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        assertTrue(fixture.router.keepAudioHere())
        fixture.playWhenReady = false
        val field = AndroidLocalOutputAdapter::class.java.getDeclaredField("collidingOutputIds").apply {
            isAccessible =
                true
        }
        field.set(fixture.router.localOutputs, setOf(fixture.outputId))
        fixture.command++

        assertEquals(ManualPlayProtection.Denied, fixture.router.protectManualPlayOnGuardedHeadset(fixture.media))

        assertEquals(0, fixture.plays)
        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
        assertFalse(fixture.playWhenReady)
        assertEquals(fixture.outputId, fixture.router.outputLock.value?.outputId)
    }

    @Test
    fun `Automatic cancellation cannot overwrite a newer choice delivered from its retirement callback`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        fixture.router.selectCar()
        runCurrent()
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.selection?.destination == OutputIntentOwner.Destination.Car &&
                    state.transfer?.phase == OutputIntentOwner.TransferPhase.Waiting
                ) {
                    delivered = true
                    fixture.router.select(fixture.outputId)
                }
            }
        }

        fixture.router.select(null)
        runCurrent()

        assertTrue(delivered, "retirement must synchronously deliver the newer selection")
        assertEquals(fixture.outputId, owner.selection.value?.outputId)
        assertEquals(OutputIntentOwner.Destination.Output, owner.selection.value?.destination)
        assertFalse(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }
}
