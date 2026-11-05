package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Duration

/** Focused service-boundary tests for PlaybackServiceQueueMutation. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceQueueMutationTest {
    @Test
    fun `deferred accepted install uses the service guard before raw Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            val deferred = DeferredPlaybackWork(
                currentPlayer = { fixture.player },
                commandGeneration = fixture::commandGeneration,
                itemGeneration = { fixture.serviceItemGeneration() },
                profileGeneration = { 0L },
                explicitSelectionSequence = fixture::explicitSelectionSequence,
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
                    queue = MediaItems.Queue(playbackFixtureItem("deferred-book"), 0L),
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.checkCurrentItemMutation {
                assertNull(it.handleSetMediaItems(listOf(playbackFixtureItem("replacement")), 0, 7_000L).get())
            }
        }
    }

    @Test
    fun `guarded current item replacement clears inherited Play intent before mutation`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.checkCurrentItemMutation {
                assertNull(it.handleReplaceMediaItems(0, 1, listOf(playbackFixtureItem("replacement"))).get())
            }
        }
    }

    @Test
    fun `guarded removal of the current item clears inherited Play intent before advancing`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.checkCurrentItemMutation { assertNull(it.handleRemoveMediaItems(0, 1).get()) }
        }
    }

    @Test
    fun `future queue edits preserve the healthy current item and Play intent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = false)
            fixture.player.addMediaItems(
                listOf(playbackFixtureItem("future-one"), playbackFixtureItem("future-two")),
            )
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(fixture.forwarding.handleAddMediaItems(3, listOf(playbackFixtureItem("future-three"))).get())
            assertNull(
                fixture.forwarding.handleReplaceMediaItems(
                    1,
                    2,
                    listOf(playbackFixtureItem("replacement-future")),
                ).get(),
            )
            assertNull(fixture.forwarding.handleRemoveMediaItems(2, 3).get())

            assertEquals(listOf(true, true, true), fixture.queueMutationIntents)
            assertEquals("original", fixture.player.currentMediaItem?.mediaId)
            assertEquals(12_000L, fixture.player.currentPosition)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `unguarded queue replacement retains normal transport behavior`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureNoManualGuard()
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(
                fixture.forwarding.handleSetMediaItems(listOf(playbackFixtureItem("replacement")), 0, 7_000L).get(),
            )

            assertEquals(listOf(true), fixture.queueMutationIntents)
            assertEquals("replacement", fixture.player.currentMediaItem?.mediaId)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `adding the first guarded item cannot inherit Play intent from an empty queue`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureManualGuard(selected = true, colliding = true)
            fixture.player.clearMediaItems()
            fixture.retainPlayIntentWhileIdle()
            fixture.queueMutationIntents.clear()

            assertNull(
                fixture.forwarding.handleAddMediaItems(0, listOf(playbackFixtureItem("new-first-item"))).get(),
            )

            assertEquals(listOf(false), fixture.queueMutationIntents)
            assertEquals("new-first-item", fixture.player.currentMediaItem?.mediaId)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test
    fun `a newer seek during inherited-intent pause refuses the older queue replacement`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
                val request = fixture.forwarding.handleSetMediaItems(
                    listOf(playbackFixtureItem("replacement")),
                    0,
                    7_000L,
                )

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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
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
                val request = fixture.forwarding.handleSetMediaItems(
                    listOf(playbackFixtureItem("replacement")),
                    0,
                    7_000L,
                )

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
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.configureNoManualGuard()

            assertTrue(fixture.resumeLoadedCurrent())

            assertTrue(fixture.player.playWhenReady)
            assertEquals(1, fixture.rawPlayTransitions)
            assertEquals(1f, fixture.player.volume)
        }
    }
}
