package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Focused service-boundary tests for PlaybackServiceOutputProtection. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceOutputProtectionTest {
    @Test
    fun `forwarding Stop releases the lock even when Media3 retains the loaded queue`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { it.checkLockedCarControllerPlay() }
    }

    @Test
    fun `route safety pause cannot become listener auto-rewind intent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture -> fixture.checkRouteSafetyPauseReason() }
    }

    @Test
    fun `service protects an exact Automatic route before car arrival`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
    fun `synchronous Stop from raw continuity Play cannot resurrect its awaiting target`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.beginRecovery()
            runCurrent()
            fixture.stopOnFirstRawPlay()
            val commandBeforeStop = fixture.commandGeneration()

            fixture.returnHeadset()
            runCurrent()

            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(
                fixture.commandGeneration() > commandBeforeStop,
                "the forwarding Stop must become newest intent",
            )
            assertEquals(1, fixture.rawPlayCalls, "the old recovery may issue at most its already-entered Play")
            assertFalse(
                fixture.isRecoveryPlayAwaiting(),
                "a callback must not resurrect the stopped recovery target",
            )

            fixture.beginRecovery()
            runCurrent()
            assertEquals(1, fixture.rawPlayCalls, "the consumed recovery cannot issue another Play")
            assertFalse(fixture.isRecoveryPlayAwaiting())
        }
    }

    @Test
    fun `guarded local resume refuses a colliding endpoint without raw Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = false, colliding = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `guarded local resume refuses an absent endpoint without raw Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false, connected = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `a logical output lock cannot bypass protection when its guard is absent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)

            assertFalse(fixture.resumeLoadedCurrent())

            assertEquals(0, fixture.rawPlayTransitions)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `guarded local resume makes one muted Play transition when the probe can start`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
    fun `service journal snapshot holds the position captured before muted route verification`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            val heldPosition = fixture.positionSnapshotPosition()
            assertTrue(fixture.resumeLoadedCurrent())
            assertTrue(fixture.isRouteMuted())

            fixture.reportPositionWithoutSeek(25_000L)
            runCurrent()

            assertTrue(fixture.player.currentPosition >= 25_000L, "the raw player position should have advanced")
            assertEquals(
                heldPosition,
                fixture.positionSnapshotPosition(),
                "journal and pause sync must use held position",
            )
        }
    }

    @Test
    fun `forwarded Pause restores silent advancement before the real pause and retires its probe`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            assertTrue(fixture.resumeLoadedCurrent())
            fixture.reportPositionWithoutSeek(25_000L)

            fixture.forwarding.handleSetPlayWhenReady(false)
            runCurrent()

            assertFalse(fixture.player.playWhenReady)
            assertEquals(12_000L, fixture.player.currentPosition)
            assertFalse(fixture.isRouteMuted())
            assertFalse(fixture.hasPositionCheckpoint())
        }
    }

    @Test
    fun `forwarded Stop retires a muted probe despite retained Play intent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            assertTrue(fixture.resumeLoadedCurrent())
            fixture.reportPositionWithoutSeek(25_000L)

            fixture.forwarding.handleStop()
            runCurrent()

            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertEquals(12_000L, fixture.player.currentPosition)
            assertFalse(fixture.isRouteMuted())
            assertFalse(fixture.hasPositionCheckpoint())
        }
    }

    @Test
    fun `real service seek callbacks exclude compensation but retain a later user seek`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture -> fixture.checkCompensationSeekProvenance() }
    }

    @Test
    fun `a synchronous user seek inside compensation retains its own provenance and position`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.checkCompensationSeekProvenance(inlineUserSeek = true)
        }
    }

    @Test
    fun `reentrant mute listener sees the checkpoint before the mute acquisition returns`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            val expectedPosition = fixture.positionSnapshotPosition()
            var observedPosition: Long? = null
            fixture.observeMuteEdgePosition {
                fixture.reportPositionWithoutSeek(25_000L)
                observedPosition = fixture.positionSnapshotPosition()
            }

            assertTrue(fixture.resumeLoadedCurrent())

            assertEquals(expectedPosition, observedPosition)
            assertTrue(fixture.isRouteMuted())
        }
    }

    @Test
    fun `guarded playback can acquire a zero position checkpoint`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            fixture.reportPositionWithoutSeek(0L)

            assertTrue(fixture.resumeLoadedCurrent())
            assertTrue(fixture.isRouteMuted())
            assertTrue(fixture.player.playWhenReady)
            fixture.reportPositionWithoutSeek(25_000L)
            assertNull(
                fixture.positionSnapshotPosition(),
                "silent advancement cannot make an unstarted point persistable",
            )
        }
    }

    @Test
    fun `guarded retained Play intent cannot prepare an idle player before route proof`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            fixture.retainPlayIntentWhileIdle()
            val states = fixture.observePlaybackStates()
            val transitionsBeforeResume = fixture.rawPlayTransitions
            fixture.chooseCarOnNextRouteMute()

            assertFalse(fixture.resumeLoadedCurrent())
            runCurrent()

            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(
                fixture.player.playWhenReady,
                "the newer route choice does not erase existing transport intent",
            )
            assertEquals(transitionsBeforeResume, fixture.rawPlayTransitions)
            assertFalse(
                states.any { it.state != Player.STATE_IDLE },
                "the old request must not prepare after the synchronous newer destination choice",
            )
            assertTrue(fixture.isRouteMuted(), "the newer Car request must retain its own mute until proof")
            assertEquals(
                OutputIntentOwner.Destination.Car,
                fixture.activeTransferDestination(),
                "the superseded headset probe must not remain the mute owner",
            )
            assertEquals(0f, fixture.player.volume)
            advanceTimeBy(5_100L)
            runCurrent()
            assertFalse(fixture.player.playWhenReady, "an idle unverified successor times out safely")
            assertFalse(fixture.isRouteMuted(), "neither the old claim nor the failed successor may strand mute")
            assertEquals(null, fixture.activeTransferDestination())
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `fresh-start forwarding Play cannot bypass guarded output denial`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.armFreshStartForCurrentItem()
            fixture.configureManualGuard(selected = true, colliding = true)
            val transitionsBefore = fixture.rawPlayTransitions
            val playCallsBefore = fixture.rawPlayCalls

            val request = fixture.forwarding.handleSetPlayWhenReady(true)
            runCurrent()
            val accepted = request.get()

            assertEquals(false, accepted, "the forwarding future must report guarded refusal")
            assertFalse(fixture.player.playWhenReady)
            assertEquals(transitionsBefore, fixture.rawPlayTransitions)
            assertEquals(playCallsBefore, fixture.rawPlayCalls)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `fresh-start forwarding Play still skips a second freshness request without a guard`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.armFreshStartForCurrentItem()
            fixture.configureNoManualGuard()
            val playCallsBefore = fixture.rawPlayCalls

            val request = fixture.forwarding.handleSetPlayWhenReady(true)
            runCurrent()
            val accepted = request.get()

            assertEquals(true, accepted)
            assertTrue(fixture.player.playWhenReady)
            assertEquals(playCallsBefore + 1, fixture.rawPlayCalls)
        }
    }
}
