package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Focused service-boundary tests for PlaybackServiceCommandContinuity. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceCommandContinuityTest {
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
            assertNull(forwarding.handleSetMediaItems(listOf(playbackFixtureItem("replacement")), 0, 0L).get())
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val deferred = DeferredPlaybackWork(
                currentPlayer = { fixture.player },
                commandGeneration = fixture::commandGeneration,
                itemGeneration = { 0 },
                profileGeneration = { 0 },
                explicitSelectionSequence = fixture::explicitSelectionSequence,
                commands = fixture.commands,
                acceptPrepared = { _, _, _ -> error("A skipped-over deferred claim must not install anything") },
            )
            val claim = requireNotNull(deferred.capture())
            assertTrue(deferred.isCurrent(claim))

            fixture.skip(30.seconds)

            assertFalse(deferred.isCurrent(claim), "custom skip is newer intent for every existing effect owner")
            assertEquals(42_000L, fixture.player.currentPosition)
        }
    }

    private fun TestScope.assertCommandSupersedes(command: PlaybackServiceCommandContinuityFixture.() -> Unit) {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
}
