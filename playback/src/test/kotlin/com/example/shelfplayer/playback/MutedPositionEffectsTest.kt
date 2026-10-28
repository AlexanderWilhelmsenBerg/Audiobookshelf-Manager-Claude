package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Controlled playback-thread delivery around the real command component and real Media3 PlayerMessage. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MutedPositionEffectsTest {
    @Test
    fun `compensation waits for the playback thread and fresh proof exactly once`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(0f, fixture.volume)
        assertEquals(listOf(true), fixture.compensationOrigins)
        assertEquals(1, fixture.messages.size)
        fixture.prove()
        fixture.prove()
        runCurrent()
        assertEquals(1, fixture.messages.size, "repeated route callbacks cannot start another compensation")

        fixture.deliver(0)
        runCurrent()
        assertEquals(1f, fixture.volume)
        assertNull(fixture.activeProbe())
        assertTrue(fixture.measurements >= 2, "post-barrier proof must be re-read")
        assertEquals(0, fixture.plays, "verification must never manufacture transport Play")
    }

    @Test
    fun `a later genuine same-position seek is visible while a compensation barrier is pending`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        fixture.media.seekTo(12_000L)
        runCurrent()

        assertEquals(listOf(true, false), fixture.compensationOrigins)
        assertTrue(fixture.messages[0].isCanceled)
        assertEquals(2, fixture.messages.size)
        fixture.deliver(0)
        runCurrent()
        assertEquals(0f, fixture.volume, "the retired barrier cannot release the current proof")
        fixture.deliver(1)
        runCurrent()
        assertEquals(1f, fixture.volume)
    }

    @Test
    fun `barrier timeout restores the silent tail then pauses before releasing mute`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        fixture.rawPosition = 18_000L
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(1, fixture.pauses)
        assertFalse(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertTrue(fixture.messages.single().isCanceled)
        assertNull(fixture.activeProbe())
    }

    @Test
    fun `newer Play retires old barrier and timeout without touching its successor probe`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        assertNotNull(fixture.effects.before(PlaybackMovement.Play)).invoke()
        fixture.command++
        fixture.playWhenReady = true
        fixture.start()
        val successor = assertNotNull(fixture.activeProbe())
        val pausesBefore = fixture.pauses

        fixture.deliver(0)
        runCurrent()
        assertTrue(fixture.activeProbe() === successor)
        assertEquals(0f, fixture.volume)
        assertEquals(pausesBefore, fixture.pauses)
        fixture.cancel()
    }

    @Test
    fun `unknown wrong and multiple post-seek routes fail closed after restoring silent advancement`() = runTest {
        for (route in listOf(null, setOf("different"), setOf(OUTPUT, "competing"))) {
            val fixture = Fixture(this)
            fixture.start()
            fixture.rawPosition = 25_000L
            fixture.prove()
            runCurrent()
            fixture.routes = route
            fixture.deliver(0)
            runCurrent()
            if (route == null) {
                fixture.rawPosition = 18_000L
                advanceTimeBy(1_001L)
                runCurrent()
            }
            assertEquals(12_000L, fixture.rawPosition)
            assertFalse(fixture.playWhenReady)
            assertEquals(1f, fixture.volume)
            assertEquals(0, fixture.plays)
            assertNull(fixture.activeProbe())
        }
    }

    @Test
    fun `a failed playback-thread message cannot strand the current mute`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.failBarrier = true
        fixture.prove()
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition)
        assertFalse(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertNull(fixture.activeProbe())
    }

    private class Fixture(scope: TestScope) {
        var rawPosition = 12_000L
        var volume = 1f
        var playWhenReady = true
        var command = 0L
        var pauses = 0
        var plays = 0
        var measurements = 0
        var routes: Set<String>? = setOf(OUTPUT)
        var failBarrier = false
        val messages = mutableListOf<PlayerMessage>()
        val compensationOrigins = mutableListOf<Boolean>()
        private val item = MediaItem.Builder().setMediaId("checkpoint-fixture").build()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val gains = PlaybackVolumeEffects(TestAppClock())
        val media: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) {
                _,
                method,
                args,
            ->
            when (method.name) {
                "getCurrentPosition" -> rawPosition

                "getCurrentMediaItem" -> item

                "getMediaItemCount" -> 1

                "getDuration" -> 100_000L

                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                "getPlayWhenReady", "isPlaying" -> playWhenReady

                "getPlaybackState" -> Player.STATE_READY

                "pause" -> {
                    pauses++
                    playWhenReady = false
                    null
                }

                "play" -> {
                    plays++
                    playWhenReady = true
                    null
                }

                "seekTo" -> {
                    rawPosition = args!!.last() as Long
                    compensationOrigins += effects.onSeek(media)
                    null
                }

                "createMessage" -> {
                    check(!failBarrier) { "synthetic message failure" }
                    PlayerMessage(
                        { message -> messages += message },
                        args!![0] as PlayerMessage.Target,
                        Timeline.EMPTY,
                        0,
                        Clock.DEFAULT,
                        Looper.getMainLooper(),
                    )
                }

                else -> null
            }
        } as ExoPlayer
        private val router = AudioOutputRouter(
            RuntimeEnvironment.getApplication(),
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            scope.backgroundScope,
            dispatcher,
            volumeEffects = gains,
        )
        val effects: MutedPositionEffects = MutedPositionEffects(
            router,
            object : MutedPositionEffects.Source {
                override fun player() = media
                override fun context() = MutedPositionEffects.Context(command, 0, 0)
            },
        )
        private val guard = field<HeadsetRouteGuard>(router, "headsetGuard")

        init {
            gains.attach(media)
            AudioOutputRouter::class.java.getDeclaredField("player").apply { isAccessible = true }.set(router, media)
            val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
            AudioOutputRouter::class.java.getDeclaredField("live").apply {
                isAccessible = true
            }.set(router, mapOf(OUTPUT to device))
            field<MutableStateFlow<String?>>(router, "_selectedId").value = OUTPUT
            AudioOutputRouter::class.java.getDeclaredField("currentTrackRoutes").apply { isAccessible = true }.set(
                router,
                {
                    measurements++
                    routes
                },
            )
            guard.attachPlayer(media)
            guard.protect(OUTPUT)
            effects.install()
        }

        fun start() = assertTrue(guard.beginProbe(OUTPUT, media, 0))
        fun prove() = guard.verifyProbeIfActive(setOf(OUTPUT), emptySet(), true)
        fun activeProbe() = guard.currentProbe
        fun cancel() = guard.cancelProbe()
        fun deliver(index: Int) = messages[index].target.handleMessage(0, null)

        private inline fun <reified T> field(target: Any, name: String): T =
            target.javaClass.getDeclaredField(name).run {
                isAccessible = true
                get(target) as T
            }
    }

    private companion object {
        const val OUTPUT = "bluetooth:checkpoint-fixture"
    }
}
