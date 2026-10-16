package com.example.shelfplayer.playback

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
            get(fixture.router) as String?
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
        val owningThread = Thread.currentThread()
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isPlaying" -> {
                    check(Thread.currentThread() == owningThread) { "ExoPlayer isPlaying on the wrong thread" }
                    reads.add(Thread.currentThread())
                    true
                }

                "setPreferredAudioDevice" -> null

                "pause" -> {
                    check(Thread.currentThread() == owningThread) { "ExoPlayer pause on the wrong thread" }
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
        return Fixture(router, reads)
    }

    private data class Fixture(val router: AudioOutputRouter, val readThreads: List<Thread>) {
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
