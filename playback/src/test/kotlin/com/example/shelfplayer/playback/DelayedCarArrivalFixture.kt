package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/**
 * Replays #277 after the safety pause, through real router cleanup and service transport callbacks.
 * The existing fixture supplies one real Media3 player; audible-route and identity evidence are inputs,
 * not hardware claims. Stop at the arrival decision so an accepted claim cannot start a synthetic track.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DelayedCarArrivalFixture(private val scope: TestScope) : AutoCloseable {
    private val base = PlaybackServiceCommandContinuityFixture(scope)
    val service = base.service
    val router get() = service.audioOutputs
    val player get() = base.player
    val endpointId get() = base.endpointId
    private val clock = service.clock as TestAppClock
    private val owner get() = service.routeOwnership
    private val events: Player.Listener
    private val log = mutableListOf<LogEvent>()
    var identityTrusted = true
    val recovery = CarContinuityRecoveryCoordinator(
        CarArrivalResumeGate(now = clock::elapsed, trustedIdentity = { identityTrusted && it == endpointId }),
    )
    val selectionSequence get() = router.explicitSelection.value?.sequence ?: 0L

    init {
        base.observeMeasuredAutomaticRoute()
        scope.runCurrent()
        service.setPlaybackField("carRecovery", recovery)
        service.logger = object : Logger {
            override fun log(event: LogEvent) {
                log += event
            }
        }
        service.history = playbackDependencyProxy<PlaybackHistoryRepository> { Unit }
        service.autoRewind = AutoRewindController(
            playbackDependencyProxy<PlaybackSettingsRepository> { flowOf(PlaybackSettings()) },
            clock,
            unusedPlaybackDependency<PlaybackHistoryRepository>(),
            service.logger,
            scope.backgroundScope,
        )
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
        events = type.getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }.newInstance(service) as Player.Listener
        player.addListener(events)
        service.playbackCall("bindOutputContinuity")
        player.playWhenReady = true
        recovery.observePlayingHeadset(inputs())
    }

    fun projection(state: AndroidAutoProjectionMonitor.State) = service.carLifecycle.onProjectionReported(state)

    fun safetyPause() {
        router.positions.guard.pauseForSafety(player)
        scope.runCurrent()
    }

    fun listenerPause() {
        // Transport intent must cancel even though the safety pause already set playWhenReady=false.
        base.forwarding.handleSetPlayWhenReady(false)
        scope.runCurrent()
    }

    fun removeHeadset() {
        // AudioManager's empty Robolectric inventory drives the real disappearance/retirement callback.
        router.playbackCall("refresh")
        scope.runCurrent()
    }

    fun returnHeadset() = base.returnHeadset()

    fun observeSpeakerPrediction() {
        val predicted = requireNotNull(
            OutputDevices.outputOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null),
        ).copy(
            isActive = true,
            activitySource = AudioOutputActivitySource.FrameworkMediaPolicy,
        )
        owner.onOutputsChanged(listOf(predicted), isPlaying = true)
        service.playbackCall("observeCarContinuityHeadset", List::class.java, listOf(predicted))
    }

    fun resumeMeasuredPlayback() {
        returnHeadset()
        player.playWhenReady = true
        val measured = router.outputs.value.map {
            it.copy(
                activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            )
        }
        owner.onPlaybackObserved(measured)
        service.playbackCall("observeCarContinuityHeadset", List::class.java, measured)
    }

    fun depart(at: Duration): CarArrivalResumeGate.Decision {
        advanceTo(at)
        val decision = recovery.gate.onProjectionDeparture(at, owner.currentGeneration, selectionSequence)
        service.carLifecycle.onControllerDisconnected()
        service.carLifecycle.onFinalLegacyControllerDeparture()
        projection(AndroidAutoProjectionMonitor.State.NotConnected)
        return decision
    }

    fun advanceTo(at: Duration) = clock.advanceBy(at - clock.elapsed())

    fun interrupt(at: Duration, reason: Int): String {
        clock.advanceBy(at - clock.elapsed())
        events.onPlayWhenReadyChanged(false, reason)
        return log.last { it.message == "Car lifecycle continuity decision" }.fields
            .filterIsInstance<LogField.Public>().single { it.key == "reason" }.value
    }

    fun arrive(at: Duration): CarArrivalResumeGate.Decision {
        clock.advanceBy(at - clock.elapsed())
        service.carLifecycle.onControllerConnected()
        return recovery.carArrival(at, owner.currentGeneration, selectionSequence)
    }

    fun assess(target: CarArrivalResumeGate.Target) = recovery.assess(
        target,
        CarContinuityPlayPrecheck.Proceed,
        owner.currentGeneration,
        selectionSequence,
        endpointId,
    )

    private fun inputs() = CarContinuityRecoveryCoordinator.Inputs(
        owner.heardRoute,
        endpointId,
        owner.currentGeneration,
        selectionSequence,
        carConnected = false,
    )

    override fun close() {
        player.removeListener(events)
        base.close()
    }
}
