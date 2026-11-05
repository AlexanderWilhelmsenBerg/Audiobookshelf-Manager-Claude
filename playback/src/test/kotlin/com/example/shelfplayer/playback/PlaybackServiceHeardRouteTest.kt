package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Service callback binding over the shared, real Media3 command fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceHeardRouteTest {
    @Test
    fun `service callback and route selection stay silent while muted then confirm only audible release`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val service = fixture.playbackField<PlaybackService>("service")
            val router = fixture.playbackField<AudioOutputRouter>("router")
            val volumeEffects = fixture.playbackField<PlaybackVolumeEffects>("volumeEffects")
            val owner = service.routeOwnership
            val recovery = service.playbackField<CarContinuityRecoveryCoordinator>("carRecovery")
            val fakePlayer = PlayingExoPlayer(fixture.player)
            service.setPlaybackField("player", fakePlayer.player)
            volumeEffects.attach(fakePlayer.player)
            attachRewind(service, fakePlayer.player)
            val observer = service.playbackCall("getRouteObservationEffects") as PlaybackRouteObservationEffects
            observer.attach()

            val output = AudioOutput(
                id = fixture.endpointId,
                displayName = "Fixture headset",
                kind = DeviceKind.Bluetooth,
                role = AudioOutputRole.Headset,
                isActive = true,
                activitySource = AudioOutputActivitySource.PlayingAudioTrack,
            )
            router.playbackField<MutableStateFlow<List<AudioOutput>>>("_outputs").value = listOf(output)
            router.playbackField<MutableStateFlow<String?>>("_selectedId").value = output.id
            owner.onBookChanged(hasBook = true)

            try {
                val claim = requireNotNull(volumeEffects.acquireRouteMute(fakePlayer.player))
                fixture.selectCar()
                service.playbackCall("syncExplicitOutputIntent")
                val generation = requireNotNull(owner.currentGeneration)
                val selectionSequence = fixture.explicitSelectionSequence()
                recovery.notePlayIssued(target(output.id, generation, selectionSequence))

                val playerEvents = playerEvents(service)
                playerEvents.onIsPlayingChanged(true)
                assertNull(owner.heardRoute, "the raw service callback cannot record evidence under route mute")
                assertTrue(recovery.isPlayAwaiting, "muted raw isPlaying must not consume car recovery confirmation")

                assertTrue(volumeEffects.releaseRouteMute(claim))
                assertEquals(generation, owner.heardRoute?.generation)
                assertFalse(recovery.isPlayAwaiting, "still-playing unmute confirms the pending recovery once")
                assertFalse(volumeEffects.releaseRouteMute(claim))

                fakePlayer.playing = false
                owner.onBookChanged(hasBook = true)
                val pausedGeneration = requireNotNull(owner.currentGeneration)
                recovery.notePlayIssued(target(output.id, pausedGeneration, selectionSequence))
                val pausedClaim = requireNotNull(volumeEffects.acquireRouteMute(fakePlayer.player))
                assertTrue(volumeEffects.releaseRouteMute(pausedClaim))
                assertNull(owner.heardRoute, "a paused release is not audible route evidence")
                assertTrue(recovery.isPlayAwaiting, "a paused release does not consume recovery confirmation")
            } finally {
                observer.detach()
                volumeEffects.detach(fakePlayer.player)
            }
        }
    }

    private fun target(outputId: String, generation: Long, selectionSequence: Long) = CarArrivalResumeGate.Target(
        outputId = outputId,
        generation = generation,
        explicitSelectionSequence = selectionSequence,
        phase = CarArrivalResumeGate.Phase.Arrival,
    )

    private fun TestScope.attachRewind(service: PlaybackService, player: Player) {
        service.autoRewind = AutoRewindController(
            repository = playbackDependencyProxy { method ->
                when (method) {
                    "observeSettings" -> flowOf(PlaybackSettings())
                    else -> error("Unexpected playback-settings call: $method")
                }
            },
            clock = TestAppClock(),
            history = unusedPlaybackDependency<PlaybackHistoryRepository>(),
            logger = service.logger,
            applicationScope = backgroundScope,
        ).also { it.attach(player) }
    }

    private fun playerEvents(service: PlaybackService): Player.Listener {
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
        return type.getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }
            .newInstance(service) as Player.Listener
    }

    private class PlayingExoPlayer(private val delegate: ExoPlayer) {
        var playing = true
        private var volume = 1f
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isPlaying" -> playing

                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                else -> method.invoke(delegate, *args.orEmpty())
            }
        } as ExoPlayer
    }
}
