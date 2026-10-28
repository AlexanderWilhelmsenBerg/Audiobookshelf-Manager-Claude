package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub #128 / physical crash build 2223:
 * AudioOutputRouter.settle -> publish read ExoPlayer.isPlaying on the settle worker.
 *
 * Exercise the real router and its production resettle callback from a worker, using
 * an ExoPlayer proxy that throws on a read from anywhere except the test's player thread.
 * This fails before the fix even with an unguarded route, because method arguments
 * eagerly evaluated player.isPlaying before MainImmediate was entered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputRouterThreadTest {
    @Test
    fun `Keep audio here rejects collision or newer intent delivered during measurement`() = runTest {
        for (collision in listOf(false, true)) {
            val fixture = fixture()
            val endpoint = fixture.observeEndpoint(47)
            fixture.router.outputIntentOwner.onBookChanged(15L)
            fixture.present(endpoint)
            var queries = 0
            fixture.measuredRoutes {
                queries++
                if (queries == 2) {
                    if (collision) fixture.colliding(endpoint.id) else fixture.router.selectCar()
                }
                setOf(endpoint.id)
            }

            assertTrue(!fixture.router.keepAudioHere())
            assertEquals(null, fixture.router.outputLock.value)
            if (!collision) {
                assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
            }
            runCurrent()
        }
    }

    @Test
    fun `Keep audio here cannot authorize a replacement player from its predecessor measurement`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        val endpoint = fixture.observeEndpoint(48)
        fixture.router.outputIntentOwner.onBookChanged(16L)
        fixture.present(endpoint)
        var queries = 0
        fixture.measuredRoutes {
            queries++
            if (queries == 2) fixture.replacePlayerFrom(replacement)
            setOf(endpoint.id)
        }

        assertTrue(!fixture.router.keepAudioHere())
        assertEquals(null, fixture.router.outputLock.value)
        assertTrue(replacement.pauseThreads.isEmpty())
    }

    @Test
    fun `Keep audio here arms only a unique exact live playing track and shares router owner`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(40, "Ambiguous A2DP")
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.outputIntentOwner.onBookChanged(7L)
        fixture.present(endpoint)

        assertTrue(fixture.router.keepAudioHere())
        val lock = fixture.router.outputLock.value
        assertEquals(OutputIntentOwner.LockPhase.Active, lock?.phase)
        assertEquals(endpoint.id, lock?.outputId)
        assertEquals(endpoint.id, fixture.router.selectedId.value)
        assertEquals(lock?.selectionSequence, fixture.router.explicitSelection.value?.sequence)
        assertTrue(!fixture.router.selectCarFromGenericController())
        assertEquals(lock?.selectionSequence, fixture.router.explicitSelection.value?.sequence)
        assertEquals(endpoint.id, fixture.router.outputLock.value?.outputId)
    }

    @Test
    fun `Keep audio here refuses unknown duplicate and stale measurements`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(43, "Ambiguous A2DP")
        fixture.router.outputIntentOwner.onBookChanged(5L)
        fixture.present(endpoint)

        fixture.measuredRoutes { null }
        assertTrue(!fixture.router.keepAudioHere())
        fixture.measuredRoutes { setOf(endpoint.id, "other") }
        assertTrue(!fixture.router.keepAudioHere())
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.colliding(endpoint.id)
        assertTrue(!fixture.router.keepAudioHere())
        fixture.colliding(null)
        fixture.present(endpoint, com.example.shelfplayer.core.model.playback.AudioOutputRole.Car)
        assertTrue(!fixture.router.keepAudioHere(), "a measured car/speaker category is not eligible")
        assertEquals(null, fixture.router.outputLock.value)
    }

    @Test
    fun `Keep audio here rejects a book generation change during route query`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(44, "Ambiguous A2DP")
        fixture.router.outputIntentOwner.onBookChanged(11L)
        fixture.present(endpoint)
        var reads = 0
        fixture.measuredRoutes {
            reads += 1
            if (reads == 2) fixture.router.outputIntentOwner.onBookChanged(12L)
            setOf(endpoint.id)
        }

        assertTrue(!fixture.router.keepAudioHere())
        assertEquals(null, fixture.router.outputLock.value)
    }

    @Test
    fun `Waiting lock fails closed when the next book plays through a different route`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(46, "Ambiguous A2DP")
        fixture.router.outputIntentOwner.onBookChanged(13L)
        fixture.present(endpoint)
        fixture.measuredRoutes { setOf(endpoint.id) }
        assertTrue(fixture.router.keepAudioHere())

        fixture.router.outputIntentOwner.onBookChanged(14L)
        fixture.measuredRoutes { setOf("different-output") }
        fixture.router.verifyOutputLock()
        runCurrent()

        assertEquals(OutputIntentOwner.LockPhase.Unverified, fixture.router.outputLock.value?.phase)
        assertTrue(fixture.pauseThreads.isNotEmpty(), "waiting lock must safety-pause a different measured sink")
    }

    @Test
    fun `automatic playing exact ambiguous endpoint is guarded before route loss`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(41, "Car or earbuds")
        val routeOwner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(hasBook = true) }
        val measuredOutput = endpoint.output.copy(
            id = endpoint.id,
            isActive = true,
            activitySource = AudioOutputActivitySource.PlayingAudioTrack,
        )
        routeOwner.onPlaybackObserved(listOf(measuredOutput))
        fixture.measuredRoutes { setOf(endpoint.id) }
        val generation = requireNotNull(routeOwner.currentGeneration)
        val headset = requireNotNull(routeOwner.measuredPlayingHeadsetForContinuity(listOf(measuredOutput)))
        assertTrue(
            fixture.router.protectMeasuredPlayingHeadset(headset, generation, 0L) {
                routeOwner.currentGeneration == generation &&
                    routeOwner.measuredPlayingHeadsetForContinuity(listOf(measuredOutput)) == headset
            },
        )
        fixture.router.onActualTrackRouteChanged()
        runCurrent()

        assertEquals(endpoint.id, fixture.guardId(), "exact measured endpoint should be protected while Automatic")
        assertEquals(endpoint.id, fixture.router.selectedId.value)

        fixture.measuredRoutes { emptySet() }
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertTrue(fixture.pauseThreads.isNotEmpty(), "route loss must pause at the measured callback boundary")

        AudioOutputRouter::class.java.getDeclaredMethod(
            "onDisconnectedSelection",
            String::class.java,
            Set::class.java,
        ).apply { isAccessible = true }.invoke(fixture.router, endpoint.id, emptySet<String>())
        val replacement = fixture.observeEndpoint(45, "Car or earbuds")
        fixture.measuredRoutes { setOf(replacement.id) }
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertEquals(endpoint.id, fixture.guardId(), "a new process-local appearance is not the old destination")
        assertEquals(endpoint.id, fixture.router.selectedId.value, "a new appearance must not be adopted automatically")
        assertTrue(fixture.pauseThreads.isNotEmpty(), "a replacement measured sink remains outside the old guard")
    }

    @Test
    fun `explicit destination and nonexact measurements do not arm automatic route guard`() = runTest {
        val fixture = fixture()
        val info = device(42, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Ambiguous output")
        val registry = fixture.registry
        val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(42, info.type, info.productName?.toString())),
        ).single()
        AudioOutputRouter::class.java.getDeclaredField("live").apply {
            isAccessible = true
            set(fixture.router, mapOf(endpoint.id to info))
        }
        fixture.measuredRoutes { setOf(endpoint.id, "bluetooth:other") }
        assertTrue(!fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 0L) { true })
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertEquals(null, fixture.guardId(), "multiple measured sinks cannot establish endpoint ownership")

        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.selectCar()
        runCurrent()
        assertTrue(
            !fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 1L) { true },
            "explicit Car intent is not permission to capture an Automatic continuity guard",
        )
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertEquals(null, fixture.guardId(), "explicit Car intent must not be overwritten by measured route")
    }

    @Test
    fun `reentrant destination change during route proof prevents stale protection`() = runTest {
        val fixture = fixture()
        val info = device(43, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Ambiguous output")
        val endpoint = fixture.registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(43, info.type, info.productName?.toString())),
        ).single()
        AudioOutputRouter::class.java.getDeclaredField("live").apply {
            isAccessible = true
            set(fixture.router, mapOf(endpoint.id to info))
        }
        var changeDestination = true
        fixture.measuredRoutes {
            if (changeDestination) {
                changeDestination = false
                fixture.router.selectCar()
            }
            setOf(endpoint.id)
        }

        val armed = fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 0L) { true }
        assertTrue(!armed, "newer explicit destination during measurement must invalidate the claim")
        runCurrent()
        assertEquals(null, fixture.guardId())
    }

    @Test
    fun `book boundary clears only the transient automatic protection`() = runTest {
        val fixture = fixture()
        val info = device(44, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Ambiguous output")
        val endpoint = fixture.registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(44, info.type, info.productName?.toString())),
        ).single()
        AudioOutputRouter::class.java.getDeclaredField("live").apply {
            isAccessible = true
            set(fixture.router, mapOf(endpoint.id to info))
        }
        fixture.measuredRoutes { setOf(endpoint.id) }
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 7L, 0L) { true })
        assertTrue(fixture.router.reassert(endpoint.id), "same-endpoint policy reassertion remains safe")
        runCurrent()
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        assertEquals(listOf(0f), fixture.volumeChanges)
        fixture.router.clearMeasuredPlayingHeadsetProtection()
        runCurrent()

        assertEquals(null, fixture.guardId())
        assertEquals(null, fixture.router.selectedId.value)
        assertEquals(listOf(0f, 1f), fixture.volumeChanges, "book cleanup restores only the probe-owned volume")
        assertTrue(fixture.pauseThreads.isEmpty(), "clearing a transient claim must not pause playback")
    }

    @Test
    fun `collision introduced by route query cannot establish protection`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(46)
        fixture.measuredRoutes {
            fixture.colliding(endpoint.id)
            setOf(endpoint.id)
        }

        assertTrue(!fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 0L) { true })
        assertEquals(null, fixture.guardId())
    }

    @Test
    fun `book replaced during route query cannot establish the old claim`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(47)
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(true) }
        val generation = requireNotNull(owner.currentGeneration)
        fixture.measuredRoutes {
            owner.onBookChanged(true)
            setOf(endpoint.id)
        }

        assertTrue(
            !fixture.router.protectMeasuredPlayingHeadset(endpoint.id, generation, 0L) {
                owner.currentGeneration == generation
            },
        )
        assertEquals(null, fixture.guardId())
    }

    @Test
    fun `new attachment releases implicit protection and restores the old probe volume`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        val endpoint = fixture.observeEndpoint(48)
        fixture.measuredRoutes { setOf(endpoint.id) }
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 0L) { true })
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())

        fixture.router.attach(replacement.player)
        runCurrent()

        assertEquals(null, fixture.guardId())
        assertEquals(null, fixture.router.selectedId.value)
        assertEquals(listOf(0f, 1f), fixture.volumeChanges)
        assertTrue(replacement.pauseThreads.isEmpty())
    }

    @Test
    fun `book cleanup preserves a newer explicit choice of the same endpoint`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(49)
        fixture.measuredRoutes { setOf(endpoint.id) }
        assertTrue(fixture.router.protectMeasuredPlayingHeadset(endpoint.id, 1L, 0L) { true })

        fixture.router.select(endpoint.id)
        fixture.router.clearMeasuredPlayingHeadsetProtection()
        runCurrent()

        assertEquals(endpoint.id, fixture.router.selectedId.value)
        assertEquals(1L, fixture.router.explicitSelection.value?.sequence)
    }

    @Test
    fun `ordinary background resettle does not read an unguarded ExoPlayer`() = runTest {
        val fixture = fixture()
        onDeviceCallback { fixture.router.resettle() }

        assertTrue(fixture.readThreads.isEmpty(), "off-thread publish must never consult Player")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(fixture.readThreads.isEmpty(), "unguarded output updates need no Player read")
    }

    @Test
    fun `guarded background resettle reads and pauses only on Player dispatcher`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        onDeviceCallback { fixture.router.resettle() }

        // The test scheduler may run the queued Main task before withContext returns;
        // thread ownership, not an assumed callback scheduling order, is the invariant.
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertTrue(fixture.readThreads.isNotEmpty(), "a guarded route needs an eligibility check")
        assertTrue(fixture.readThreads.all { it == Thread.currentThread() }, "only the Player looper may read Player")
    }

    @Test
    fun `delayed automatic request cannot override newer guarded headset selection`() = runTest {
        val fixture = fixture()
        // Both calls happen before the main-dispatcher work is drained.
        fixture.router.selectCar()
        fixture.guard("bluetooth:original")
        runCurrent()
        assertTrue(
            fixture.preferredRequests.isEmpty(),
            "stale Automatic/Car work must not clear a later exact headset preference",
        )
    }

    @Test
    fun `rapid worker resettles never read guarded Media3 player off its dispatcher`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.measuredRoutes { setOf("bluetooth:car") }

        onDeviceCallback {
            repeat(3) { fixture.router.resettle() }
        }
        assertTrue(
            fixture.readThreads.all { it == Thread.currentThread() },
            "the test scheduler may drain callbacks before returning, but never on the worker",
        )
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertTrue(fixture.readThreads.isNotEmpty())
        assertTrue(
            fixture.readThreads.all { it == Thread.currentThread() },
            "even after route-settle delays, player reads belong on the Media3 dispatcher",
        )
        assertTrue(fixture.pauseThreads.all { it == Thread.currentThread() })
    }

    @Test
    fun `repeated explicit car and automatic requests execute only latest null preference`() = runTest {
        val fixture = fixture()
        fixture.router.selectCar()
        fixture.router.select(null)
        fixture.router.selectCar()
        val latest = fixture.router.explicitSelection.value
        assertEquals(OutputIntentOwner.Destination.Car, latest?.destination)
        assertEquals(3L, latest?.sequence)

        runCurrent()
        assertEquals(1, fixture.preferredRequests.size, "equal null targets must still supersede old requests")
        assertEquals(null, fixture.preferredRequests.single())
    }

    @Test
    fun `detaching player invalidates queued preference before its dispatcher receives work`() = runTest {
        val fixture = fixture()
        fixture.router.selectCar()
        fixture.router.detach()
        runCurrent()
        assertTrue(
            fixture.preferredRequests.isEmpty(),
            "a request from an earlier player attachment must not reach a detached ExoPlayer",
        )
    }

    @Test
    fun `manual headset Play stays silent until the actual track route is verified`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        assertEquals(listOf(0f), fixture.volumeChanges)
        fixture.measuredRoutes { emptySet() }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(listOf(0f), fixture.volumeChanges, "unknown route must remain muted")
        fixture.measuredRoutes { setOf("bluetooth:original") }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(listOf(0f, 1f), fixture.volumeChanges, "exact track route restores volume")
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(
            listOf(0f, 1f),
            fixture.volumeChanges,
            "duplicate callbacks cannot restore the retired probe twice",
        )
        assertTrue(fixture.pauseThreads.isEmpty())
    }

    @Test
    fun `wrong actual sink aborts silent manual Play without unmuting early`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        fixture.measuredRoutes { setOf("bluetooth:car") }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(listOf(0f, 1f), fixture.volumeChanges)
        assertEquals(1, fixture.pauseThreads.size, "wrong physical sink must be paused while silent")
    }

    @Test
    fun `unknown actual sink times out while silent and pauses`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        fixture.measuredRoutes { emptySet() }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        advanceTimeBy(550)
        runCurrent()
        assertEquals(1, fixture.pauseThreads.size)
        assertEquals(listOf(0f, 1f), fixture.volumeChanges)
    }

    @Test
    fun `route guard prefers verified AudioTrack over projected remote mix prediction`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.measuredRoutes { setOf("bluetooth:original") }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(0, fixture.pauseThreads.size, "the actual headset is carrying the AudioTrack")

        fixture.measuredRoutes { emptySet() }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(1, fixture.pauseThreads.size, "a remote submix is not the physical headset")
    }

    @Test
    fun `unmeasured track cannot authorize guarded playback after reconnect`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        // Null means no playing AudioTrack was measured; a framework prediction is weaker.
        fixture.measuredRoutes { null }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(1, fixture.pauseThreads.size, "an unmeasured guarded track must pause safely")

        // Source-backed production entrypoint guard: Robolectric cannot model an OEM
        // media-policy prediction that contradicts the real AudioTrack. This assertion
        // ensures a future change cannot reintroduce that unsafe fallback.
        val source = java.io.File("src/main/kotlin/com/example/shelfplayer/playback/AudioOutputRouter.kt")
            .readText()
        val guard = source.substringAfter("private fun pauseUnsafeGuardedPlayback() {")
            .substringBefore("/** Framework-reported devices")
        assertTrue(!guard.contains("routedIds()"), "route safety must require measured AudioTrack evidence")
    }

    @Test
    fun `repeated car route transitions preserve exact guard and car selection can supersede it`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.measuredRoutes { setOf("bluetooth:original") }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(0, fixture.pauseThreads.size)

        // Second projection session: actual track moves to RemoteSubmix.
        fixture.measuredRoutes { emptySet() }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(1, fixture.pauseThreads.size)

        // A subsequent unmeasured Play must also remain guarded.
        fixture.measuredRoutes { null }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(2, fixture.pauseThreads.size)

        // A real explicit Car choice is newer listener intent; never resurrect
        // the previous headset automatically based on an unattributed media key.
        fixture.router.selectCar()
        runCurrent()
        assertEquals(null, fixture.guardId())
        assertEquals(
            OutputIntentOwner.Destination.Car,
            fixture.router.explicitSelection.value?.destination,
        )
    }

    @Test
    fun `silent probe allows only reasserted exact device when prediction is unknown`() {
        val id = "bluetooth:original"
        assertTrue(canAttemptMutedHeadsetProbe(id, id, id, true, emptySet()))
        assertTrue(!canAttemptMutedHeadsetProbe(id, id, id, true, setOf("bluetooth:car")))
        assertTrue(!canAttemptMutedHeadsetProbe(id, id, "bluetooth:other", true, emptySet()))
        assertTrue(!canAttemptMutedHeadsetProbe(id, id, id, false, emptySet()))
    }

    @Test
    fun `measured exact headset overrides remote mix prediction`() {
        val predictedRemoteMix = emptySet<String>()
        val measuredHeadset = setOf("bluetooth:original")
        val id = "bluetooth:original"
        assertTrue(shouldSuspendGuardedPlayback(id, id, predictedRemoteMix, true))
        assertTrue(!shouldSuspendGuardedPlayback(id, id, measuredHeadset, true))
    }

    @Test
    fun `measured different or multiple sinks cannot authorize headset playback`() {
        val id = "bluetooth:original"
        assertTrue(shouldSuspendGuardedPlayback(id, id, setOf("bluetooth:car"), true))
        assertTrue(shouldSuspendGuardedPlayback(id, id, setOf(id, "bluetooth:car"), true))
        assertTrue(shouldSuspendGuardedPlayback(id, id, emptySet(), true))
    }

    @Test
    fun `after grace expires unattributed Play still cannot escape to default car route`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original", connected = false)

        // Emulate the exact missing-output branch reached by AudioDeviceCallback after car arrival.
        // The OS may expose only an unmapped projection output for much longer than two seconds.
        AudioOutputRouter::class.java.getDeclaredMethod(
            "onDisconnectedSelection",
            String::class.java,
            Set::class.java,
        ).apply { isAccessible = true }.invoke(fixture.router, "bluetooth:original", emptySet<String>())
        runCurrent()
        assertEquals(1, fixture.pauseThreads.size, "the missing headset must immediately suspend audio")

        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(
            "bluetooth:original",
            fixture.router.selectedId.value,
            "an expired grace period is not consent for automatic car routing",
        )
        assertEquals("bluetooth:original", fixture.guardId())

        // A later unrelated device callback must not restart the expired two-second grace.
        AudioOutputRouter::class.java.getDeclaredMethod(
            "onDisconnectedSelection",
            String::class.java,
            Set::class.java,
        ).apply { isAccessible = true }.invoke(fixture.router, "bluetooth:original", emptySet<String>())
        runCurrent()
        val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
            isAccessible = true
            get(fixture.router)
        }
        val missingWait = HeadsetRouteGuard::class.java.getDeclaredField("disappearanceGrace").run {
            isAccessible = true
            get(owner)
        }
        assertEquals(null, missingWait, "later device churn must not rearm the elapsed timeout")

        // Physical headset media keys typically appear as virtual/system input (-1). The route
        // guard must still be active when Media3 moves back into its playing state.
        val safety = requireNotNull(fixture.guardOwner().currentPlayerListener())
        safety.onIsPlayingChanged(true)
        runCurrent()
        assertTrue(fixture.pauseThreads.size >= 2, "post-expiry Play must be paused on an unknown route")
        assertTrue(fixture.pauseThreads.all { it == Thread.currentThread() })
    }

    @Test
    fun `explicit car Play after grace removes headset ownership without restoring it`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        AudioOutputRouter::class.java.getDeclaredMethod(
            "onDisconnectedSelection",
            String::class.java,
            Set::class.java,
        ).apply { isAccessible = true }.invoke(fixture.router, "bluetooth:original", emptySet<String>())
        runCurrent()
        advanceTimeBy(2_100)
        runCurrent()
        fixture.router.selectCar()
        runCurrent()
        assertEquals(null, fixture.router.selectedId.value)
        assertEquals(null, fixture.guardId())
        assertTrue(
            fixture.router.explicitSelection.value?.destination == OutputIntentOwner.Destination.Car,
        )
    }

    @Test
    fun `explicit car output choice releases transient headset guard`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.router.selectCar()

        assertTrue(fixture.router.selectedId.value == null)
        assertTrue(
            fixture.router.explicitSelection.value?.destination == OutputIntentOwner.Destination.Car,
        )
        val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
            isAccessible = true
            get(fixture.router)
        }
        val guard = HeadsetRouteGuard::class.java.getDeclaredField("endpointId").run {
            isAccessible = true
            get(owner) as? String
        }
        assertTrue(guard == null, "a Play directed to the car must not be cancelled by a stale headset guard")
        runCurrent()
    }

    private suspend fun onDeviceCallback(block: () -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) { block() }
        }
    }
}

private fun device(id: Int, type: Int, name: String): AudioDeviceInfo {
    val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    val port = org.robolectric.util.ReflectionHelpers.getField<Any>(device, "mPort")
    org.robolectric.util.ReflectionHelpers.setField(port, "mName", name)
    val handle = org.robolectric.util.ReflectionHelpers.getField<Any>(port, "mHandle")
    org.robolectric.util.ReflectionHelpers.setField(handle, "mId", id)
    return device
}

private fun TestScope.fixture(): Fixture {
    val reads = mutableListOf<Thread>()
    val pauses = mutableListOf<Thread>()
    val volumes = mutableListOf<Float>()
    val preferredRequests = mutableListOf<Any?>()
    val player = threadCheckedPlayer(reads, pauses, volumes, preferredRequests)
    val registry = LiveOutputEndpointRegistry()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val volumeEffects = volumeEffectsForTest(dispatcher)
    volumeEffects.attach(player)
    val router = AudioOutputRouter(
        context = RuntimeEnvironment.getApplication(),
        logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        },
        applicationScope = this,
        mainDispatcher = dispatcher,
        endpointRegistry = registry,
        volumeEffects = volumeEffects,
    )
    AudioOutputRouter::class.java.getDeclaredField("player").apply {
        isAccessible = true
        set(router, player)
    }
    val guardOwner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
        isAccessible = true
        get(router) as HeadsetRouteGuard
    }
    guardOwner.attachPlayer(player)
    return Fixture(router, registry, reads, pauses, volumes, preferredRequests, player, volumeEffects)
}

private fun threadCheckedPlayer(
    reads: MutableList<Thread>,
    pauses: MutableList<Thread>,
    volumes: MutableList<Float>,
    preferredRequests: MutableList<Any?>,
): ExoPlayer {
    val owningThread = Thread.currentThread()
    return Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "isPlaying" -> {
                check(Thread.currentThread() == owningThread) { "ExoPlayer isPlaying on the wrong thread" }
                reads.add(Thread.currentThread())
                true
            }

            "setPreferredAudioDevice" -> {
                check(Thread.currentThread() == owningThread) {
                    "setPreferredAudioDevice on the wrong Media3 thread"
                }
                preferredRequests.add(args?.get(0))
                null
            }

            "addListener", "removeListener" -> null

            "getVolume" -> volumes.lastOrNull() ?: 1f

            "getMediaItemCount" -> 1

            "setVolume" -> {
                volumes.add(args?.get(0) as Float)
                null
            }

            "pause" -> {
                check(Thread.currentThread() == owningThread) { "ExoPlayer pause on the wrong thread" }
                pauses.add(Thread.currentThread())
                null
            }

            else -> error("Unexpected ExoPlayer call: ${method.name}")
        }
    } as ExoPlayer
}

private data class Fixture(
    val router: AudioOutputRouter,
    val registry: LiveOutputEndpointRegistry,
    val readThreads: List<Thread>,
    val pauseThreads: List<Thread>,
    val volumeChanges: List<Float>,
    val preferredRequests: List<Any?>,
    val player: ExoPlayer,
    val volumeEffects: PlaybackVolumeEffects,
) {
    fun measuredRoutes(routes: () -> Set<String>?) {
        AudioOutputRouter::class.java.getDeclaredField("currentTrackRoutes").apply {
            isAccessible = true
            set(router, routes)
        }
    }

    fun present(
        endpoint: LiveOutputEndpointRegistry.Endpoint,
        role: com.example.shelfplayer.core.model.playback.AudioOutputRole = endpoint.output.role,
    ) {
        @Suppress("UNCHECKED_CAST")
        val outputs = AudioOutputRouter::class.java.getDeclaredField("_outputs").run {
            isAccessible = true
            get(router) as MutableStateFlow<List<com.example.shelfplayer.core.model.playback.AudioOutput>>
        }
        outputs.value = listOf(
            endpoint.output.copy(
                id = endpoint.id,
                role = role,
                isActive = true,
                activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            ),
        )
    }

    fun safetyListener(): Player.Listener = requireNotNull(guardOwner().currentPlayerListener())

    fun guardId(): String? = guardOwner().endpointId

    fun observeEndpoint(platformId: Int, name: String = "Synthetic output"): LiveOutputEndpointRegistry.Endpoint {
        val info = device(platformId, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name)
        val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(platformId, info.type, info.productName?.toString())),
        ).single()
        AudioOutputRouter::class.java.getDeclaredField("live").apply {
            isAccessible = true
            set(router, mapOf(endpoint.id to info))
        }
        return endpoint
    }

    fun guardOwner(): HeadsetRouteGuard = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
        isAccessible = true
        get(router) as HeadsetRouteGuard
    }

    fun colliding(outputId: String?) {
        AudioOutputRouter::class.java.getDeclaredField("collidingOutputIds").apply {
            isAccessible = true
            set(router, outputId?.let(::setOf).orEmpty())
        }
    }

    fun replacePlayerFrom(other: Fixture) {
        val field = AudioOutputRouter::class.java.getDeclaredField("player").apply { isAccessible = true }
        volumeEffects.attach(other.player)
        guardOwner().attachPlayer(other.player)
        field.set(router, other.player)
    }

    fun guard(outputId: String, connected: Boolean = true) {
        // These probe fixtures represent a connected guarded endpoint; disappearance tests refresh inventory.
        AudioOutputRouter::class.java.getDeclaredField("live").apply {
            isAccessible = true
            set(
                router,
                if (connected) {
                    mapOf(outputId to device(99, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Fixture headset"))
                } else {
                    emptyMap<String, AudioDeviceInfo>()
                },
            )
        }
        guardOwner().protect(outputId)
        @Suppress("UNCHECKED_CAST")
        val selection = AudioOutputRouter::class.java.getDeclaredField("_selectedId").run {
            isAccessible = true
            get(router) as MutableStateFlow<String?>
        }
        selection.value = outputId
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputRouterQueuedSafetyTest {
    @Test
    fun `old player callbacks cannot cancel a replacement player's probe`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        fixture.router.attach(fixture.player)
        val oldListener = requireNotNull(fixture.guardOwner().currentPlayerListener())
        fixture.router.attach(replacement.player)
        runCurrent()

        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        val pausesBeforeOldCallback = replacement.pauseThreads.size
        oldListener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        oldListener.onIsPlayingChanged(true)
        runCurrent()

        assertEquals(listOf(0f), replacement.volumeChanges, "retired callbacks must not restore a new probe")
        assertEquals(pausesBeforeOldCallback, replacement.pauseThreads.size, "retired callbacks cannot pause successor")
    }

    @Test
    fun `old listener callbacks cannot affect a reattached same player probe`() = runTest {
        val fixture = fixture()
        fixture.router.attach(fixture.player)
        val retiredListener = requireNotNull(fixture.guardOwner().currentPlayerListener())
        fixture.router.attach(fixture.player)
        runCurrent()

        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        val pausesBeforeRetiredCallback = fixture.pauseThreads.size
        retiredListener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        retiredListener.onIsPlayingChanged(true)
        runCurrent()

        assertEquals(listOf(0f), fixture.volumeChanges, "only the current listener may cancel the probe")
        assertEquals(pausesBeforeRetiredCallback, fixture.pauseThreads.size)
    }

    @Test
    fun `stale probe timeout restores its volume without pausing after a newer car choice`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())

        fixture.router.selectCar()
        fixture.guardOwner().verifyProbeIfActive(
            actualRoute = setOf("bluetooth:car"),
            collisions = emptySet(),
            isPlaying = true,
        )
        runCurrent()

        assertEquals(listOf(0f, 1f), fixture.volumeChanges, "the old silent probe must clean up its own volume")
        assertTrue(fixture.pauseThreads.isEmpty(), "stale timeout must not pause after explicit Car intent")
    }

    @Test
    fun `cancelling a probe restores volume to the player that owns it`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        fixture.guard("bluetooth:original")
        assertEquals(ManualPlayProtection.MutedAllowed, fixture.router.protectManualPlayOnGuardedHeadset())
        fixture.replacePlayerFrom(replacement)

        fixture.router.selectCar()
        runCurrent()

        assertEquals(listOf(0f, 1f), fixture.volumeChanges, "the muted owner must regain its prior volume")
        assertTrue(replacement.volumeChanges.isEmpty(), "stale cleanup must not alter a replacement player")
    }

    @Test
    fun `queued safety cannot act on a replacement player`() = runTest {
        val fixture = fixture()
        val replacement = fixture()
        fixture.guard("bluetooth:original")
        fixture.colliding("bluetooth:original")
        fixture.safetyListener().onIsPlayingChanged(true)
        fixture.replacePlayerFrom(replacement)

        runCurrent()

        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(replacement.pauseThreads.isEmpty(), "old routing work cannot pause a replacement player")
        assertTrue(replacement.readThreads.isEmpty())
    }

    @Test
    fun `queued collision safety cannot pause after the listener releases the old guard`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.colliding("bluetooth:original")
        fixture.router.resettle()

        fixture.router.selectCar()
        runCurrent()

        assertTrue(fixture.pauseThreads.isEmpty(), "old collision work must not override the newer Car choice")
        assertTrue(fixture.readThreads.isEmpty(), "the released request cannot read the current player")
    }

    @Test
    fun `queued safety cannot cross a newer same endpoint ownership epoch`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.colliding("bluetooth:original")
        fixture.router.resettle()
        fixture.router.selectCar()
        // The new owner can choose the same endpoint again before the old main-dispatcher callback runs.
        fixture.guard("bluetooth:original")

        runCurrent()

        assertTrue(fixture.pauseThreads.isEmpty(), "an old callback cannot act on the new same-id ownership")
    }

    @Test
    fun `current collision still fails closed even when the measured id matches`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.colliding("bluetooth:original")
        fixture.measuredRoutes { setOf("bluetooth:original") }

        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()

        assertEquals(1, fixture.pauseThreads.size, "colliding endpoints are never exact physical proof")
    }

    @Test
    fun `selection invalidation inside route measurement prevents the old pause`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.colliding("bluetooth:original")
        var invalidate = true
        fixture.measuredRoutes {
            if (invalidate) {
                invalidate = false
                fixture.router.selectCar()
            }
            null
        }

        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()

        assertTrue(fixture.pauseThreads.isEmpty(), "a route read cannot revive invalidated headset work")
    }
}
