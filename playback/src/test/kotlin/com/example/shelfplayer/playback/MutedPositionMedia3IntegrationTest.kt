package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises muted position settling against pinned Media3 with a real silence media source. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MutedPositionMedia3IntegrationTest {
    @Test
    fun `real Media3 seek and message settle one advancing muted interval`() {
        val fixture = Fixture(Handler(Looper.getMainLooper()).asCoroutineDispatcher().immediate)
        try {
            fixture.prepareAndPlay()
            val heldPosition = fixture.player.currentPosition
            val probe = fixture.beginProbe()

            fixture.await("real ExoPlayer clock to advance while the route is muted") {
                fixture.player.currentPosition >= heldPosition + 100L
            }

            assertTrue(fixture.guard.finishProbe(probe, verified = true))
            fixture.await("muted verification to settle") { fixture.guard.currentProbe == null }

            assertEquals(listOf(MutedProbeCompletion.Verified), fixture.settledCompletions)
            assertEquals(1, fixture.seekReasons.size, "one proof cycle must issue one compensation seek")
            assertEquals(Player.DISCONTINUITY_REASON_SEEK, fixture.seekReasons.single())
            assertEquals(listOf(true), fixture.seekCallbacksDuringSeekTo)
            assertEquals(listOf(true), fixture.compensationSeekResults)
            assertEquals(1, fixture.playerMessageCount, "the real ExoPlayer playback thread must deliver the barrier")
            assertTrue(fixture.player.playWhenReady, "verification must preserve current Play intent")
            assertTrue(fixture.player.isPlaying, "successful verification must leave playback running")
            assertEquals(1f, fixture.player.volume)
            assertTrue(
                fixture.player.currentPosition in heldPosition..heldPosition + 50L,
                "the real media clock may advance only within the settling allowance after compensation",
            )
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val realPlayer by lazy {
            ExoPlayer.Builder(context).setLooper(Looper.getMainLooper())
                .setAudioOutputProvider(requireNotNull(monitor.providerForPlayer()))
                .build()
        }
        private var insideSeekTo = false

        @Volatile
        var playerMessageCount = 0
            private set

        private var measuredRoute: Set<String>? = null
        private val outputRegistry = LiveOutputEndpointRegistry()
        private val headset = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            .build()
        val outputId = outputRegistry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(headset.id, headset.type, "fixture")),
        ).single().id
        private val volumeEffects = PlaybackVolumeEffects(TestAppClock())
        private val router = AudioOutputRouter(
            context,
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            scope,
            dispatcher,
            outputRegistry,
            volumeEffects = volumeEffects,
        )
        private val lab = AudioRouteLab(context, EventLog(TestAppClock()), TestAppClock(), router, scope, dispatcher)
        private val monitor = AudioTrackRouteMonitor(context, lab, TestAppClock(), outputRegistry)
        val seekReasons = mutableListOf<Int>()
        val seekCallbacksDuringSeekTo = mutableListOf<Boolean>()
        val compensationSeekResults = mutableListOf<Boolean>()
        val settledCompletions = mutableListOf<MutedProbeCompletion>()

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            if (method.name == "seekTo") insideSeekTo = true
            try {
                val forwarded = args?.copyOf()
                if (method.name == "createMessage" && forwarded?.firstOrNull() is PlayerMessage.Target) {
                    val target = forwarded[0] as PlayerMessage.Target
                    forwarded[0] = PlayerMessage.Target { messageType, payload ->
                        playerMessageCount++
                        target.handleMessage(messageType, payload)
                    }
                }
                method.invoke(realPlayer, *forwarded.orEmpty())
            } finally {
                if (method.name == "seekTo") insideSeekTo = false
            }
        } as ExoPlayer

        val effects = MutedPositionEffects(
            router,
            object : MutedPositionEffects.Source {
                override fun player(): ExoPlayer = this@Fixture.player

                override fun context() = MutedPositionEffects.Context(0, 0, 0)
            },
        )
        val guard: HeadsetRouteGuard = field(router, "headsetGuard")

        init {
            lab.start()
            volumeEffects.attach(player)
            set(router, "player", player)
            set(router, "currentTrackRoutes", { measuredRoute })
            set(router.localOutputs, "live", mapOf(outputId to headset))
            set(router.localOutputs, "collidingOutputIds", emptySet<String>())
            field<kotlinx.coroutines.flow.MutableStateFlow<String?>>(router, "_selectedId").value = outputId
            guard.attachPlayer(player)
            guard.protect(outputId)
            measuredRoute = setOf(outputId)
            val settled = guard.onProbeSettled
            guard.onProbeSettled = { probe, result ->
                settled?.invoke(probe, result)
                settledCompletions += result
            }
            router.outputIntentOwner.onBookChanged(1)
            effects.install()
            player.addListener(
                object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                        ) {
                            seekReasons += reason
                            seekCallbacksDuringSeekTo += insideSeekTo
                            compensationSeekResults += effects.onSeek(player)
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException): Unit =
                        throw AssertionError("Media3 silence source failed", error)
                },
            )
        }

        fun prepareAndPlay() {
            player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000L).createMediaSource())
            player.prepare()
            await("real ExoPlayer READY") { player.playbackState == Player.STATE_READY }
            player.play()
            await("real ExoPlayer playing") { player.isPlaying }
            await("production AudioTrack provider attachment and timing") {
                lab.state.value.entries.any { "track-timing" in it }
            }
            assertTrue(lab.state.value.entries.any { "observer-audio-track-attached" in it })
            assertTrue(lab.state.value.entries.none { "provider-fallback" in it || "attach-failed" in it })
        }

        fun beginProbe(): HeadsetRouteGuard.Probe {
            val preferences: RoutePreferenceEffects = field(router, "preferenceEffects")
            assertTrue(guard.beginProbe(outputId, player, preferences.snapshot()))
            return requireNotNull(guard.currentProbe)
        }

        fun await(description: String, predicate: () -> Boolean) {
            val mainLooper = shadowOf(Looper.getMainLooper())
            repeat(5_000) {
                if (predicate()) return
                // Advance virtual time at the polling cadence, rather than racing the playback thread 10x.
                mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
                Thread.sleep(1L)
            }
            assertTrue(predicate(), "Timed out waiting for $description")
        }

        override fun close() {
            guard.cancelProbe()
            router.detach()
            player.release()
            lab.stop()
            scope.cancel()
        }

        private inline fun <reified T> field(target: Any, name: String): T =
            target.javaClass.getDeclaredField(name).run {
                isAccessible = true
                get(target) as T
            }

        private fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).run {
                isAccessible = true
                set(target, value)
            }
        }
    }
}
