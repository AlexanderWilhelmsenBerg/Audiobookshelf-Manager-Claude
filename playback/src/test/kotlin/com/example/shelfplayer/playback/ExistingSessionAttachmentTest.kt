package com.example.shelfplayer.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull

/**
 * Issue #75 — a fresh phone-side controller can recover the already-live session instead of waiting for
 * the UI to issue a new playback command.
 *
 * This is a real Media3 session/controller connection under Robolectric. It deliberately does not construct
 * [PlaybackService]: the only attachable thing is [MediaSession.token], which is exactly the distinction the
 * production idle-launch guard relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ExistingSessionAttachmentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = object : Logger {
        override fun log(event: LogEvent) = Unit
    }
    private val listener = object : MediaController.Listener {}

    @Test
    fun `fresh controller recovers the loaded book without issuing Play`() {
        val live = LivePlaybackSession()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        var controller: MediaController? = null
        try {
            val item = book()
            player.setMediaItem(item)
            live.publish(session.token)

            assertNull(PlaybackUiState.Idle.bookId)
            assertFalse(player.playWhenReady)

            controller = assertNotNull(connectExisting(SessionConnector(context, logger, live)))
            val recovered = controller.playbackUiState()

            assertEquals(BOOK, recovered.bookId)
            assertEquals("The Tidewatch Cycle", recovered.title)
            assertFalse(recovered.isPlaying)
            assertEquals(item.mediaId, player.currentMediaItem?.mediaId)
            assertEquals(1, player.mediaItemCount)
            assertFalse(player.playWhenReady, "attaching must observe the session, not send Play")
        } finally {
            controller?.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `a second UI controller recovers the same live session after recreation`() {
        val live = LivePlaybackSession()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        var first: MediaController? = null
        var recreated: MediaController? = null
        try {
            player.setMediaItem(book())
            live.publish(session.token)
            val connector = SessionConnector(context, logger, live)

            first = assertNotNull(connectExisting(connector))
            assertEquals(BOOK, first.playbackUiState().bookId)
            first.release()
            first = null

            recreated = assertNotNull(connectExisting(connector))

            assertEquals(BOOK, recreated.playbackUiState().bookId)
            assertEquals(1, player.mediaItemCount, "reattachment must not replace the loaded queue")
            assertFalse(player.playWhenReady)
        } finally {
            first?.release()
            recreated?.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `clearing the live session removes the recovered book from UI state`() {
        val live = LivePlaybackSession()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        var controller: MediaController? = null
        try {
            player.setMediaItem(book())
            live.publish(session.token)
            controller = assertNotNull(SessionConnector(context, logger, live).connectExisting(listener))
            assertEquals(BOOK, controller.playbackUiState().bookId)

            player.clearMediaItems()

            assertNull(controller.playbackUiState().bookId)
        } finally {
            controller?.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `no live token means foreground observation does not build a controller`() {
        val live = LivePlaybackSession()
        val connector = SessionConnector(context, logger, live)

        assertNull(live.currentToken())
        assertNull(connectExisting(connector))
        assertNull(live.currentToken())
    }

    @Test
    fun `a stale observed token cannot attach after the service replaces its session`() {
        val live = LivePlaybackSession()
        val firstPlayer = ExoPlayer.Builder(context).build()
        val replacementPlayer = ExoPlayer.Builder(context).build()
        var firstSession: MediaSession? = null
        var replacementSession: MediaSession? = null
        try {
            val first = MediaSession.Builder(context, firstPlayer)
                .setId("issue-75-stale")
                .build()
                .also { firstSession = it }
            val replacement = MediaSession.Builder(context, replacementPlayer)
                .setId("issue-75-replacement")
                .build()
                .also { replacementSession = it }

            live.publish(first.token)
            val stale = first.token
            live.publish(replacement.token)

            val connector = SessionConnector(context, logger, live)

            assertNull(
                connectExisting(connector, stale),
                "an Activity callback for an older session must not reconnect after replacement",
            )
            assertEquals(replacement.token, live.currentToken())
        } finally {
            replacementSession?.release()
            firstSession?.release()
            replacementPlayer.release()
            firstPlayer.release()
        }
    }

    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connectExisting(connector: SessionConnector): MediaController? =
        runMedia3Connection { connector.connectExisting(listener) }

    private fun connectExisting(connector: SessionConnector, token: SessionToken): MediaController? =
        runMedia3Connection { connector.connectExisting(token, listener) }

    /**
     * Media3 posts direct-session connection work to the controller/session application looper.
     *
     * Robolectric PAUSED mode deliberately does not run that looper for us, so execute the suspending client
     * call off the test thread while this thread pumps the main looper. The bounded wait turns a broken
     * connection into a normal test failure instead of wedging the entire Gradle test worker.
     */
    private fun <T> runMedia3Connection(block: suspend () -> T): T {
        val task = FutureTask<T> {
            runBlocking { block() }
        }
        Thread(task, "issue-75-media3-connection").apply {
            isDaemon = true
            start()
        }

        val mainLooper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!task.isDone && System.nanoTime() < deadline) {
            mainLooper.idle()
            Thread.yield()
        }
        if (!task.isDone) {
            task.cancel(true)
            throw AssertionError("Timed out connecting the test MediaController")
        }
        return task.get()
    }

    private fun book() = MediaItem.Builder()
        .setMediaId(BOOK.value)
        .setUri("https://books.example/api/items/tidewatch/file/1")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("The Tidewatch Cycle")
                .setArtist("Marisol Holt")
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
    }
}
