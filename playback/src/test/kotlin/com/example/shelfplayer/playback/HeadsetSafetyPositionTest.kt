package com.example.shelfplayer.playback

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadsetSafetyPositionTest {
    @Test
    fun `safety pause holds the verified position for progress and the exact headset probe`() = runTest {
        val f = MutedPositionEffectsTest.Fixture(this)
        f.effects.observeGuardedHeadset(100.milliseconds)
        f.rawPosition += 200
        f.router.onBeforeRouteSafetyPause = { f.effects.freezeGuardedHeadset(300.milliseconds) }
        f.router.positions.guard.pauseForSafety(f.media)
        f.rawPosition += 1_049

        assertEquals(12_000L, f.effects.positionMs())
        f.start()
        assertEquals(12_000.milliseconds, f.activeProbe()?.positionCheckpoint?.position)
        assertEquals(0, f.plays)
    }

    @Test
    fun `stale observations other outputs and newer commands cannot move progress`() = runTest {
        val f = MutedPositionEffectsTest.Fixture(this)
        f.effects.observeGuardedHeadset(100.milliseconds)
        f.rawPosition = 18_000
        f.effects.freezeGuardedHeadset(2_000.milliseconds)
        assertEquals(18_000L, f.effects.positionMs())
        f.effects.observeGuardedHeadset(2_100.milliseconds)
        f.command++
        f.rawPosition = 22_000
        f.effects.freezeGuardedHeadset(2_200.milliseconds)
        assertEquals(22_000L, f.effects.positionMs())
        f.observation = AudioTrackObservation(1, AudioTrackPath.RemoteSubmix)
        f.effects.observeGuardedHeadset(2_300.milliseconds)
        f.rawPosition = 24_000
        f.effects.freezeGuardedHeadset(2_400.milliseconds)
        assertEquals(24_000L, f.effects.positionMs())
    }

    @Test
    fun `manual transport preserves the paused cursor once but a listener seek wins`() = runTest {
        for (movement in listOf(PlaybackMovement.Pause, PlaybackMovement.Play, PlaybackMovement.Seek)) {
            val f = MutedPositionEffectsTest.Fixture(this)
            f.effects.observeGuardedHeadset(100.milliseconds)
            f.router.onBeforeRouteSafetyPause = { f.effects.freezeGuardedHeadset(300.milliseconds) }
            f.router.positions.guard.pauseForSafety(f.media)
            f.rawPosition += 1_049
            assertNotNull(f.effects.before(movement)).invoke()
            assertNotNull(f.effects.before(movement)).invoke()
            assertEquals(if (movement == PlaybackMovement.Seek) 13_049L else 12_000L, f.rawPosition)
            assertEquals(if (movement == PlaybackMovement.Seek) emptyList() else listOf(true), f.compensationOrigins)
            assertEquals(false, f.playWhenReady)
        }
    }

    @Test
    fun `a healthy connection makes no position correction and another profile cannot inherit the pause`() = runTest {
        val f = MutedPositionEffectsTest.Fixture(this)
        f.effects.observeGuardedHeadset(100.milliseconds)
        f.rawPosition += 200
        assertEquals(12_200L, f.effects.positionMs())
        f.router.onBeforeRouteSafetyPause = { f.effects.freezeGuardedHeadset(300.milliseconds) }
        f.router.positions.guard.pauseForSafety(f.media)
        f.profileGeneration++
        assertEquals(12_200L, f.effects.positionMs())
        assertEquals(emptyList(), f.compensationOrigins)
    }
}
