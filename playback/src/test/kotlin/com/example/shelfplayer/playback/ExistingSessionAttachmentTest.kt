package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
@LooperMode(LooperMode.Mode.LEGACY)
class ExistingSessionAttachmentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = object : Logger {
        override fun log(event: LogEvent) = Unit
    }
    private val listener = object : MediaController.Listener {}

    @Test
    fun `fresh controller recovers the loaded book without issuing Play`() = runTest {
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

            controller = assertNotNull(SessionConnector(context, logger, live).connectExisting(listener))
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
    fun `a second UI controller recovers the same live session after recreation`() = runTest {
        val live = LivePlaybackSession()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        var first: MediaController? = null
        var recreated: MediaController? = null
        try {
            player.setMediaItem(book())
            live.publish(session.token)
            val connector = SessionConnector(context, logger, live)

            first = assertNotNull(connector.connectExisting(listener))
            assertEquals(BOOK, first.playbackUiState().bookId)
            first.release()
            first = null

            recreated = assertNotNull(connector.connectExisting(listener))

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
    fun `clearing the live session removes the recovered book from UI state`() = runTest {
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
    fun `no live token means foreground observation does not build a controller`() = runTest {
        val live = LivePlaybackSession()
        val connector = SessionConnector(context, logger, live)

        assertNull(live.currentToken())
        assertNull(connector.connectExisting(listener))
        assertNull(live.currentToken())
    }

    @Test
    fun `a stale observed token cannot attach after the service replaces its session`() = runTest {
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
                connector.connectExisting(stale, listener),
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
