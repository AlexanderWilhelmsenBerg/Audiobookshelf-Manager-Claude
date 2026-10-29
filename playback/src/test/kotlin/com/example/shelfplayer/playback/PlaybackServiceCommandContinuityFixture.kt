package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.robolectric.RuntimeEnvironment
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

internal fun playbackFixtureItem(id: String): MediaItem =
    MediaItem.Builder().setMediaId(id).setUri("file:///fixture").build()

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackServiceCommandContinuityFixture(private val scope: TestScope) : AutoCloseable {
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
    val commands: PlaybackCommands get() = service.playbackCommandEffects
    var rawPlayCalls = 0
        private set
    var rawPrepareCalls = 0
        private set
    private val selectionSupport by lazy {
        PlaybackServiceSelectionFixtureSupport(
            context,
            dispatcher,
            scope.backgroundScope,
            clock,
            logger,
            service,
            player,
            volumeEffects,
            ::selectCar,
        )
    }
    val postConnectSessionOpens: Int get() = selectionSupport.postConnectSessionOpens
    val capturedJournalBookId: LibraryItemId? get() = selectionSupport.capturedJournalBookId
    val capturedJournalOwner: ProfileId? get() = selectionSupport.capturedJournalOwner
    val capturedJournalPosition: Duration? get() = selectionSupport.capturedJournalPosition
    val pauseSyncTriggers: List<SyncTrigger> get() = selectionSupport.pauseSyncTriggers
    val syncedPositions: List<Duration> get() = selectionSupport.syncedPositions
    private var rawPauseRequests = 0
    val queueMutationIntents = mutableListOf<Boolean>()
    private val realPlayer = ExoPlayer.Builder(context).build()
    private var reportedPositionMs: Long? = null

    // Observe commands while retaining one real pinned Media3 player and all its normal callbacks.
    val player = java.lang.reflect.Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { _, method, args ->
        if (method.name in setOf(
                "setMediaItem",
                "setMediaItems",
                "addMediaItem",
                "addMediaItems",
                "replaceMediaItem",
                "replaceMediaItems",
                "removeMediaItem",
                "removeMediaItems",
            )
        ) {
            queueMutationIntents += realPlayer.playWhenReady
        }
        if (method.name == "play") rawPlayCalls += 1
        if (method.name == "prepare") rawPrepareCalls += 1
        if (method.name == "setPlayWhenReady" && args?.firstOrNull() == false) rawPauseRequests++
        if (method.name == "seekTo" && reportedPositionMs != null) {
            reportedPositionMs = args?.lastOrNull() as? Long
        }
        if (method.name == "getCurrentPosition" &&
            reportedPositionMs != null
        ) {
            return@newProxyInstance reportedPositionMs
        }
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
    private val recovery: CarContinuityRecoveryCoordinator get() = service.playbackField("carRecovery")
    private val effects: CarContinuityResumeEffects get() = service.playbackCall(
        "getCarResumeEffects",
    ) as CarContinuityResumeEffects
    private val target: CarArrivalResumeGate.Target
    val forwarding: ResumeFreshnessPlayer
    var rawPlayTransitions = 0
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                service.setPlaybackField("mediaItemGeneration", service.playbackField<Long>("mediaItemGeneration") + 1L)
            }
        })
        player.setMediaItem(playbackFixtureItem("original"))
        player.seekTo(12_000L)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                rawPlayTransitions++
            }
        })
        configureService()
        configureRouter()
        service.installPositionCheckpointHandlers()
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

    suspend fun configureCapturedPauseSync(
        recordEntered: CompletableDeferred<Unit>,
        releaseRecord: CompletableDeferred<Unit>,
    ) = selectionSupport.configureCapturedPauseSync(recordEntered, releaseRecord)

    fun dispatchPausedPlayerEvent() = selectionSupport.dispatchPausedPlayerEvent()

    fun invokePostConnect(selectCar: Boolean) = selectionSupport.invokePostConnect(selectCar)

    fun configurePostConnectRace() = selectionSupport.configurePostConnectRace()

    private fun configureService() {
        val baseline = ResumeBaseline()
        val sync = SessionSyncCoordinator(
            unusedPlaybackDependency<SessionSyncRepository>(),
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
        service.volumeEffects = volumeEffects
        service.audioTrackRouteMonitor = AudioTrackRouteMonitor(
            context,
            AudioRouteLab(context, EventLog(clock), clock, router, scope.backgroundScope, dispatcher),
            registry,
        )
        service.setPlaybackField("activeProfileGeneration", { 0L })
        service.carConnections = CarConnections(clock)
        service.resumeFreshness = ResumeFreshnessCoordinator(
            unusedPlaybackDependency<PlaybackRepository>(),
            unusedPlaybackDependency<ProfileRepository>(),
            baseline,
            RealtimeProgressEvidenceStore(),
            logger,
            scope.backgroundScope,
            dispatcher,
        )
        service.sleepTimer = sleepTimer(sync)
        service.sessionSync = sync
        service.resumeBaseline = baseline
        service.setPlaybackField("scope", scope.backgroundScope)
        service.setPlaybackField("player", player)
    }

    private fun sleepTimer(sync: SessionSyncCoordinator): SleepTimerController {
        val timers = playbackDependencyProxy<SleepTimerRepository> { method ->
            when (method) {
                "observeSettings" -> flowOf(SleepTimerSettings.Default)
                else -> error("Unexpected sleep repository call: $method")
            }
        }
        return SleepTimerController(
            repository = timers,
            shakes = playbackDependencyProxy<ShakeSource> { method ->
                when (method) {
                    "isSensing" -> false
                    else -> error("Unexpected shake call: $method")
                }
            },
            volumeEffects = volumeEffects,
            history = SleepTimerHistory(
                timers,
                unusedPlaybackDependency<PlaybackHistoryRepository>(),
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
        router.setPlaybackField("player", player)
        router.setPlaybackField("live", emptyMap<String, AudioDeviceInfo>())
        router.setPlaybackField("guardedHeadsetId", headset.id)
        router.playbackField<MutableStateFlow<String?>>("_selectedId").value = headset.id
    }

    fun observeMeasuredAutomaticRoute() {
        val measured = headset.copy(
            activitySource = AudioOutputActivitySource.PlayingAudioTrack,
        )
        router.setPlaybackField("live", mapOf(endpoint.id to device))
        router.setPlaybackField("currentTrackRoutes", { setOf(endpoint.id) })
        router.setPlaybackField("guardedHeadsetId", null)
        router.playbackField<MutableStateFlow<String?>>("_selectedId").value = null
        router.playbackField<MutableStateFlow<List<AudioOutput>>>("_outputs").value = listOf(measured)
        owner.onBookChanged(hasBook = true)
        owner.onPlaybackObserved(listOf(measured))
        service.playbackCall("observeCarContinuityHeadset", List::class.java, listOf(measured))
    }

    fun checkRouteSafetyPauseReason() {
        var recordedEvents = 0
        service.history = playbackDependencyProxy<PlaybackHistoryRepository> { method ->
            check(method.substringBefore('-') == "record") { "Unexpected history call: $method" }
            recordedEvents += 1
            Unit
        }
        service.autoRewind = AutoRewindController(
            playbackDependencyProxy<PlaybackSettingsRepository> { method ->
                when (method) {
                    "observeSettings" -> flowOf(PlaybackSettings())
                    else -> error("Unexpected playback settings call: $method")
                }
            },
            clock,
            unusedPlaybackDependency<PlaybackHistoryRepository>(),
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

        router.playbackField<HeadsetRouteGuard>("headsetGuard").pauseForSafety(player)
        scope.runCurrent()

        assertTrue(callbackWasSafetyPause, "pinned Media3 must deliver the pause callback in its effect scope")
        assertNull(service.autoRewind.playbackField<Any?>("pausedAt"), "a safety pause must not prime auto-rewind")
        assertFalse(router.isRouteSafetyPause(player), "effect provenance must not outlive the pause call")
        assertEquals(beforeSafetyPause, recordedEvents, "route safety must not fabricate listener Pause history")
        player.playWhenReady = true
        player.pause()
        scope.runCurrent()
        assertNotNull(
            service.autoRewind.playbackField<Any?>("pausedAt"),
            "a later listener pause still primes auto-rewind",
        )
        assertFalse(callbackWasSafetyPause, "the later listener pause must have no safety-effect provenance")
        assertEquals(beforeSafetyPause + 2, recordedEvents, "the later real Play and Pause remain in history")
        player.removeListener(originListener)
        player.removeListener(listener)
    }

    suspend fun checkCompensationSeekProvenance(inlineUserSeek: Boolean = false) {
        var positionWrites = 0
        service.playbackRepository = playbackDependencyProxy<PlaybackRepository> { method ->
            check(method.substringBefore('-') == "recordPosition")
            positionWrites++
            AppResult.Success(Unit)
        }
        service.autoRewind = AutoRewindController(
            playbackDependencyProxy<PlaybackSettingsRepository> { flowOf(PlaybackSettings()) },
            clock,
            unusedPlaybackDependency<PlaybackHistoryRepository>(),
            logger,
            scope.backgroundScope,
        )
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
        val events = type.getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }.newInstance(service) as Player.Listener
        var inlineDelivered = false
        val seeks = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                events.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (inlineUserSeek && !inlineDelivered && volumeEffects.isRouteMuted(player)) {
                    inlineDelivered = true
                    forwarding.handleSeek(0, 14_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }
            }
        }
        player.addListener(seeks)
        try {
            configureManualGuard(selected = true, colliding = false)
            assertTrue(resumeLoadedCurrent())
            reportPositionWithoutSeek(25_000L)
            forwarding.handleSetPlayWhenReady(false)
            scope.runCurrent()
            if (inlineUserSeek) {
                assertTrue(inlineDelivered, "the actual Media3 callback must issue the newer command")
                assertEquals(14_000L, player.currentPosition)
                assertEquals(1, positionWrites, "the newer user seek cannot inherit compensation provenance")
                assertEquals(0, rawPauseRequests, "the superseded outer Pause must not reach raw transport")
                return
            }
            assertEquals(0, positionWrites, "compensation must not create SeekCompleted persistence")
            assertEquals(12_000L, player.currentPosition)

            forwarding.handleSeek(0, 12_500L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            scope.runCurrent()
            assertEquals(1, positionWrites, "the next actual user seek must remain visible")
            forwarding.handleSeek(0, 12_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            scope.runCurrent()
            assertEquals(2, positionWrites, "the former held position must not retain compensation provenance")
        } finally {
            player.removeListener(seeks)
        }
    }

    fun configureManualGuard(selected: Boolean, colliding: Boolean, connected: Boolean = true) {
        if (connected) returnHeadset() else router.setPlaybackField("live", emptyMap<String, AudioDeviceInfo>())
        router.setPlaybackField("guardedHeadsetId", endpointId)
        router.playbackField<MutableStateFlow<String?>>("_selectedId").value = if (selected) endpointId else null
        router.setPlaybackField("collidingOutputIds", if (colliding) setOf(endpointId) else emptySet<String>())
    }

    fun configureNoManualGuard() {
        router.setPlaybackField("guardedHeadsetId", null)
        router.playbackField<MutableStateFlow<String?>>("_selectedId").value = null
    }

    fun checkCurrentItemMutation(change: (ResumeFreshnessPlayer) -> Unit) {
        configureManualGuard(selected = true, colliding = true)
        player.addMediaItem(playbackFixtureItem("future"))
        retainPlayIntentWhileIdle()
        queueMutationIntents.clear()

        change(forwarding)

        assertEquals(listOf(false), queueMutationIntents, "raw replacement cannot inherit unverified Play intent")
        assertFalse(player.playWhenReady)
        assertFalse(isRouteMuted(), "paused replacement does not need to start a silent playing probe")
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

    fun activeTransferDestination(): OutputIntentOwner.Destination? =
        router.positions.guard.currentProbe?.transferRequest?.selection?.destination

    fun explicitSelectionSequence(): Long = router.explicitSelection.value?.sequence ?: 0L

    fun selectCar() = router.selectCar()

    fun isRouteMuted(): Boolean = volumeEffects.isRouteMuted(player)
    fun hasPositionCheckpoint(): Boolean = router.positions.guard.positionCheckpoint != null
    fun observeMuteEdgePosition(onMuted: () -> Unit) {
        volumeEffects.addMuteListener { change ->
            if (change.player === player && change.muted) onMuted()
        }
    }
    fun reportPositionWithoutSeek(positionMs: Long) {
        reportedPositionMs = positionMs
    }
    fun positionSnapshotPosition(): Long? = service.playbackCall("positionSnapshot")?.playbackField("position")
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
    fun routerGuardId(): String? = router.playbackField<HeadsetRouteGuard>("headsetGuard")
        .playbackField<String?>("endpointId")

    fun armFreshStartForCurrentItem() {
        val profile = ProfileId("fresh-profile")
        val bookId = LibraryItemId("fresh-book")
        val extras = Bundle().apply { putString(MediaItems.KEY_OWNER_PROFILE_ID, profile.value) }
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(bookId.value)
                .setUri("file:///fixture")
                .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
                .build(),
        )
        service.resumeFreshness.attach(player)
        service.resumeFreshness.onSessionOpenedImmediately(
            PlaybackSession(
                id = "fresh-session",
                profileId = profile,
                bookId = bookId,
                title = "Fresh",
                author = null,
                coverUrl = null,
                startAt = Duration.ZERO,
                duration = Duration.ZERO,
                tracks = emptyList(),
                chapters = emptyList(),
            ),
            initialPlayWillFollow = true,
        )
        service.resumeFreshness.invalidate(ResumeInvalidation.MediaChanged)
    }
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
        fromCarController {
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
        }
    }

    fun <T> fromCarController(request: () -> T): T {
        val session = MediaLibraryService.MediaLibrarySession.Builder(
            context,
            player,
            object : MediaLibraryService.MediaLibrarySession.Callback {},
        ).build()
        service.setPlaybackField("session", session)
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
            return request()
        } finally {
            service.setPlaybackField("session", null)
            session.release()
        }
    }

    fun beginRecovery() = effects.onRecoveryReady(target)

    fun isRecoveryCurrent(): Boolean = recovery.isCurrent(target, owner.currentGeneration, 0)

    fun isRecoveryPlayAwaiting(): Boolean = recovery.isPlayAwaiting

    fun stopOnFirstRawPlay() {
        var stopped = false
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && !stopped) {
                    stopped = true
                    assertNull(forwarding.handleStop().get())
                }
            }
        })
    }

    fun commandGeneration(): Long = service.playbackField("controllerCommandGeneration")

    fun serviceItemGeneration(): Long = service.playbackField("mediaItemGeneration")

    fun skip(delta: Duration) {
        // Kotlin's inline Duration is a mangled JVM long parameter on this private service seam.
        val value = Duration::class.java.getDeclaredMethod("unbox-impl").invoke(delta)
        val method = PlaybackService::class.java.declaredMethods.single { it.name.startsWith("skipBy-") }
        method.isAccessible = true
        method.invoke(service, value)
    }

    fun returnHeadset() {
        router.setPlaybackField("live", mapOf(headset.id to device))
        router.playbackField<MutableStateFlow<List<AudioOutput>>>("_outputs").value = listOf(headset)
    }

    override fun close() {
        effects.cancelPending()
        router.detach()
        player.release()
    }
}
