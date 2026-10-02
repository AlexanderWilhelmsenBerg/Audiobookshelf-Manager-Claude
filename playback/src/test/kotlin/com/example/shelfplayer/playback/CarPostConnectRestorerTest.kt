package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.model.LibraryItemId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #88 / #185 — behavioural proof of the idle Android Auto restore, against a real ExoPlayer.
 *
 * [IdleResumeWiringTest] only proves the service delegates here; these tests prove what delegation does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPostConnectRestorerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var locked = false
    private val held = AutoLibrary.HeldResume(item("held"), HELD_START_MS)
    private val queue = MediaItems.Queue(item("queued"), QUEUE_START_MS)
    private var openedQueues = 0

    private val quiet = object : CarPostConnectRestorer.Observer {
        override fun note(message: String, player: Player?) = Unit

        override fun installing(direction: String, item: MediaItem, extraFields: List<LogField>) = Unit
    }

    private fun restorer(
        lastPlayed: suspend () -> LibraryItemId? = { BOOK },
        heldResume: suspend () -> AutoLibrary.HeldResume? = { held },
        openQueue: suspend (LibraryItemId) -> MediaItems.Queue? = {
            openedQueues++
            queue
        },
    ) = CarPostConnectRestorer(
        isProfileLocked = { locked },
        lastPlayedBookId = lastPlayed,
        heldResume = heldResume,
        openQueue = openQueue,
        observer = quiet,
    )

    @After
    fun release() {
        player.release()
    }

    @Test
    fun `Never policy installs the holder without preparing or playing`() = runBlocking {
        restorer().restore(AutoStartAction.None, player)

        assertEquals(1, player.mediaItemCount)
        assertEquals("held", player.currentMediaItem?.mediaId)
        assertEquals(HELD_START_MS, player.currentPosition)
        assertEquals(Player.STATE_IDLE, player.playbackState, "the holder must not be prepared")
        assertFalse(player.playWhenReady)
        assertEquals(0, openedQueues, "the holder must not open a /play session")
    }

    @Test
    fun `an existing item is kept untouched`() = runBlocking {
        player.setMediaItem(item("existing"))

        for (action in listOf(AutoStartAction.None, AutoStartAction.Arm, AutoStartAction.ArmAndPlay)) {
            restorer().restore(action, player)
        }

        assertEquals(1, player.mediaItemCount)
        assertEquals("existing", player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test
    fun `a locked profile installs nothing under any policy`() = runBlocking {
        locked = true

        for (action in AutoStartAction.entries) {
            restorer().restore(action, player)
        }

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `Arm installs and prepares without playing`() = runBlocking {
        restorer().restore(AutoStartAction.Arm, player)

        assertEquals("queued", player.currentMediaItem?.mediaId)
        assertEquals(QUEUE_START_MS, player.currentPosition)
        assertTrue(player.playbackState != Player.STATE_IDLE, "Arm must prepare")
        assertFalse(player.playWhenReady, "Arm must stay silent")
    }

    @Test
    fun `ArmAndPlay installs prepares and plays`() = runBlocking {
        restorer().restore(AutoStartAction.ArmAndPlay, player)

        assertEquals("queued", player.currentMediaItem?.mediaId)
        assertTrue(player.playbackState != Player.STATE_IDLE)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `Suppressed installs nothing`() = runBlocking {
        restorer().restore(AutoStartAction.Suppressed, player)

        assertEquals(0, player.mediaItemCount)
        assertEquals(0, openedQueues)
    }

    @Test
    fun `no resumable book installs nothing`() = runBlocking {
        restorer(lastPlayed = { null }, heldResume = { null }).restore(AutoStartAction.Arm, player)
        restorer(lastPlayed = { null }, heldResume = { null }).restore(AutoStartAction.None, player)

        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `a candidate that appears only after the refresh is used`() = runBlocking {
        var refreshed = false
        val late = restorer(
            lastPlayed = {
                refreshed = true
                BOOK
            },
        )

        late.restore(AutoStartAction.Arm, player)

        assertTrue(refreshed)
        assertEquals("queued", player.currentMediaItem?.mediaId)
    }

    @Test
    fun `a holder candidate resolved after the refresh is installed`() = runBlocking {
        var refreshed = false
        val late = restorer(
            heldResume = {
                refreshed = true
                held
            },
        )

        late.restore(AutoStartAction.None, player)

        assertTrue(refreshed)
        assertEquals("held", player.currentMediaItem?.mediaId)
    }

    @Test
    fun `a player filled while the queue was opening is not overwritten`() = runBlocking {
        val racing = restorer(
            openQueue = {
                player.setMediaItem(item("raced"))
                queue
            },
        )

        racing.restore(AutoStartAction.ArmAndPlay, player)

        assertEquals("raced", player.currentMediaItem?.mediaId)
        assertEquals(1, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `a player filled while the holder was resolving is not overwritten`() = runBlocking {
        val racing = restorer(
            heldResume = {
                player.setMediaItem(item("raced"))
                held
            },
        )

        racing.restore(AutoStartAction.None, player)

        assertEquals("raced", player.currentMediaItem?.mediaId)
        assertEquals(1, player.mediaItemCount)
    }

    @Test
    fun `a profile locked while the queue was opening installs nothing`() = runBlocking {
        val racing = restorer(
            openQueue = {
                locked = true
                queue
            },
        )

        racing.restore(AutoStartAction.ArmAndPlay, player)

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        const val HELD_START_MS = 4_000L
        const val QUEUE_START_MS = 9_000L

        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri("https://books.example/$id").build()
    }
}
