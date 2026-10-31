package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackVolumeEffectsTest {

    @Test
    fun `shared route observer gates muted playback and retires its listener safely`() {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val first = MutablePlayer(isPlaying = true)
        val successor = MutablePlayer(isPlaying = true)
        var currentPlayer: Player? = first.player
        val output = routeOutput()
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(true) }
        val recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        val pending = CarArrivalResumeGate.Target(
            outputId = output.id,
            generation = requireNotNull(owner.currentGeneration),
            explicitSelectionSequence = 0,
            phase = CarArrivalResumeGate.Phase.Arrival,
        )
        recovery.notePlayIssued(pending)
        val observations = mutableListOf<Boolean>()
        val observer = PlaybackRouteObservationEffects(
            currentPlayer = { currentPlayer },
            volumeEffects = effects,
            onAudible = {
                owner.onPlaybackObserved(listOf(output))
                recovery.playbackBecameActive(owner.currentGeneration, 0)
                observations += true
            },
        )
        effects.attach(first.player)
        observer.attach()
        val firstClaim = requireNotNull(effects.acquireRouteMute(first.player))

        owner.onOutputsChanged(
            listOf(output),
            isPlaying = observer.isAudible(first.player),
        )
        observer.observe(first.player)
        assertEquals(null, owner.heardRoute, "raw Media3 playing while route-muted is not audible evidence")
        assertEquals(null, owner.measuredPlayingHeadsetForContinuity(listOf(output)))
        assertTrue(recovery.isPlayAwaiting, "muted playback cannot consume recovery's audible claim")
        assertTrue(effects.releaseRouteMute(firstClaim))

        assertEquals(owner.currentGeneration, owner.heardRoute?.generation)
        assertEquals(output.id, owner.measuredPlayingHeadsetForContinuity(listOf(output)))
        assertFalse(effects.releaseRouteMute(firstClaim), "the release is one-shot")
        assertEquals(listOf(true), observations)
        assertFalse(recovery.isPlayAwaiting, "successful audible release confirms recovery")

        // A callback carrying the predecessor is rejected even after the successor is attached.
        currentPlayer = successor.player
        effects.attach(successor.player)
        val successorClaim = requireNotNull(effects.acquireRouteMute(successor.player))
        observer.observe(first.player)
        assertTrue(effects.releaseRouteMute(successorClaim))
        assertEquals(listOf(true, true), observations)

        // Teardown removes this exact callback from the singleton before later mute cycles.
        observer.detach()
        effects.acquireRouteMute(successor.player)?.let(effects::releaseRouteMute)
        assertEquals(listOf(true, true), observations)
    }

    @Test
    fun `audible gate rechecks player identity after player getters`() {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val first = MutablePlayer(isPlaying = true)
        val successor = MutablePlayer(isPlaying = true)
        var currentPlayer: Player? = first.player
        var observations = 0
        val observer = PlaybackRouteObservationEffects(
            currentPlayer = { currentPlayer },
            volumeEffects = effects,
            onAudible = { observations += 1 },
        )
        effects.attach(first.player)
        first.onIsPlayingRead = { currentPlayer = successor.player }

        assertFalse(observer.isAudible(first.player), "the final identity check rejects a replaced player")
        observer.observe(first.player)
        assertEquals(0, observations)
    }

    @Test
    fun `observer detached during unmute dispatch cannot create audible evidence`() {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val player = MutablePlayer(isPlaying = true)
        var observations = 0
        val observer = PlaybackRouteObservationEffects(
            currentPlayer = { player.player },
            volumeEffects = effects,
            onAudible = { observations++ },
        )
        effects.addMuteListener { change -> if (!change.muted) observer.detach() }
        observer.attach()
        effects.attach(player.player)
        val claim = requireNotNull(effects.acquireRouteMute(player.player))

        assertTrue(effects.releaseRouteMute(claim))

        assertEquals(0, observations, "a detached service observer cannot run from the copied dispatch list")
        assertTrue(player.player.isPlaying, "listener lifetime is independent of the still-current player")
    }

    @Test
    fun `mute dispatch skips a listener removed by an earlier callback`() {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val player = MutablePlayer()
        var laterCalls = 0
        lateinit var laterListener: (PlaybackVolumeEffects.MuteChange) -> Unit
        val firstListener: (PlaybackVolumeEffects.MuteChange) -> Unit = {
            effects.removeMuteListener(laterListener)
        }
        laterListener = { laterCalls += 1 }
        effects.addMuteListener(firstListener)
        effects.addMuteListener(laterListener)
        effects.attach(player.player)

        effects.acquireRouteMute(player.player)

        assertEquals(0, laterCalls, "the live listener set is authoritative after earlier callback mutations")
        effects.removeMuteListener(firstListener)
    }

    @Test
    fun `shared route observer ignores successful mute release while player is paused`() {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val player = MutablePlayer(isPlaying = false)
        val output = routeOutput()
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply { onBookChanged(true) }
        val recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate())
        recovery.notePlayIssued(
            CarArrivalResumeGate.Target(
                outputId = output.id,
                generation = requireNotNull(owner.currentGeneration),
                explicitSelectionSequence = 0,
                phase = CarArrivalResumeGate.Phase.Arrival,
            ),
        )
        val observer = PlaybackRouteObservationEffects(
            currentPlayer = { player.player },
            volumeEffects = effects,
            onAudible = {
                owner.onPlaybackObserved(listOf(output))
                recovery.playbackBecameActive(owner.currentGeneration, 0)
            },
        )
        effects.attach(player.player)
        observer.attach()
        val claim = requireNotNull(effects.acquireRouteMute(player.player))

        assertTrue(effects.releaseRouteMute(claim))
        assertEquals(null, owner.heardRoute)
        assertTrue(recovery.isPlayAwaiting)
        observer.detach()
    }

    private fun routeOutput() = AudioOutput(
        id = "bluetooth:buds",
        displayName = "Buds",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Headset,
        isActive = true,
        activitySource = AudioOutputActivitySource.PlayingAudioTrack,
    )

    @Test
    fun `retired probe cleanup restores its own latest gain and cannot touch successor`() {
        val owner = PlaybackVolumeEffects(TestAppClock())
        val old = MutablePlayer()
        val successor = MutablePlayer()
        val events = mutableListOf<Boolean>()
        owner.addMuteListener { events += it.muted }
        owner.attach(old.player)
        owner.setSleepGain(old.player, 0.4f)
        val claim = requireNotNull(owner.acquireRouteMute(old.player))
        owner.attach(successor.player)
        owner.setSleepGain(successor.player, 0.7f)

        assertTrue(owner.releaseRouteMute(claim))

        assertEquals(0.4f, old.volume)
        assertEquals(0.7f, successor.volume)
        assertEquals(listOf(true), events, "retiring a muted player is not an audible resume")
    }

    @Test
    fun `late predecessor detach retires its claim without unbinding successor`() {
        val owner = PlaybackVolumeEffects(TestAppClock())
        val old = MutablePlayer()
        val successor = MutablePlayer()
        owner.attach(old.player)
        val claim = requireNotNull(owner.acquireRouteMute(old.player))
        owner.attach(successor.player)

        owner.detach(old.player)
        owner.setSleepGain(successor.player, 0.6f)

        assertFalse(owner.releaseRouteMute(claim), "explicitly retired claims cannot affect disposed players")
        assertEquals(0f, old.volume)
        assertEquals(0.6f, successor.volume)
        assertFalse(owner.isRouteMuted(successor.player))
        assertFalse(owner.releaseRouteMute(null))
    }

    private class MutablePlayer(private val isPlaying: Boolean = false) {
        var volume = 1f
        var onIsPlayingRead: (() -> Unit)? = null
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume

                "isPlaying" -> isPlaying.also { onIsPlayingRead?.invoke() }

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player
    }
}
