package com.example.shelfplayer.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #174 — selecting an Android Auto Profiles row switches profile and is never answered with media.
 *
 * The first cases pin [ProfileRowSelection]'s own answer. The last pins the Media3 behaviour that answer
 * relies on, with a real session and controller: a failed set-media answer leaves the session's player
 * exactly as it was. If a Media3 upgrade ever applied such an answer, a profile tap would replace or clear
 * the listener's book, and this is the test that would say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(UnstableApi::class)
class ProfileRowSelectionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = object : Logger {
        override fun log(event: LogEvent) = Unit
    }

    @Test
    fun `a completed switch is reported and the row is still not media`() {
        val switched = SessionResult(SessionResult.RESULT_SUCCESS)
        val reported = mutableListOf<SessionResult>()

        val answer = ProfileRowSelection.answer(Futures.immediateFuture(switched)) { result -> reported += result }

        assertEquals(listOf(switched), reported)
        assertNotMedia(answer)
    }

    @Test
    fun `a refused switch reaches the car with its reason before the answer completes`() {
        val refusal = SessionResult(
            SessionError(SessionError.ERROR_PERMISSION_DENIED, "Unlock this profile in BookWave on your phone."),
        )
        val pending = SettableFuture.create<SessionResult>()
        val reported = mutableListOf<SessionResult>()

        val answer = ProfileRowSelection.answer(pending) { result -> reported += result }

        assertFalse(answer.isDone, "the request is not answered before the switch has finished")
        assertTrue(reported.isEmpty())

        pending.set(refusal)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, reported.single().sessionError?.code)
        assertNotMedia(answer)
    }

    @Test
    fun `a switch that throws is still never answered with media`() {
        val reported = mutableListOf<SessionResult>()
        val threw = Futures.immediateFailedFuture<SessionResult>(IllegalStateException("the switch threw"))

        val answer = ProfileRowSelection.answer(threw) { result -> reported += result }

        assertTrue(reported.isEmpty())
        assertIs<IllegalStateException>(failureOf(answer), "the switch's own failure is what Media3 sees")
    }

    @Test
    fun `a selection refused before any switch is not media either`() {
        assertNotMedia(ProfileRowSelection.refused())
    }

    @Test
    fun `Media3 leaves the loaded book alone when a profile row is answered`() {
        val player = ExoPlayer.Builder(context).build()
        val asked = AtomicInteger()
        val session = MediaSession.Builder(context, player)
            .setId("issue-174-profile-row")
            .setCallback(
                object : MediaSession.Callback {
                    override fun onSetMediaItems(
                        mediaSession: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        mediaItems: MutableList<MediaItem>,
                        startIndex: Int,
                        startPositionMs: Long,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        asked.incrementAndGet()
                        val switched = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        return ProfileRowSelection.answer(switched) {}
                    }
                },
            )
            .build()
        var controller: MediaController? = null
        try {
            player.setMediaItem(book())
            val live = LivePlaybackSession().apply { publish(session.token) }
            val attached = assertNotNull(
                runMedia3Connection {
                    SessionConnector(context, logger, live).connectExisting(object : MediaController.Listener {})
                },
            )
            controller = attached

            attached.setMediaItem(MediaItem.Builder().setMediaId("profile/prf_other").build())
            awaitMainLooper("the session must be asked to set the selected profile row") { asked.get() == 1 }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, player.mediaItemCount, "a profile row must not replace or clear the loaded book")
            assertEquals(BOOK_ID, player.currentMediaItem?.mediaId)
            assertFalse(player.playWhenReady, "a profile row must never start playback")
        } finally {
            controller?.release()
            session.release()
            player.release()
        }
    }

    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNotMedia(answer: ListenableFuture<MediaSession.MediaItemsWithStartPosition>) {
        assertIs<UnsupportedOperationException>(failureOf(answer), "Media3 reports this cause as not supported")
    }

    /** Why [answer] failed. An answer that completed with media fails the test, naming what it handed back. */
    private fun failureOf(answer: ListenableFuture<MediaSession.MediaItemsWithStartPosition>): Throwable? {
        assertTrue(answer.isDone, "the answer must be complete once the switch has finished")
        val failure = assertFailsWith<ExecutionException> {
            val media = answer.get()
            fail("A profile row was answered with media: ${media.mediaItems.map { item -> item.mediaId }}")
        }
        return failure.cause
    }

    /** As in `ExistingSessionAttachmentTest`: pump the main looper while a suspending Media3 call runs. */
    private fun awaitMainLooper(message: String, condition: () -> Boolean) {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var pumpsRemaining = MAX_MAIN_LOOPER_PUMPS
        while (!condition() && pumpsRemaining-- > 0) {
            mainLooper.idle()
            Thread.yield()
        }
        if (!condition()) throw AssertionError(message)
    }

    private fun <T> runMedia3Connection(block: suspend () -> T): T {
        val task = FutureTask<T> {
            runBlocking { block() }
        }
        Thread(task, "issue-174-media3-connection").apply {
            isDaemon = true
            start()
        }

        val mainLooper = shadowOf(Looper.getMainLooper())
        var pollsRemaining = MEDIA3_CONNECTION_MAX_POLLS
        while (!task.isDone && pollsRemaining-- > 0) {
            mainLooper.idle()
            LockSupport.parkNanos(MEDIA3_CONNECTION_POLL_NANOS)
        }
        if (!task.isDone) {
            task.cancel(true)
            throw AssertionError("Timed out connecting the test MediaController")
        }
        return task.get()
    }

    private fun book() = MediaItem.Builder()
        .setMediaId(BOOK_ID)
        .setUri("https://books.example/api/items/tidewatch/file/1")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("The Tidewatch Cycle")
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private companion object {
        const val BOOK_ID = "tidewatch"
        const val MAX_MAIN_LOOPER_PUMPS = 10_000
        const val MEDIA3_CONNECTION_MAX_POLLS = 5_000
        const val MEDIA3_CONNECTION_POLL_NANOS = 1_000_000L
    }
}
