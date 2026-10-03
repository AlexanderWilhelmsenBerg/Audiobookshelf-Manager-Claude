package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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
    private var activeProfile: ProfileId? = ProfileId("first")
    private var profileGeneration = 0L
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
        isRequestCurrent: () -> Boolean = { true },
        activeProfileLookup: suspend () -> ProfileId? = { activeProfile },
        beforeAcceptance: () -> Unit = {},
        openQueue: suspend (LibraryItemId) -> MediaItems.Queue? = {
            openedQueues++
            queue
        },
    ) = CarPostConnectRestorer(
        activeProfileId = activeProfileLookup,
        profileGeneration = { profileGeneration },
        isRequestCurrent = isRequestCurrent,
        isProfileLocked = { locked },
        lastPlayedBookId = lastPlayed,
        heldResume = heldResume,
        openQueue = { bookId -> openQueue(bookId)?.let { CarPostConnectRestorer.Prepared(session(), it) } },
        acceptQueue = { _, authorized, install ->
            beforeAcceptance()
            if (authorized()) {
                install()
                true
            } else {
                false
            }
        },
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

    @Test
    fun `no active profile resolves no metadata or queue`() = runBlocking {
        activeProfile = null
        val absent = restorer(
            lastPlayed = { error("no candidate lookup without a profile") },
            heldResume = { error("no metadata lookup without a profile") },
        )

        for (action in AutoStartAction.entries) absent.restore(action, player)

        assertEquals(0, player.mediaItemCount)
        assertEquals(0, openedQueues)
    }

    @Test
    fun `switching unlocked profiles during holder resolution discards the old metadata`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(heldResume = {
                entered.complete(Unit)
                resume.await()
                held
            }).restore(AutoStartAction.None, player)
        }
        entered.await()
        activeProfile = ProfileId("second")
        resume.complete(Unit)
        restore.join()

        assertEquals(0, player.mediaItemCount)
        assertEquals(0, openedQueues)
    }

    @Test
    fun `switching unlocked profiles during candidate resolution does not open a queue`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(lastPlayed = {
                entered.complete(Unit)
                resume.await()
                BOOK
            }).restore(AutoStartAction.Arm, player)
        }
        entered.await()
        activeProfile = ProfileId("second")
        resume.complete(Unit)
        restore.join()

        assertEquals(0, openedQueues)
        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `switching unlocked profiles during queue opening does not install or play`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(openQueue = {
                entered.complete(Unit)
                resume.await()
                queue
            }).restore(AutoStartAction.ArmAndPlay, player)
        }
        entered.await()
        activeProfile = ProfileId("second")
        resume.complete(Unit)
        restore.join()

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `switching away and back during holder resolution discards the old request`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(heldResume = {
                entered.complete(Unit)
                resume.await()
                held
            }).restore(AutoStartAction.None, player)
        }
        entered.await()
        switchAwayAndBack()
        resume.complete(Unit)
        restore.join()

        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `switching away and back during candidate resolution cannot open a queue`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(lastPlayed = {
                entered.complete(Unit)
                resume.await()
                BOOK
            }).restore(AutoStartAction.Arm, player)
        }
        entered.await()
        switchAwayAndBack()
        resume.complete(Unit)
        restore.join()

        assertEquals(0, openedQueues)
        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `switching away and back during queue preparation cannot install or play`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(openQueue = {
                entered.complete(Unit)
                resume.await()
                queue
            }).restore(AutoStartAction.ArmAndPlay, player)
        }
        entered.await()
        switchAwayAndBack()
        resume.complete(Unit)
        restore.join()

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    private fun switchAwayAndBack() {
        activeProfile = ProfileId("second")
        profileGeneration += 1
        activeProfile = ProfileId("first")
        profileGeneration += 1
    }

    @Test
    fun `locking while final profile lookup is suspended prevents install`() = runBlocking {
        var accepting = false
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val restore = launch {
            restorer(
                activeProfileLookup = {
                    if (accepting) {
                        entered.complete(Unit)
                        resume.await()
                    }
                    activeProfile
                },
                beforeAcceptance = { accepting = true },
            ).restore(AutoStartAction.ArmAndPlay, player)
        }
        entered.await()
        locked = true
        resume.complete(Unit)
        restore.join()

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `a newer transport command during queue preparation keeps an idle player silent`() = runBlocking {
        var requestCurrent = true
        restorer(
            isRequestCurrent = { requestCurrent },
            openQueue = {
                requestCurrent = false
                queue
            },
        ).restore(AutoStartAction.ArmAndPlay, player)

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    private fun session() = PlaybackSession(
        id = "remote-test",
        profileId = ProfileId("first"),
        bookId = BOOK,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = Duration.ZERO,
        duration = 1.hours,
        tracks = emptyList(),
        chapters = emptyList(),
    )

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        const val HELD_START_MS = 4_000L
        const val QUEUE_START_MS = 9_000L

        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri("https://books.example/$id").build()
    }
}
