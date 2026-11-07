package com.example.shelfplayer.playback

import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import java.time.ZoneId

/** Shared service dependencies for integration fixtures; scenario state remains with each fixture. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackServiceFixtureSetup(
    private val service: PlaybackService,
    private val router: AudioOutputRouter,
    private val registry: LiveOutputEndpointRegistry,
    private val player: ExoPlayer,
    private val volumeEffects: PlaybackVolumeEffects,
    private val scope: TestScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: TestAppClock,
    private val logger: Logger,
) {
    fun configure() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
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
        service.carProjection = AndroidAutoProjectionMonitor(context, dispatcher, logger, clock)
        service.coldStartToggleGate = ColdStartToggleGate()
        service.logger = logger
        service.applicationScope = scope.backgroundScope
        service.mainDispatcher = dispatcher
        service.audioOutputs = router
        service.audioRouteLab =
            AudioRouteLab(context, EventLog(clock), clock, router, scope.backgroundScope, dispatcher)
        service.volumeEffects = volumeEffects
        service.audioTrackRouteMonitor = AudioTrackRouteMonitor(
            context,
            service.audioRouteLab,
            clock,
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
}
