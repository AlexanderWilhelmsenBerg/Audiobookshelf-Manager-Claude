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
        for (route in listOf(null, setOf("different"), setOf("one", "competing"))) {
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

    @Test
    fun `route proof arriving after a silent wait compensates the additional elapsed tail`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        fixture.routes = null
        fixture.deliver(0)
        runCurrent()
        assertEquals(0f, fixture.volume)
        fixture.rawPosition = 20_000L
        fixture.routes = setOf(fixture.outputId)

        fixture.prove()
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition, "later successful proof cannot skip a second unheard interval")
        assertEquals(0f, fixture.volume, "the second compensation needs its own post-seek playback barrier")
        assertEquals(2, fixture.messages.size)
        fixture.deliver(1)
        runCurrent()
        assertEquals(1f, fixture.volume)
        assertEquals(12_000L, fixture.rawPosition)
    }

    @Test
    fun `a direct sleep focus or noisy pause restores the held point before retiring its probe`() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L

        fixture.externalPause()
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition, "non-controller pause must not discard unheard progress")
        assertEquals(listOf(true), fixture.compensationOrigins)
        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
    }

    internal class Fixture(scope: TestScope) {
        private val registry = LiveOutputEndpointRegistry()
        private val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 1, "Fixture headset")
        private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 2, "Fixture speaker")
        private val endpoints = registry.reconcile(
            listOf(headset, speaker).map {
                LiveOutputEndpointRegistry.Observation(it.id, it.type, it.productName?.toString())
            },
        )
        val outputId: String = endpoints.single { it.platformId == headset.id }.id
        val speakerId: String = endpoints.single { it.platformId == speaker.id }.id
        var rawPosition = 12_000L
        var volume = 1f
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var bufferOnSeek = false
        var command = 0L
        var pauses = 0
        var plays = 0
        var measurements = 0
        var routes: Set<String>? = setOf(outputId)
        var failBarrier = false
        var failPreference = false
        var projection = true
        var itemGeneration = 0L
        var profileGeneration = 0L
        var current: ExoPlayer? = null
        var observation: AudioTrackObservation? = AudioTrackObservation(1, AudioTrackPath.Endpoint(outputId))
        val messages = mutableListOf<PlayerMessage>()
        val playbackListeners = mutableListOf<Player.Listener>()
        val compensationOrigins = mutableListOf<Boolean>()
        val preferenceRequests = mutableListOf<AudioDeviceInfo?>()
        var item = MediaItem.Builder().setMediaId("checkpoint-fixture").build()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val gains = PlaybackVolumeEffects(TestAppClock())
        val media: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) {
                _,
                method,
                args,
            ->
            when (method.name) {
                "addListener" -> playbackListeners.add(args!![0] as Player.Listener).let { null }

                "removeListener" -> playbackListeners.remove(args!![0] as Player.Listener).let { null }

                "getCurrentPosition" -> rawPosition

                "getCurrentMediaItem" -> item

                "getMediaItemCount" -> 1

                "getDuration" -> 100_000L

                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                "getPlayWhenReady" -> playWhenReady

                "isPlaying" -> playWhenReady && playbackState == Player.STATE_READY

                "getPlaybackState" -> playbackState

                "setPreferredAudioDevice" -> {
                    check(!failPreference) { "synthetic preference failure" }
                    preferenceRequests += args!![0] as? AudioDeviceInfo
                    null
                }

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
                    if (bufferOnSeek) playbackState = Player.STATE_BUFFERING
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
        val router = AudioOutputRouter(
            RuntimeEnvironment.getApplication(),
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            scope.backgroundScope,
            dispatcher,
            registry,
            volumeEffects = gains,
        )
        val effects: MutedPositionEffects = MutedPositionEffects(
            router,
            object : MutedPositionEffects.Source {
                override fun player() = current
                override fun context() = MutedPositionEffects.Context(command, itemGeneration, profileGeneration)
            },
        )
        private val guard = field<HeadsetRouteGuard>(router, "headsetGuard")

        init {
            current = media
            gains.attach(media)
            AudioOutputRouter::class.java.getDeclaredField("player").apply { isAccessible = true }.set(router, media)
            AndroidLocalOutputAdapter::class.java.getDeclaredField("live").apply {
                isAccessible = true
            }.set(router.localOutputs, mapOf(outputId to headset, speakerId to speaker))
            field<MutableStateFlow<String?>>(router, "_selectedId").value = outputId
            AudioOutputRouter::class.java.getDeclaredField("currentTrackRoutes").apply { isAccessible = true }.set(
                router,
                {
                    measurements++
                    routes
                },
            )
            AudioOutputRouter::class.java.getDeclaredField("currentTrackObservation").apply { isAccessible = true }.set(
                router,
                { observation.takeIf { playWhenReady } },
            )
            router.currentProjection = { projection }
            guard.attachPlayer(media)
            guard.protect(outputId)
            effects.install()
            router.outputIntentOwner.onBookChanged(1)
            router.onActualTrackRouteChanged()
        }

        fun start() = assertTrue(guard.beginProbe(outputId, media, 0))
        fun prove() = guard.verifyProbeIfActive(setOf(outputId), emptySet(), true)
        fun activeProbe() = guard.currentProbe
        fun cancel() = guard.cancelProbe()
        fun deliver(index: Int) = messages[index].target.handleMessage(0, null)

        fun reportPlaybackState(state: Int) {
            playbackState = state
            playbackListeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }

        fun externalPause() {
            media.pause()
            guard.currentPlayerListener?.onPlayWhenReadyChanged(
                false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
        }

        fun playWithProtection(beforeRawPlay: () -> Unit): Boolean = PlaybackCommandEffects(
            currentPlayer = { current },
            itemGeneration = { itemGeneration },
            isAttached = { router.isAttachedPlayer(it) },
            protectOutput = { target, willStartAudio, holdForSeek ->
                router.protectManualPlayOnGuardedHeadset(target, willStartAudio, holdForSeek)
            },
        ).play(media, stillAuthorized = { true }, beforeRawPlay = beforeRawPlay)

        fun report(path: AudioTrackPath?, epoch: Long = 1) {
            observation = path?.let { AudioTrackObservation(epoch, it) }
            routes = when (path) {
                is AudioTrackPath.Endpoint -> setOf(path.id)
                null -> null
                AudioTrackPath.RemoteSubmix, AudioTrackPath.Unresolved, AudioTrackPath.Multiple -> emptySet()
            }
            router.onActualTrackRouteChanged()
        }

        fun replaceBook() {
            effects.retireForItemTransition()
            item = MediaItem.Builder().setMediaId("successor-fixture").build()
            itemGeneration++
            rawPosition = 5_000L
            val generation = (router.outputIntentOwner.state.value.activeBookGeneration ?: 0L) + 1
            router.outputIntentOwner.onBookChanged(generation)
        }

        private inline fun <reified T> field(target: Any, name: String): T =
            target.javaClass.getDeclaredField(name).run {
                isAccessible = true
                get(target) as T
            }

        private fun device(type: Int, id: Int, name: String): android.media.AudioDeviceInfo {
            val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
            val port = org.robolectric.util.ReflectionHelpers.getField<Any>(device, "mPort")
            org.robolectric.util.ReflectionHelpers.setField(port, "mName", name)
            val handle = org.robolectric.util.ReflectionHelpers.getField<Any>(port, "mHandle")
            org.robolectric.util.ReflectionHelpers.setField(handle, "mId", id)
            return device
        }
    }
}
