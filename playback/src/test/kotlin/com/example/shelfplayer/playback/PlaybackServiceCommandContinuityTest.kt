package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PLAY-001 / PLAY-002 / S12-S16: actual service forwarding/custom-command wiring must
 * revoke a suspended continuity transaction without relying on a raw Pause callback.
 * The service's production session-player factory wraps a real ExoPlayer. The fixture
 * supplies existing owners without running Hilt/service creation or claiming physical sound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceCommandContinuityTest {
    @Test
    fun `service protects an exact Automatic route before car arrival`() = runTest {
        Fixture(this).use { fixture ->
            fixture.observeMeasuredAutomaticRoute()
            assertEquals(
                fixture.endpointId,
                fixture.routerGuardId(),
                "the production service observer must reach router protection before car bind",
            )
        }
    }

    @Test
    fun `route return without a newer command reaches the production one-shot Play`() = runTest {
        Fixture(this).use { fixture ->
            fixture.beginRecovery()
            runCurrent()
            assertTrue(fixture.isRecoveryCurrent())
            assertFalse(fixture.player.playWhenReady)

            fixture.returnHeadset()
            runCurrent()

            assertTrue(fixture.player.playWhenReady, "the fixture must reach the actual raw Play effect")
            assertEquals(0f, fixture.player.volume, "a route request cannot unmute the playing track")
            assertFalse(fixture.isRecoveryCurrent(), "the one-shot claim is consumed")
        }
    }

    @Test
    fun `forwarding Seek revokes continuity while the raw player is already paused`() = runTest {
        assertCommandSupersedes {
            assertNull(forwarding.handleSeek(0, 5_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).get())
            assertEquals(5_000L, player.currentPosition)
        }
    }

    @Test
    fun `forwarding Stop revokes continuity without requiring a raw Pause transition`() = runTest {
        assertCommandSupersedes {
            assertNull(forwarding.handleStop().get())
            assertEquals(Player.STATE_IDLE, player.playbackState)
        }
    }

    @Test
    fun `forwarding media replacement cannot lend the old recovery to the new book`() = runTest {
        assertCommandSupersedes {
            assertNull(forwarding.handleSetMediaItems(listOf(item("replacement")), 0, 0L).get())
            assertEquals("replacement", player.currentMediaItem?.mediaId)
        }
    }

    @Test
    fun `custom notification skip revokes continuity before applying its seek`() = runTest {
        assertCommandSupersedes {
            skip(30.seconds)
            assertEquals(42_000L, player.currentPosition)
        }
    }

    @Test
    fun `custom notification skip also invalidates existing deferred media work`() = runTest {
        Fixture(this).use { fixture ->
            val deferred = DeferredPlaybackWork(
                currentPlayer = { fixture.player },
                commandGeneration = fixture::commandGeneration,
                itemGeneration = { 0 },
                profileGeneration = { 0 },
                acceptPrepared = { _, _, _ -> error("A skipped-over deferred claim must not install anything") },
            )
            val claim = requireNotNull(deferred.capture())
            assertTrue(deferred.isCurrent(claim))

            fixture.skip(30.seconds)

            assertFalse(deferred.isCurrent(claim), "custom skip is newer intent for every existing effect owner")
            assertEquals(42_000L, fixture.player.currentPosition)
        }
    }

    private fun TestScope.assertCommandSupersedes(command: Fixture.() -> Unit) {
        Fixture(this).use { fixture ->
            fixture.beginRecovery()
            runCurrent()
            assertTrue(fixture.isRecoveryCurrent(), "the exact headset wait must be pending before the command")
            val before = fixture.commandGeneration()

            fixture.command()
            runCurrent()

            assertTrue(fixture.commandGeneration() > before)
            assertEquals(0, fixture.rawPlayTransitions, "no raw transport callback can provide cancellation")
            fixture.returnHeadset()
            runCurrent()
            assertFalse(fixture.player.playWhenReady, "old continuity must not Play after newer listener intent")
            assertFalse(fixture.isRecoveryCurrent())
            assertEquals(1f, fixture.player.volume, "cancelled recovery must not start a muted probe")
        }
    }

    private class Fixture(private val scope: TestScope) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val clock = TestAppClock()
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        private val registry = LiveOutputEndpointRegistry()
        private val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        private val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(device.id, device.type, device.productName?.toString())),
        ).single()
        private val headset = endpoint.output.copy(id = endpoint.id, isActive = true)
        val endpointId: String get() = endpoint.id
        private val service = PlaybackService()
        val player = ExoPlayer.Builder(context).build()
        private val router = AudioOutputRouter(context, logger, scope.backgroundScope, dispatcher, registry)
        private val owner: RouteHeardOwnership get() = service.field("routeOwnership")
        private val recovery: CarContinuityRecoveryCoordinator get() = service.field("carRecovery")
        private val effects: CarContinuityResumeEffects get() = service.call(
            "getCarResumeEffects",
        ) as CarContinuityResumeEffects
        private val target: CarArrivalResumeGate.Target
        val forwarding: ResumeFreshnessPlayer
        var rawPlayTransitions = 0
            private set

        init {
            player.setMediaItem(item("original"))
            player.seekTo(12_000L)
            player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    rawPlayTransitions++
                }
            })
            configureService()
            configureRouter()
            owner.onBookChanged(hasBook = true)
            owner.onPlaybackObserved(listOf(headset))
            val inputs = CarContinuityRecoveryCoordinator.Inputs(
                owner.heardRoute,
                headset.id,
                owner.currentGeneration,
                0,
                carConnected = false,
            )
            recovery.observePlayingHeadset(inputs)
            recovery.platformInterruption(10.seconds, inputs, focusLoss = true)
            target = requireNotNull(recovery.carArrival(11.seconds, owner.currentGeneration, 0).target)
            forwarding = service.createSessionPlayer(player)
        }

        private fun configureService() {
            val baseline = ResumeBaseline()
            val sync = SessionSyncCoordinator(
                unused<SessionSyncRepository>(),
                baseline,
                clock,
                logger,
                applicationScope = scope.backgroundScope,
                mainDispatcher = dispatcher,
            )
            service.clock = clock
            service.logger = logger
            service.applicationScope = scope.backgroundScope
            service.mainDispatcher = dispatcher
            service.audioOutputs = router
            service.carConnections = CarConnections(clock)
            service.resumeFreshness = ResumeFreshnessCoordinator(
                unused<PlaybackRepository>(),
                unused<ProfileRepository>(),
                baseline,
                RealtimeProgressEvidenceStore(),
                logger,
                scope.backgroundScope,
                dispatcher,
            )
            service.sleepTimer = sleepTimer(sync)
            service.setField("scope", scope.backgroundScope)
            service.setField("player", player)
        }

        private fun sleepTimer(sync: SessionSyncCoordinator) = SleepTimerController(
            repository = proxy<SleepTimerRepository> { method ->
                when (method) {
                    "observeSettings" -> flowOf(SleepTimerSettings.Default)
                    else -> error("Unexpected sleep repository call: $method")
                }
            },
            shakes = proxy<ShakeSource> { method ->
                when (method) {
                    "isSensing" -> false
                    else -> error("Unexpected shake call: $method")
                }
            },
            sessionSync = sync,
            history = unused<PlaybackHistoryRepository>(),
            clock = clock,
            zoneProvider = object : LocalZoneProvider {
                override fun current(): ZoneId = ZoneId.of("UTC")
            },
            logger = logger,
            applicationScope = scope.backgroundScope,
            mainDispatcher = dispatcher,
        )

        private fun configureRouter() {
            router.setField("player", player)
            router.setField("live", emptyMap<String, AudioDeviceInfo>())
            router.setField("guardedHeadsetId", headset.id)
            router.field<MutableStateFlow<String?>>("_selectedId").value = headset.id
        }

        fun observeMeasuredAutomaticRoute() {
            val measured = headset.copy(
                activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            )
            router.setField("live", mapOf(endpoint.id to device))
            router.setField("currentTrackRoutes", { setOf(endpoint.id) })
            router.setField("guardedHeadsetId", null)
            router.field<MutableStateFlow<String?>>("_selectedId").value = null
            router.field<MutableStateFlow<List<AudioOutput>>>("_outputs").value = listOf(measured)
            owner.onBookChanged(hasBook = true)
            owner.onPlaybackObserved(listOf(measured))
            service.call("observeCarContinuityHeadset", List::class.java, listOf(measured))
        }

        fun routerGuardId(): String? = router.field<HeadsetRouteGuard>("headsetGuard")
            .field<String?>("endpointId")

        fun beginRecovery() = effects.onRecoveryReady(target)

        fun isRecoveryCurrent(): Boolean = recovery.isCurrent(target, owner.currentGeneration, 0)

        fun commandGeneration(): Long = service.field("controllerCommandGeneration")

        fun skip(delta: Duration) {
            // Kotlin's inline Duration is a mangled JVM long parameter on this private service seam.
            val value = Duration::class.java.getDeclaredMethod("unbox-impl").invoke(delta)
            val method = PlaybackService::class.java.declaredMethods.single { it.name.startsWith("skipBy-") }
            method.isAccessible = true
            method.invoke(service, value)
        }

        fun returnHeadset() {
            router.setField("live", mapOf(headset.id to device))
            router.field<MutableStateFlow<List<AudioOutput>>>("_outputs").value = listOf(headset)
        }

        override fun close() {
            effects.cancelPending()
            router.detach()
            player.release()
        }
    }

    private companion object {
        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri("file:///fixture").build()

        inline fun <reified T> unused(): T = proxy { error("Unexpected repository call: $it") }

        inline fun <reified T> proxy(crossinline result: (String) -> Any?): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> result(method.name) } as T

        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@field) as T
        }

        fun Any.setField(name: String, value: Any?) {
            if (this is AudioOutputRouter && name == "player") {
                val guard = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
                    isAccessible = true
                    get(this@setField) as HeadsetRouteGuard
                }
                guard.detachPlayer()
                if (value is ExoPlayer) guard.attachPlayer(value)
            }
            if (this is AudioOutputRouter && name == "guardedHeadsetId") {
                val owner = AudioOutputRouter::class.java.getDeclaredField("headsetGuard").run {
                    isAccessible = true
                    get(this@setField) as HeadsetRouteGuard
                }
                if (value == null) owner.release() else owner.protect(value as String)
                return
            }
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }

        fun Any.call(name: String): Any? = javaClass.getDeclaredMethod(name).run {
            isAccessible = true
            invoke(this@call)
        }

        fun Any.call(name: String, parameterType: Class<*>, argument: Any?): Any? =
            javaClass.getDeclaredMethod(name, parameterType).run {
                isAccessible = true
                invoke(this@call, argument)
            }
    }
}
