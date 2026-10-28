package com.example.shelfplayer.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.common.time.SystemAppClock
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.FutureTask
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Real MediaLibrarySession callback futures: a cancelled old resolution must not install empty media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(UnstableApi::class)
class ControllerQueueMedia3ReplayTest {
    @Test
    fun `newer controller Stop wins over a suspended library queue future`() = replay(stop = true)

    @Test
    fun `ordinary controller set then Play survives suspended queue resolution`() = replay(stop = false)

    @Test
    fun `cancelled cold resumption cannot fallback Play a newer queue`() {
        Fixture(cold = true).use { fixture ->
            fixture.request()
            fixture.stop()
            fixture.complete()
            fixture.assertCancelled()
            fixture.assertLaterExplicitPlay()
        }
    }

    @Test
    fun `valid cold resumption performs one passive compound install and Play`() {
        Fixture(cold = true).use { fixture ->
            fixture.request()
            fixture.complete()
            fixture.assertPlayed()
        }
    }

    private fun replay(stop: Boolean) {
        Fixture().use { fixture ->
            fixture.request()
            if (stop) fixture.stop() else fixture.play()
            fixture.complete()
            if (stop) fixture.assertCancelled() else fixture.assertPlayed()
        }
    }

    private inner class Fixture(
        private val cold: Boolean = false,
        private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        releaseClock: AppClock = SystemAppClock(),
    ) : AutoCloseable {
        private val service = Robolectric.buildService(QueueService::class.java).create().get()

        // Media3's release barrier uses elapsedRealtime for its timeout. Robolectric's paused clock can
        // otherwise leave cleanup waiting forever if the playback thread exits before opening the barrier.
        private val player = ExoPlayer.Builder(service).setClock(
            object : Clock by Clock.DEFAULT {
                override fun elapsedRealtime(): Long = releaseClock.elapsed().inWholeMilliseconds
            },
        ).build()
        private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        private var generation = 0L
        private var prepared = 0
        private var published = 0
        private var passiveRequests = 0
        private var playRequests = 0
        private val release = CompletableDeferred<Unit>()
        private val forwarding = ResumeFreshnessPlayer(
            player,
            { _ ->
                if (futures.isPlaybackResumptionDelivery()) passiveRequests++
                player.play()
                Futures.immediateVoidFuture()
            },
            { false },
            {
                generation++
                true
            },
            {
                generation++
                if (it) playRequests++
                true
            },
            { SkipIntervals(15.seconds, 30.seconds) },
            prepareCurrentOutput = { Futures.immediateVoidFuture() },
            movementEffects = unprotectedMovementEffects(player),
            allowEffects = { futures.playerEffectsAllowed() },
        )
        private val resolution: ControllerQueueResolution = ControllerQueueResolution(
            commandGeneration = { generation },
            profileGeneration = { 0L },
            serviceOwner = { player },
            activeProfileId = { PROFILE },
            isProfileLocked = { false },
            prepare = { _, _ ->
                prepared++
                release.await()
                CarPostConnectRestorer.Prepared(session(), MediaItems.Queue(item("old-answer"), 42_000L))
            },
            accept = { _, allowed, install ->
                if (allowed()) {
                    published++
                    install()
                    true
                } else {
                    false
                }
            },
        )
        private val futures = SessionCallbackFutures(scope, mainDispatcher, logger)
        private var callbackAnswer: ListenableFuture<MediaSession.MediaItemsWithStartPosition>? = null
        private val session = MediaLibrarySession.Builder(
            service,
            forwarding,
            object : MediaLibrarySession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val claim = resolution.capture()
                    return futures.submitPlayback(
                        authorized = { resolution.isUnchanged(claim) },
                        playable = { it.mediaItems.isNotEmpty() },
                    ) {
                        val queue = assertNotNull(resolution.resolve(claim, BOOK, null))
                        MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, queue.startPositionMs)
                    }.also { callbackAnswer = it }
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    generation++
                    val claim = resolution.capture()
                    return futures.submit {
                        val queue = assertNotNull(resolution.resolve(claim, BOOK, null))
                        MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, queue.startPositionMs)
                    }.also { callbackAnswer = it }
                }
            },
        ).setId("phase3-controller-queue-replay").build()
        private val live = LivePlaybackSession().apply { publish(session.token) }
        private val requester = connect()
        private val stopper = connect()

        private fun connect() = assertNotNull(
            runConnection {
                SessionConnector(service, logger, live).connectExisting(object : MediaController.Listener {})
            },
        )

        fun request() {
            if (cold) {
                requester.play()
            } else {
                player.setMediaItem(item("current"), 7_000L)
                requester.setMediaItem(MediaItem.Builder().setMediaId("book/request").build())
            }
            pump { prepared == 1 }
        }

        fun stop() {
            val beforeStop = generation
            stopper.stop()
            pump { generation > beforeStop }
            if (cold) player.setMediaItem(item("current"), 7_000L)
        }

        fun play() {
            requester.play()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(assertNotNull(callbackAnswer).isDone, "Play must wait for the actual queue answer")
            assertEquals(0, playRequests, "the same controller's Play must not overtake its pending media request")
        }

        fun complete() {
            release.complete(Unit)
            pump { callbackAnswer?.isDone == true }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun assertCancelled() {
            assertTrue(assertNotNull(callbackAnswer).isCancelled)
            assertEquals(0, published, "stale remote preparation cannot publish a durable live session")
            assertEquals("current", player.currentMediaItem?.mediaId)
            assertEquals(1, player.mediaItemCount, "a cancelled future cannot clear newer playback")
            assertFalse(player.playWhenReady)
            assertEquals(0, playRequests)
        }

        fun assertPlayed() {
            pump { player.currentMediaItem?.mediaId == "old-answer" && playRequests == 1 }
            assertFalse(assertNotNull(callbackAnswer).isCancelled)
            assertEquals(1, published)
            assertEquals(42_000L, player.currentPosition)
            assertTrue(player.playWhenReady)
            assertEquals(1, playRequests)
            assertEquals(if (cold) 1 else 0, passiveRequests)
        }

        fun assertLaterExplicitPlay() {
            stopper.play()
            pump { player.playWhenReady }
            assertEquals(1, playRequests)
            assertEquals(0, passiveRequests, "a rejected completion cannot leak passive intent")
        }

        override fun close() {
            requester.release()
            stopper.release()
            scope.cancel()
            session.release()
            player.release()
            service.onDestroy()
        }
    }

    class QueueService : MediaLibraryService() {
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = null
    }

    private fun pump(condition: () -> Boolean) {
        repeat(MAX_POLLS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.yield()
        }
        assertTrue(condition(), "Media3 callback did not complete")
    }

    private fun <T> runConnection(block: suspend () -> T): T {
        val task = FutureTask { runBlocking { block() } }
        Thread(task, "phase3-session-connection").apply {
            isDaemon = true
            start()
        }
        repeat(MAX_POLLS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (task.isDone) return task.get()
            LockSupport.parkNanos(POLL_NANOS)
        }
        task.cancel(true)
        error("Media3 test connection timed out")
    }

    private companion object {
        const val MAX_POLLS = 10_000
        const val POLL_NANOS = 100_000L
        val PROFILE = ProfileId("first")
        val BOOK = LibraryItemId("book")
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id").build()
        fun session() = PlaybackSession(
            id = "remote-test", profileId = PROFILE, bookId = BOOK, title = "Test book",
            author = null, coverUrl = null, startAt = 42.seconds, duration = 1.hours,
            tracks = emptyList(), chapters = emptyList(),
        )
    }
}
