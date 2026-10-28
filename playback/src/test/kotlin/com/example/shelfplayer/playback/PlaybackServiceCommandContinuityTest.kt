package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
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
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `forwarding Stop releases the lock even when Media3 retains the loaded queue`() = runTest {
        Fixture(this).use { fixture ->
            val generation = fixture.armLogicalLock()
            assertEquals(1, fixture.player.mediaItemCount)

            assertNull(fixture.forwarding.handleStop().get())

            assertNull(fixture.currentLock())
            assertEquals(1, fixture.player.mediaItemCount, "Stop does not depend on a queue-clear callback")
            assertEquals(generation, fixture.currentIntentGeneration(), "retained queue still owns its context")
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `actual service car-controller Play preserves a lock and uses the shared intent owner`() = runTest {
        Fixture(this).use { it.checkLockedCarControllerPlay() }
    }

    @Test
    fun `route safety pause cannot become listener auto-rewind intent`() = runTest {
        Fixture(this).use { fixture -> fixture.checkRouteSafetyPauseReason() }
    }

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
    fun `guarded local resume refuses a colliding endpoint without raw Play`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)

            assertFalse(fixture.resumeLoadedCurrent())
            assertFalse(fixture.resumeLoadedCurrent(), "repeated denial must remain fail closed")

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume, "a denied probe must not claim or change volume")
        }
    }

    @Test
    fun `guarded local resume refuses a missing selection without raw Play`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = false, colliding = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `guarded local resume refuses an absent endpoint without raw Play`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false, connected = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `a logical output lock cannot bypass protection when its guard is absent`() = runTest {
        Fixture(this).use { fixture ->
            fixture.armLogicalLock()
            fixture.configureNoManualGuard()

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertNotNull(fixture.currentLock())
        }
    }

    @Test
    @Config(sdk = [32])
    fun `guarded local resume refuses when measured route support is unavailable`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `guarded local resume makes one muted Play transition when the probe can start`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)

            assertTrue(fixture.resumeLoadedCurrent())
            assertTrue(fixture.player.playWhenReady)
            assertEquals(0f, fixture.player.volume, "the allowed command starts under the route mute")
            assertEquals(1, fixture.rawPlayTransitions, "one PlayWhenReady transport transition")
            assertEquals(1, fixture.rawPlayCalls, "one accepted request issues exactly one raw Play")

            assertTrue(fixture.resumeLoadedCurrent(), "an already-requested Play remains idempotent")
            assertEquals(1, fixture.rawPlayTransitions, "repeated Play must not add a transport transition")
            assertEquals(2, fixture.rawPlayCalls, "the second accepted request also issues only one raw Play")
            assertEquals(0f, fixture.player.volume)
        }
    }

    @Test
    fun `guarded retained Play intent cannot prepare an idle player before route proof`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.retainPlayIntentWhileIdle()
            val states = fixture.observePlaybackStates()
            val transitionsBeforeResume = fixture.rawPlayTransitions

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(fixture.player.playWhenReady, "the pre-existing intent stays visible but does not prepare")
            assertFalse(
                states.any { it.state != Player.STATE_IDLE && it.playWhenReady && it.volume > 0f },
                "no state transition may prepare requested playback while the guarded route is unmuted",
            )
            assertEquals(transitionsBeforeResume, fixture.rawPlayTransitions)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `unguarded retained Play intent can prepare an idle player`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureNoManualGuard()
            fixture.retainPlayIntentWhileIdle()
            val states = fixture.observePlaybackStates()

            assertTrue(fixture.resumeLoadedCurrent())

            assertTrue(fixture.player.playWhenReady)
            assertTrue(
                states.any { it.state != Player.STATE_IDLE && it.playWhenReady && it.volume == 1f },
                "ordinary local preparation remains available without an output guard",
            )
        }
    }

    @Test
    fun `new Car choice during mute notification cancels the retained resume`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            fixture.retainPlayIntentWhileIdle()
            val states = fixture.observePlaybackStates()
            val transitionsBeforeResume = fixture.rawPlayTransitions
            fixture.chooseCarOnNextRouteMute()

            assertFalse(fixture.resumeLoadedCurrent())
            runCurrent()

            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(fixture.player.playWhenReady, "the newer route choice does not erase existing transport intent")
            assertEquals(transitionsBeforeResume, fixture.rawPlayTransitions)
            assertFalse(
                states.any { it.state != Player.STATE_IDLE },
                "the old request must not prepare after the synchronous newer destination choice",
            )
            assertFalse(fixture.isRouteMuted(), "the superseded probe must release its route-mute claim")
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    @Config(sdk = [32])
    fun `ordinary local resume without a guarded destination remains available`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureNoManualGuard()

            assertTrue(fixture.resumeLoadedCurrent())

            assertTrue(fixture.player.playWhenReady)
            assertEquals(1, fixture.rawPlayTransitions)
            assertEquals(1f, fixture.player.volume)
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
            assertEquals(0, fixture.rawPlayCalls, "a newer command must prevent even a no-op raw Play")
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
            .apply {
                val port = org.robolectric.util.ReflectionHelpers.getField<Any>(this, "mPort")
                org.robolectric.util.ReflectionHelpers.setField(port, "mName", "Fixture headset")
            }
        private val endpoint = registry.reconcile(
            listOf(LiveOutputEndpointRegistry.Observation(device.id, device.type, device.productName?.toString())),
        ).single()
        private val headset = endpoint.output.copy(id = endpoint.id, isActive = true)
        val endpointId: String get() = endpoint.id
        private val service = PlaybackService()
        var rawPlayCalls = 0
            private set
        private val realPlayer = ExoPlayer.Builder(context).build()

        // Observe commands while retaining one real pinned Media3 player and all its normal callbacks.
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            if (method.name == "play") rawPlayCalls += 1
            method.invoke(realPlayer, *args.orEmpty())
        } as ExoPlayer
        private val volumeEffects = volumeEffectsForTest(dispatcher, clock)
        private val router = AudioOutputRouter(
            context,
            logger,
            scope.backgroundScope,
            dispatcher,
            registry,
            volumeEffects = volumeEffects,
        )
        private val owner: RouteHeardOwnership get() = service.routeOwnership
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
                volumeEffects = volumeEffects,
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

        private fun sleepTimer(sync: SessionSyncCoordinator): SleepTimerController {
            val timers = proxy<SleepTimerRepository> { method ->
                when (method) {
                    "observeSettings" -> flowOf(SleepTimerSettings.Default)
                    else -> error("Unexpected sleep repository call: $method")
                }
            }
            return SleepTimerController(
                repository = timers,
                shakes = proxy<ShakeSource> { method ->
                    when (method) {
                        "isSensing" -> false
                        else -> error("Unexpected shake call: $method")
                    }
                },
                volumeEffects = volumeEffects,
                history = SleepTimerHistory(
                    timers,
                    unused<PlaybackHistoryRepository>(),
                    sync,
                    clock,
                    scope.backgroundScope,
                ),
                clock = clock,
                zoneProvider = object : LocalZoneProvider {
                    override fun current(): ZoneId = ZoneId.of("UTC")
                },
                logger = logger,
                applicationScope = scope.backgroundScope,
                mainDispatcher = dispatcher,
            )
        }

        private fun configureRouter() {
            volumeEffects.attach(player)
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

        fun checkRouteSafetyPauseReason() {
            var recordedEvents = 0
            service.history = proxy<PlaybackHistoryRepository> { method ->
                check(method.substringBefore('-') == "record") { "Unexpected history call: $method" }
                recordedEvents += 1
                Unit
            }
            service.autoRewind = AutoRewindController(
                proxy<PlaybackSettingsRepository> { method ->
                    when (method) {
                        "observeSettings" -> flowOf(PlaybackSettings())
                        else -> error("Unexpected playback settings call: $method")
                    }
                },
                clock,
                unused<PlaybackHistoryRepository>(),
                logger,
                scope.backgroundScope,
            )
            val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
            val listener = type.getDeclaredConstructor(PlaybackService::class.java)
                .apply { isAccessible = true }.newInstance(service) as Player.Listener
            var callbackWasSafetyPause = false
            val originListener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) callbackWasSafetyPause = router.isRouteSafetyPause(player)
                }
            }
            player.addListener(listener)
            player.addListener(originListener)
            player.playWhenReady = true
            scope.runCurrent()
            val beforeSafetyPause = recordedEvents
            service.autoRewind.onPaused(wasUserInitiated = true)

            router.field<HeadsetRouteGuard>("headsetGuard").pauseForSafety(player)
            scope.runCurrent()

            assertTrue(callbackWasSafetyPause, "pinned Media3 must deliver the pause callback in its effect scope")
            assertNull(service.autoRewind.field<Any?>("pausedAt"), "a safety pause must not prime auto-rewind")
            assertFalse(router.isRouteSafetyPause(player), "effect provenance must not outlive the pause call")
            assertEquals(beforeSafetyPause, recordedEvents, "route safety must not fabricate listener Pause history")
            player.playWhenReady = true
            player.pause()
            scope.runCurrent()
            assertNotNull(
                service.autoRewind.field<Any?>("pausedAt"),
                "a later listener pause still primes auto-rewind",
            )
            assertFalse(callbackWasSafetyPause, "the later listener pause must have no safety-effect provenance")
            assertEquals(beforeSafetyPause + 2, recordedEvents, "the later real Play and Pause remain in history")
            player.removeListener(originListener)
            player.removeListener(listener)
        }

        fun configureManualGuard(selected: Boolean, colliding: Boolean, connected: Boolean = true) {
            if (connected) returnHeadset() else router.setField("live", emptyMap<String, AudioDeviceInfo>())
            router.setField("guardedHeadsetId", endpointId)
            router.field<MutableStateFlow<String?>>("_selectedId").value = if (selected) endpointId else null
            router.setField("collidingOutputIds", if (colliding) setOf(endpointId) else emptySet<String>())
        }

        fun configureNoManualGuard() {
            router.setField("guardedHeadsetId", null)
            router.field<MutableStateFlow<String?>>("_selectedId").value = null
        }

        suspend fun resumeLoadedCurrent(): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = PlaybackService::class.java.declaredMethods.single {
                it.name == "resumeLoadedCurrent"
            }.apply { isAccessible = true }
            val result = method.invoke(service, { true }, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as Boolean
        }

        fun chooseCarOnNextRouteMute() {
            var selected = false
            volumeEffects.addMuteListener { change ->
                if (!selected && change.player === player && change.muted) {
                    selected = true
                    router.selectCar()
                }
            }
        }

        fun explicitDestination(): OutputIntentOwner.Destination? = router.explicitSelection.value?.destination

        fun isRouteMuted(): Boolean = volumeEffects.isRouteMuted(player)
        fun retainPlayIntentWhileIdle() {
            assertEquals(Player.STATE_IDLE, player.playbackState)
            player.playWhenReady = true
            assertTrue(player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        }

        fun observePlaybackStates(): MutableList<PlaybackStateSample> =
            mutableListOf<PlaybackStateSample>().also { states ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        states += PlaybackStateSample(playbackState, player.playWhenReady, player.volume)
                    }
                })
            }

        data class PlaybackStateSample(val state: Int, val playWhenReady: Boolean, val volume: Float)
        fun routerGuardId(): String? = router.field<HeadsetRouteGuard>("headsetGuard")
            .field<String?>("endpointId")

        fun armLogicalLock(): Long {
            val generation = requireNotNull(owner.currentGeneration)
            owner.intentOwner.keepHere(endpointId, generation, 0)
            return generation
        }

        fun currentLock(): OutputIntentOwner.Lock? = router.outputLock.value

        fun currentIntentGeneration(): Long? = router.outputIntentOwner.state.value.activeBookGeneration

        fun checkLockedCarControllerPlay() {
            assertTrue(owner.intentOwner === router.outputIntentOwner, "service and router must share intent")
            val lock = owner.intentOwner.keepHere(endpointId, requireNotNull(owner.currentGeneration), 0)
            val session = MediaLibraryService.MediaLibrarySession.Builder(
                context,
                player,
                object : MediaLibraryService.MediaLibrarySession.Callback {},
            ).build()
            service.setField("session", session)
            try {
                val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                    "com.google.android.projection.gearhead",
                    0,
                    0,
                    1,
                    1,
                    false,
                    Bundle.EMPTY,
                    false,
                )
                // Exercise the pinned Media3 request-context seam, rather than bypassing service routing.
                val impl = MediaSession::class.java.getDeclaredField("impl").run {
                    isAccessible = true
                    get(session)
                }
                Class.forName("androidx.media3.session.MediaSessionImpl")
                    .getDeclaredField("controllerForCurrentRequest").apply { isAccessible = true }.set(impl, info)
                assertEquals(info, session.controllerForCurrentRequest)
                val routePlay = PlaybackService::class.java.getDeclaredMethod(
                    "routeExplicitCarPlay",
                    Boolean::class.javaPrimitiveType,
                    ExoPlayer::class.java,
                ).apply { isAccessible = true }

                routePlay.invoke(service, true, player)

                assertEquals(lock, router.outputLock.value, "generic Auto Play must retain the lock")
                assertEquals(lock.selectionSequence, router.explicitSelection.value?.sequence)
                router.select(null)
                routePlay.invoke(service, true, player)
                assertEquals(OutputIntentOwner.Destination.Car, router.explicitSelection.value?.destination)
                assertNull(router.outputLock.value)
                assertFalse(player.playWhenReady, "adapting route intent must not manufacture Play")
            } finally {
                service.setField("session", null)
                session.release()
            }
        }

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
