package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
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
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `synchronous Stop from raw continuity Play cannot resurrect its awaiting target`() = runTest {
        Fixture(this).use { fixture ->
            fixture.beginRecovery()
            runCurrent()
            fixture.stopOnFirstRawPlay()
            val commandBeforeStop = fixture.commandGeneration()

            fixture.returnHeadset()
            runCurrent()

            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(fixture.commandGeneration() > commandBeforeStop, "the forwarding Stop must become newest intent")
            assertEquals(1, fixture.rawPlayCalls, "the old recovery may issue at most its already-entered Play")
            assertFalse(fixture.isRecoveryPlayAwaiting(), "a callback must not resurrect the stopped recovery target")

            fixture.beginRecovery()
            runCurrent()
            assertEquals(1, fixture.rawPlayCalls, "the consumed recovery cannot issue another Play")
            assertFalse(fixture.isRecoveryPlayAwaiting())
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
    fun `service journal snapshot holds the position captured before muted route verification`() = runTest {
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture -> fixture.checkCompensationSeekProvenance() }
    }

    @Test
    fun `a synchronous user seek inside compensation retains its own provenance and position`() = runTest {
        Fixture(this).use { fixture -> fixture.checkCompensationSeekProvenance(inlineUserSeek = true) }
    }

    @Test
    fun `reentrant mute listener sees the checkpoint before the mute acquisition returns`() = runTest {
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture ->
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
        Fixture(this).use { fixture ->
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

    @Test
    fun `deferred accepted install uses the service guard before raw Play`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            val deferred = DeferredPlaybackWork(
                currentPlayer = { fixture.player },
                commandGeneration = fixture::commandGeneration,
                itemGeneration = { fixture.serviceItemGeneration() },
                profileGeneration = { 0L },
                commands = fixture.commands,
                acceptPrepared = { _, authorized, install ->
                    if (authorized()) {
                        install()
                        true
                    } else {
                        false
                    }
                },
            )
            val claim = requireNotNull(deferred.capture())
            val playCallsBefore = fixture.rawPlayCalls

            val installed = deferred.installIfCurrent(
                claim = claim,
                prepared = CarPostConnectRestorer.Prepared(
                    session = PlaybackSession(
                        id = "deferred-fixture",
                        profileId = ProfileId("fixture-profile"),
                        bookId = LibraryItemId("deferred-book"),
                        title = "Fixture",
                        author = null,
                        coverUrl = null,
                        startAt = Duration.ZERO,
                        duration = Duration.ZERO,
                        tracks = emptyList(),
                        chapters = emptyList(),
                    ),
                    queue = MediaItems.Queue(item("deferred-book"), 0L),
                ),
                startPlaying = true,
                condition = { true },
            )
            runCurrent()

            assertFalse(installed)
            assertEquals("deferred-book", fixture.player.currentMediaItem?.mediaId)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(playCallsBefore, fixture.rawPlayCalls)
        }
    }

    @Test
    fun `forwarded prepare cannot prepare retained intent on a denied guarded route`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.retainPlayIntentWhileIdle()
            val preparesBefore = fixture.rawPrepareCalls
            val transitionsBefore = fixture.rawPlayTransitions

            val request = fixture.forwarding.handlePrepare()
            runCurrent()
            val accepted = request.get()

            assertEquals(false, accepted, "the forwarding prepare future must report guarded refusal")
            assertEquals(preparesBefore, fixture.rawPrepareCalls)
            assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
            assertTrue(fixture.player.playWhenReady)
            assertEquals(transitionsBefore, fixture.rawPlayTransitions)
            assertEquals(1f, fixture.player.volume)
        }
    }

    @Test
    fun `forwarded prepare remains available without an output guard`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureNoManualGuard()
            fixture.retainPlayIntentWhileIdle()
            val preparesBefore = fixture.rawPrepareCalls

            val request = fixture.forwarding.handlePrepare()
            runCurrent()
            val accepted = request.get()

            assertEquals(true, accepted, "the normal prepare future must report the command result")
            assertEquals(preparesBefore + 1, fixture.rawPrepareCalls)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `guarded queue replacement clears inherited Play intent before the raw setter`() = runTest {
        Fixture(this).use { fixture ->
            fixture.checkCurrentItemMutation {
                assertNull(it.handleSetMediaItems(listOf(item("replacement")), 0, 7_000L).get())
            }
        }
    }

    @Test
    fun `guarded current item replacement clears inherited Play intent before mutation`() = runTest {
        Fixture(this).use { fixture ->
            fixture.checkCurrentItemMutation {
                assertNull(it.handleReplaceMediaItems(0, 1, listOf(item("replacement"))).get())
            }
        }
    }

    @Test
    fun `guarded removal of the current item clears inherited Play intent before advancing`() = runTest {
        Fixture(this).use { fixture ->
            fixture.checkCurrentItemMutation { assertNull(it.handleRemoveMediaItems(0, 1).get()) }
        }
    }

    @Test
    fun `future queue edits preserve the healthy current item and Play intent`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            fixture.player.addMediaItems(listOf(item("future-one"), item("future-two")))
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(fixture.forwarding.handleAddMediaItems(3, listOf(item("future-three"))).get())
            assertNull(fixture.forwarding.handleReplaceMediaItems(1, 2, listOf(item("replacement-future"))).get())
            assertNull(fixture.forwarding.handleRemoveMediaItems(2, 3).get())

            assertEquals(listOf(true, true, true), fixture.queueMutationIntents)
            assertEquals("original", fixture.player.currentMediaItem?.mediaId)
            assertEquals(12_000L, fixture.player.currentPosition)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `unguarded queue replacement retains normal transport behavior`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureNoManualGuard()
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(fixture.forwarding.handleSetMediaItems(listOf(item("replacement")), 0, 7_000L).get())

            assertEquals(listOf(true), fixture.queueMutationIntents)
            assertEquals("replacement", fixture.player.currentMediaItem?.mediaId)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `adding the first guarded item cannot inherit Play intent from an empty queue`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.player.clearMediaItems()
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(fixture.forwarding.handleAddMediaItems(0, listOf(item("new-first-item"))).get())

            assertEquals(listOf(false), fixture.queueMutationIntents)
            assertEquals("new-first-item", fixture.player.currentMediaItem?.mediaId)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `a newer seek during inherited-intent pause refuses the older queue replacement`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()
            var delivered = false
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && !delivered) {
                        delivered = true
                        fixture.forwarding.handleSeek(0, 14_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    }
                }
            }
            fixture.player.addListener(listener)
            try {
                val request = fixture.forwarding.handleSetMediaItems(listOf(item("replacement")), 0, 7_000L)

                assertTrue(delivered, "the synchronous pause callback must reach a newer real controller seek")
                assertEquals(false, request.get())
                assertTrue(fixture.queueMutationIntents.isEmpty(), "the superseded raw setter must not run")
                assertEquals("original", fixture.player.currentMediaItem?.mediaId)
                assertEquals(14_000L, fixture.player.currentPosition)
            } finally {
                fixture.player.removeListener(listener)
            }
        }
    }

    @Test
    fun `a newer Car choice during inherited-intent pause refuses the older queue replacement`() = runTest {
        Fixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()
            var delivered = false
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && !delivered) {
                        delivered = true
                        fixture.selectCar()
                    }
                }
            }
            fixture.player.addListener(listener)
            try {
                val request = fixture.forwarding.handleSetMediaItems(listOf(item("replacement")), 0, 7_000L)

                assertTrue(delivered, "the synchronous pause callback must deliver newer destination intent")
                assertEquals(false, request.get())
                assertTrue(fixture.queueMutationIntents.isEmpty())
                assertEquals("original", fixture.player.currentMediaItem?.mediaId)
                assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
            } finally {
                fixture.player.removeListener(listener)
            }
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
        val commands: PlaybackCommands get() = service.playbackCommandEffects
        var rawPlayCalls = 0
            private set
        var rawPrepareCalls = 0
            private set
        private var rawPauseRequests = 0
        val queueMutationIntents = mutableListOf<Boolean>()
        private val realPlayer = ExoPlayer.Builder(context).build()
        private var reportedPositionMs: Long? = null

        // Observe commands while retaining one real pinned Media3 player and all its normal callbacks.
        val player = Proxy.newProxyInstance(
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
        private val recovery: CarContinuityRecoveryCoordinator get() = service.field("carRecovery")
        private val effects: CarContinuityResumeEffects get() = service.call(
            "getCarResumeEffects",
        ) as CarContinuityResumeEffects
        private val target: CarArrivalResumeGate.Target
        val forwarding: ResumeFreshnessPlayer
        var rawPlayTransitions = 0
            private set

        init {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    service.setField("mediaItemGeneration", service.field<Long>("mediaItemGeneration") + 1L)
                }
            })
            player.setMediaItem(item("original"))
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
            service.volumeEffects = volumeEffects
            service.setField("activeProfileGeneration", { 0L })
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
            service.sessionSync = sync
            service.resumeBaseline = baseline
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

        suspend fun checkCompensationSeekProvenance(inlineUserSeek: Boolean = false) {
            var positionWrites = 0
            service.playbackRepository = proxy<PlaybackRepository> { method ->
                check(method.substringBefore('-') == "recordPosition")
                positionWrites++
                AppResult.Success(Unit)
            }
            service.autoRewind = AutoRewindController(
                proxy<PlaybackSettingsRepository> { flowOf(PlaybackSettings()) },
                clock,
                unused<PlaybackHistoryRepository>(),
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
            if (connected) returnHeadset() else router.setField("live", emptyMap<String, AudioDeviceInfo>())
            router.setField("guardedHeadsetId", endpointId)
            router.field<MutableStateFlow<String?>>("_selectedId").value = if (selected) endpointId else null
            router.setField("collidingOutputIds", if (colliding) setOf(endpointId) else emptySet<String>())
        }

        fun configureNoManualGuard() {
            router.setField("guardedHeadsetId", null)
            router.field<MutableStateFlow<String?>>("_selectedId").value = null
        }

        fun checkCurrentItemMutation(change: (ResumeFreshnessPlayer) -> Unit) {
            configureManualGuard(selected = true, colliding = true)
            player.addMediaItem(item("future"))
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
        fun positionSnapshotPosition(): Long? = service.call("positionSnapshot")?.field("position")
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

        fun commandGeneration(): Long = service.field("controllerCommandGeneration")

        fun serviceItemGeneration(): Long = service.field("mediaItemGeneration")

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
