package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #257 — coroutine interleavings against the Media3 player/guard used by PlaybackService.
 *
 * The deferred stages model /play and session-outbox suspensions: a later manual Media3 selection must
 * win even when stale work returns after it. No Android device or new test runner is needed in CI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DeferredPlaybackWorkTest {

    private lateinit var player: ExoPlayer
    private var command = 0L
    private var itemVersion = 0L
    private var profile = 0L
    private var commits = 0
    private var beforeAccept: suspend () -> Unit = {}

    private val work by lazy {
        DeferredPlaybackWork(
            currentPlayer = { player },
            commandGeneration = { command },
            itemGeneration = { itemVersion },
            profileGeneration = { profile },
            acceptPrepared = { _, authorized, install ->
                beforeAccept()
                if (authorized()) {
                    commits++
                    install()
                    true
                } else {
                    false
                }
            },
        )
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context).build()
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    itemVersion += 1
                }
            },
        )
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun `manual book selection beats a suspended next-in-series open`() = runTest {
        player.setMediaItem(item("finished"))
        val claim = requireNotNull(work.capture())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val advancing = launch(start = CoroutineStart.UNDISPATCHED) {
            entered.complete(Unit)
            release.await() // suspended NextInSeries / /play lookup
            work.installIfCurrent(claim, prepared(), startPlaying = true, condition = { true })
        }
        entered.await()
        player.setMediaItem(item("manual"))
        release.complete(Unit)
        advancing.join()

        assertEquals("manual", player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
        assertEquals(0, commits)
    }

    @Test
    fun `an automatic headset load cannot overwrite a book chosen during its lookup`() = runTest {
        val claim = requireNotNull(work.capture())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loading = launch(start = CoroutineStart.UNDISPATCHED) {
            entered.complete(Unit)
            release.await() // suspended lastPlayed / /play
            work.installIfCurrent(
                claim,
                prepared(),
                startPlaying = true,
                condition = { it.mediaItemCount == 0 },
            )
        }
        entered.await()
        player.setMediaItem(item("manual"))
        release.complete(Unit)
        loading.join()

        assertEquals("manual", player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
        assertEquals(0, commits)
    }

    @Test
    fun `empty again is not permission for an obsolete automatic load`() = runTest {
        val claim = requireNotNull(work.capture())
        player.setMediaItem(item("manual"))
        player.clearMediaItems()

        assertFalse(
            work.installIfCurrent(
                claim,
                prepared(),
                startPlaying = true,
                condition = { it.mediaItemCount == 0 },
            ),
        )
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
        assertEquals(0, commits)
    }

    @Test
    fun `a command or profile generation change invalidates a suspended candidate`() = runTest {
        val claim = requireNotNull(work.capture())
        command += 1
        assertFalse(work.installIfCurrent(claim, prepared(), false, { true }))

        val newer = requireNotNull(work.capture())
        profile += 1
        assertFalse(work.installIfCurrent(newer, prepared(), false, { true }))
        assertEquals(0, commits)
    }

    @Test
    fun `a newer selection wins while durable session acceptance is suspended`() = runTest {
        player.setMediaItem(item("finished"))
        val claim = requireNotNull(work.capture())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeAccept = {
            entered.complete(Unit)
            release.await()
        }

        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            work.installIfCurrent(claim, prepared(), startPlaying = true, condition = { true })
        }
        entered.await()
        player.setMediaItem(item("manual"))
        release.complete(Unit)
        pending.join()

        assertEquals("manual", player.currentMediaItem?.mediaId)
        assertEquals(0, commits, "an accepted outbox must never install a superseded queue")
    }

    @Test
    fun `an authorized empty-player arm installs but does not start playback`() = runTest {
        val claim = requireNotNull(work.capture())

        assertTrue(
            work.installIfCurrent(
                claim,
                prepared(),
                startPlaying = false,
                condition = { it.mediaItemCount == 0 },
            ),
        )
        assertEquals("automatic", player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
        assertEquals(1, commits)
    }

    @Test
    fun `an obsolete delayed retry cannot reprepare the replacement book`() = runTest {
        val fake = FailedPlayer()
        var generation = 0L
        val retry = DeferredPlaybackWork(
            currentPlayer = { fake.player },
            commandGeneration = { generation },
            itemGeneration = { 0L },
            profileGeneration = { 0L },
            acceptPrepared = { _, _, _ -> error("unused") },
        )
        val claim = requireNotNull(retry.capture())
        val delayed = launch { retry.retryIfCurrent(claim, 1.seconds) }
        runCurrent()

        fake.item = item("new-book")
        generation += 1
        advanceTimeBy(1_000)
        runCurrent()
        delayed.join()

        assertEquals(0, fake.prepares)
    }

    @Test
    fun `the same failed idle item is still eligible for its bounded retry`() = runTest {
        val fake = FailedPlayer()
        val retry = DeferredPlaybackWork(
            currentPlayer = { fake.player },
            commandGeneration = { 0L },
            itemGeneration = { 0L },
            profileGeneration = { 0L },
            acceptPrepared = { _, _, _ -> error("unused") },
        )
        val claim = requireNotNull(retry.capture())
        val delayed = launch { retry.retryIfCurrent(claim, 1.seconds) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        delayed.join()

        assertEquals(1, fake.prepares)
    }

    private fun prepared() = CarPostConnectRestorer.Prepared(
        session = PlaybackSession(
            id = "remote-test",
            profileId = ProfileId("profile"),
            bookId = LibraryItemId("automatic"),
            title = "Test",
            author = null,
            coverUrl = null,
            startAt = 0.seconds,
            duration = 1.hours,
            tracks = emptyList(),
            chapters = emptyList(),
        ),
        queue = MediaItems.Queue(item("automatic"), 0L),
    )

    private fun item(mediaId: String) = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri("https://example.invalid/test.mp3")
        .build()

    private inner class FailedPlayer {
        var item: MediaItem = this@DeferredPlaybackWorkTest.item("failed-book")
        var prepares = 0
        private val failure = PlaybackException(
            "test",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getMediaItemCount" -> 1
                "getPlaybackState" -> Player.STATE_IDLE
                "getPlayerError" -> failure
                "prepare" -> {
                    prepares++
                    null
                }

                "toString" -> "FailedPlayer"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> primitiveDefault(method.returnType)
            }
        } as Player
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
