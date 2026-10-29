package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.SyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Focused service-boundary tests for PlaybackServiceSelectionScope. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceSelectionScopeTest {
    @Test
    fun `Car Play captures its own synchronous selection before launching the forwarding future`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.armFreshStartForCurrentItem()
            fixture.configureNoManualGuard()
            val playCalls = fixture.rawPlayCalls
            val request = fixture.fromCarController { fixture.forwarding.handleSetPlayWhenReady(true) }
            val sequence = fixture.explicitSelectionSequence()
            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())

            runCurrent()

            assertEquals(true, request.get(), "the Play that chose Car must retain its own authorization")
            assertEquals(playCalls + 1, fixture.rawPlayCalls)
            assertEquals(sequence, fixture.explicitSelectionSequence(), "raw Play must not invent another choice")
        }
    }

    @Test
    fun `queued forwarding Play is refused after newer output selection`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.armFreshStartForCurrentItem()
            fixture.configureNoManualGuard()
            val playCallsBefore = fixture.rawPlayCalls

            val request = fixture.forwarding.handleSetPlayWhenReady(true)
            fixture.selectCar()
            runCurrent()

            assertEquals(false, request.get(), "the captured Play loses to the later output selection")
            assertFalse(fixture.player.playWhenReady)
            assertEquals(playCallsBefore, fixture.rawPlayCalls, "stale Play must not reach ExoPlayer")
        }
    }

    @Test
    fun `queued forwarding Prepare is refused after newer output selection`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureNoManualGuard()
            fixture.retainPlayIntentWhileIdle()
            val preparesBefore = fixture.rawPrepareCalls

            val request = fixture.forwarding.handlePrepare()
            fixture.selectCar()
            runCurrent()

            assertEquals(false, request.get(), "the captured prepare loses to the later output selection")
            assertEquals(preparesBefore, fixture.rawPrepareCalls, "stale Prepare must not reach ExoPlayer")
            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `output selection preserves captured pause journal and sync`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val recordEntered = CompletableDeferred<Unit>()
            val releaseRecord = CompletableDeferred<Unit>()
            fixture.configureCapturedPauseSync(recordEntered, releaseRecord)
            fixture.dispatchPausedPlayerEvent()
            runCurrent()
            recordEntered.await()

            fixture.selectCar()
            releaseRecord.complete(Unit)
            runCurrent()

            assertEquals("original", fixture.capturedJournalBookId?.value)
            assertEquals(ProfileId("pause-profile"), fixture.capturedJournalOwner)
            val journalPosition = assertNotNull(fixture.capturedJournalPosition)
            assertEquals(listOf(SyncTrigger.Paused), fixture.pauseSyncTriggers)
            assertEquals(listOf(journalPosition), fixture.syncedPositions)
        }
    }

    @Test
    fun `onPostConnect selection after accepted callback cancels captured restoration before queue open`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configurePostConnectRace()
            fixture.invokePostConnect(selectCar = true)
            runCurrent()

            assertEquals(0, fixture.postConnectSessionOpens, "selection must revoke before /play queue open")
            assertEquals(0, fixture.rawPrepareCalls)
            assertEquals(0, fixture.rawPlayCalls)
            assertEquals(0, fixture.player.mediaItemCount)
        }
    }

    @Test
    fun `current onPostConnect request reaches one queue lookup before a controlled lookup failure`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configurePostConnectRace()
            fixture.invokePostConnect(selectCar = false)
            runCurrent()

            assertEquals(
                1,
                fixture.postConnectSessionOpens,
                "the valid callback must reach production queue preparation",
            )
            assertEquals(0, fixture.rawPrepareCalls)
            assertEquals(0, fixture.rawPlayCalls)
            assertEquals(0, fixture.player.mediaItemCount)
        }
    }
}
