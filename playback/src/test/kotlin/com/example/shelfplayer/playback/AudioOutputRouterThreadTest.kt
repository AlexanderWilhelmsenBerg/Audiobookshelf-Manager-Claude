package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
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
    fun `manual headset Play stays silent until the actual track route is verified`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertTrue(fixture.router.protectManualPlayOnGuardedHeadset())
        assertEquals(listOf(0f), fixture.volumeChanges)
        fixture.measuredRoutes { emptySet() }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(listOf(0f), fixture.volumeChanges, "unknown route must remain muted")
        fixture.measuredRoutes { setOf("bluetooth:original") }
        fixture.safetyListener().onIsPlayingChanged(true)
        runCurrent()
        assertEquals(listOf(0f, 1f), fixture.volumeChanges, "exact track route restores volume")
        assertTrue(fixture.pauseThreads.isEmpty())
    }

    @Test
    fun `wrong actual sink aborts silent manual Play without unmuting early`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        assertTrue(fixture.router.protectManualPlayOnGuardedHeadset())
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
        assertTrue(fixture.router.protectManualPlayOnGuardedHeadset())
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
        fixture.guard("bluetooth:original")

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
        val missingWait = AudioOutputRouter::class.java.getDeclaredField("missingHeadsetGrace").run {
            isAccessible = true
            get(fixture.router)
        }
        assertEquals(null, missingWait, "later device churn must not rearm the elapsed timeout")

        // Physical headset media keys typically appear as virtual/system input (-1). The route
        // guard must still be active when Media3 moves back into its playing state.
        val safety = AudioOutputRouter::class.java.getDeclaredField("routeSafetyListener").run {
            isAccessible = true
            get(fixture.router) as Player.Listener
        }
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
            fixture.router.explicitSelection.value?.destination == AudioOutputRouter.ExplicitDestination.Car,
        )
    }

    @Test
    fun `explicit car output choice releases transient headset guard`() = runTest {
        val fixture = fixture()
        fixture.guard("bluetooth:original")
        fixture.router.selectCar()

        assertTrue(fixture.router.selectedId.value == null)
        assertTrue(
            fixture.router.explicitSelection.value?.destination == AudioOutputRouter.ExplicitDestination.Car,
        )
        val guard = AudioOutputRouter::class.java.getDeclaredField("guardedHeadsetId").run {
            isAccessible = true
            get(fixture.router) as? String
        }
        assertTrue(guard == null, "a Play directed to the car must not be cancelled by a stale headset guard")
        runCurrent()
    }

    private suspend fun onDeviceCallback(block: () -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) { block() }
        }
    }

    private fun TestScope.fixture(): Fixture {
        val reads = mutableListOf<Thread>()
        val pauses = mutableListOf<Thread>()
        val volumes = mutableListOf<Float>()
        val owningThread = Thread.currentThread()
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isPlaying" -> {
                    check(Thread.currentThread() == owningThread) { "ExoPlayer isPlaying on the wrong thread" }
                    reads.add(Thread.currentThread())
                    true
                }

                "setPreferredAudioDevice" -> null

                "getVolume" -> volumes.lastOrNull() ?: 1f

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
        val router = AudioOutputRouter(
            context = RuntimeEnvironment.getApplication(),
            logger = object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            applicationScope = this,
            mainDispatcher = StandardTestDispatcher(testScheduler),
        )
        AudioOutputRouter::class.java.getDeclaredField("player").apply {
            isAccessible = true
            set(router, player)
        }
        return Fixture(router, reads, pauses, volumes)
    }

    private data class Fixture(
        val router: AudioOutputRouter,
        val readThreads: List<Thread>,
        val pauseThreads: List<Thread>,
        val volumeChanges: List<Float>,
    ) {
        fun measuredRoutes(routes: () -> Set<String>?) {
            AudioOutputRouter::class.java.getDeclaredField("currentTrackRoutes").apply {
                isAccessible = true
                set(router, routes)
            }
        }

        fun safetyListener(): Player.Listener =
            AudioOutputRouter::class.java.getDeclaredField("routeSafetyListener").run {
                isAccessible = true
                get(router) as Player.Listener
            }

        fun guardId(): String? = AudioOutputRouter::class.java.getDeclaredField("guardedHeadsetId").run {
            isAccessible = true
            get(router) as? String
        }

        fun guard(outputId: String) {
            AudioOutputRouter::class.java.getDeclaredField("guardedHeadsetId").apply {
                isAccessible = true
                set(router, outputId)
            }
            @Suppress("UNCHECKED_CAST")
            val selection = AudioOutputRouter::class.java.getDeclaredField("_selectedId").run {
                isAccessible = true
                get(router) as MutableStateFlow<String?>
            }
            selection.value = outputId
        }
    }
}
