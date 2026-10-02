package com.example.shelfplayer.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Issue #91 — direct coverage of the Media3 forwarding boundary controllers actually call. */
@RunWith(RobolectricTestRunner::class)
class ResumeFreshnessPlayerTest {

    @Test
    fun `fresh server first Play bypasses freshness exactly once`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        var preparations = 0
        var freshStarts = 0
        val player = forwarding(
            delegate = delegate,
            prepare = { preparations += 1 },
            consumeFreshStart = {
                freshStarts += 1
                true
            },
        )

        await(player.handleSetPlayWhenReady(true))

        assertEquals(0, preparations)
        assertEquals(1, freshStarts)
        assertEquals(listOf("delegate:play=true"), delegate.events)
    }

    @Test
    fun `armed or paused loaded Play enters freshness`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        var preparations = 0
        val player = forwarding(
            delegate = delegate,
            prepare = { preparations += 1 },
            consumeFreshStart = { false },
        )

        await(player.handleSetPlayWhenReady(true))

        assertEquals(1, preparations)
        assertTrue(delegate.events.isEmpty())
    }

    @Test
    fun `Pause invalidates before it is forwarded`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true)
        val player = forwarding(
            delegate = delegate,
            invalidate = { origin -> delegate.events += "invalidate:$origin" },
        )

        await(player.handleSetPlayWhenReady(false))

        assertEquals(
            listOf("invalidate:Pause", "delegate:play=false"),
            delegate.events,
        )
    }

    @Test
    fun `duplicate Pause still exposes newer listener intent before forwarding`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val player = forwarding(
            delegate = delegate,
            onPlayWhenReadyRequest = { requested -> delegate.events += "intent:$requested" },
            invalidate = { origin -> delegate.events += "invalidate:$origin" },
        )

        await(player.handleSetPlayWhenReady(false))

        assertEquals(
            listOf("intent:false", "invalidate:Pause", "delegate:play=false"),
            delegate.events,
        )
    }

    @Test
    fun `Play exposes newer listener intent before freshness preparation`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val player = forwarding(
            delegate = delegate,
            onPlayWhenReadyRequest = { requested -> delegate.events += "intent:$requested" },
            prepare = { delegate.events += "prepare" },
        )

        await(player.handleSetPlayWhenReady(true))

        assertEquals(listOf("intent:true", "prepare"), delegate.events)
    }

    @Test
    fun `Play revokes grace intent before freshness is allowed to suspend`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val preparation = SettableFuture.create<Unit>()
        var graceAuthorized = true
        var graceResumeAttempts = 0
        val player = ResumeFreshnessPlayer(
            delegate = delegate.player,
            preparePlay = { preparation },
            consumeFreshStart = { false },
            invalidate = {},
            onPlayWhenReadyRequest = { requested ->
                if (requested) graceAuthorized = false
            },
            skipIntervals = { SkipIntervals(back = 30.seconds, forward = 30.seconds) },
        )

        val play = player.handleSetPlayWhenReady(true)

        assertFalse(graceAuthorized, "the newer Play must revoke sleep-grace authority before freshness starts")
        if (graceAuthorized) graceResumeAttempts += 1 // faithful representation of a shake racing this Play
        assertEquals(0, graceResumeAttempts)
        assertFalse(play.isDone, "freshness remains suspended while grace is already revoked")

        preparation.set(Unit)
        await(play)
        assertTrue(delegate.events.isEmpty(), "ResumeFreshnessPlayer itself still does not issue raw Play here")
    }

    @Test
    fun `seek media replacement and Stop invalidate before forwarding`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val player = forwarding(
            delegate = delegate,
            invalidate = { origin -> delegate.events += "invalidate:$origin" },
        )
        val replacement = listOf(MediaItem.Builder().setMediaId("replacement").build())

        await(player.handleSeek(0, 1_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        await(player.handleReplaceMediaItems(0, 1, replacement))
        await(player.handleStop())

        assertEquals(ResumeInvalidation.Seek, invalidationBefore(delegate.events, "delegate:seek"))
        assertEquals(ResumeInvalidation.MediaChanged, invalidationBefore(delegate.events, "delegate:replace"))
        assertEquals(ResumeInvalidation.Stop, invalidationBefore(delegate.events, "delegate:stop"))
    }

    @Test
    fun `setting a different media item invalidates the pending freshness request`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val invalidations = mutableListOf<ResumeInvalidation>()
        val player = forwarding(delegate = delegate, invalidate = invalidations::add)

        await(
            player.handleSetMediaItems(
                listOf(MediaItem.Builder().setMediaId("other").build()),
                startIndex = 0,
                startPositionMs = 0L,
            ),
        )

        assertEquals(listOf(ResumeInvalidation.MediaChanged), invalidations)
    }

    @Test
    fun `empty session Play preserves Media3 playback resumption`() {
        val delegate = RecordingDelegate(mediaItemCount = 0, playWhenReady = false)
        var preparations = 0
        var freshTokenRead = false
        val player = forwarding(
            delegate = delegate,
            prepare = { preparations += 1 },
            consumeFreshStart = {
                freshTokenRead = true
                false
            },
        )

        await(player.handleSetPlayWhenReady(true))

        assertEquals(0, preparations)
        assertFalse(freshTokenRead)
        assertEquals(listOf("delegate:play=true"), delegate.events)
    }

    @Test
    fun `duplicate Play while already committed does not start another freshness request`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true)
        var preparations = 0
        var freshTokenRead = false
        val player = forwarding(
            delegate = delegate,
            prepare = { preparations += 1 },
            consumeFreshStart = {
                freshTokenRead = true
                false
            },
        )

        await(player.handleSetPlayWhenReady(true))

        assertEquals(0, preparations)
        assertFalse(freshTokenRead)
        assertEquals(listOf("delegate:play=true"), delegate.events)
    }

    @Test
    fun `headset Previous seeks back by the configured interval and never restarts the book`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 109_662_699L)
        val player = forwarding(
            delegate = delegate,
            invalidate = { origin -> delegate.events += "invalidate:$origin" },
            skipIntervals = { SkipIntervals(back = 30.seconds, forward = 30.seconds) },
        )

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS))

        assertEquals(listOf(109_632_699L), delegate.seekPositions)
        assertTrue(delegate.relativeCalls.isEmpty(), "seekToPrevious must never reach the delegate")
        assertEquals(ResumeInvalidation.Seek, invalidationBefore(delegate.events, "delegate:seek"))
    }

    @Test
    fun `Previous near the start clamps to zero`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 10_000L)
        val player = forwarding(delegate = delegate)

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS))

        assertEquals(listOf(0L), delegate.seekPositions)
    }

    @Test
    fun `skip interval is read at press time`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 600_000L)
        var skips = SkipIntervals(back = 30.seconds, forward = 30.seconds)
        val player = forwarding(delegate = delegate, skipIntervals = { skips })

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS))
        skips = SkipIntervals(back = 10.seconds, forward = 30.seconds)
        delegate.currentPosition = 600_000L
        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS))

        assertEquals(listOf(570_000L, 590_000L), delegate.seekPositions)
    }

    @Test
    fun `SeekBack and SeekForward use their own intervals`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 100_000L)
        val player = forwarding(
            delegate = delegate,
            skipIntervals = { SkipIntervals(back = 15.seconds, forward = 45.seconds) },
        )

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_BACK))
        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_FORWARD))

        assertEquals(listOf(85_000L, 145_000L), delegate.seekPositions)
    }

    @Test
    fun `SeekToNext jumps forward by the configured interval`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 100_000L)
        val player = forwarding(delegate = delegate)

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_NEXT))

        assertEquals(listOf(130_000L), delegate.seekPositions)
    }

    @Test
    fun `absolute seek in the current item is forwarded unchanged`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true, currentPosition = 100_000L)
        val player = forwarding(delegate = delegate)

        await(player.handleSeek(0, 1_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))

        assertEquals(listOf(1_000L), delegate.seekPositions)
    }

    @Test
    fun `Previous on an empty player synthesizes no seek position`() {
        val delegate = RecordingDelegate(mediaItemCount = 0, playWhenReady = false, currentPosition = 50_000L)
        val player = forwarding(delegate = delegate)

        await(player.handleSeek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS))

        assertTrue(delegate.seekPositions.none { it == 20_000L }, "no position derived from the current one")
    }

    private fun forwarding(
        delegate: RecordingDelegate,
        prepare: () -> Unit = {},
        consumeFreshStart: () -> Boolean = { false },
        invalidate: (ResumeInvalidation) -> Unit = {},
        onPlayWhenReadyRequest: (Boolean) -> Unit = {},
        skipIntervals: () -> SkipIntervals = { SkipIntervals(back = 30.seconds, forward = 30.seconds) },
    ) = ResumeFreshnessPlayer(
        delegate = delegate.player,
        preparePlay = {
            prepare()
            Futures.immediateFuture(Unit)
        },
        consumeFreshStart = consumeFreshStart,
        invalidate = invalidate,
        onPlayWhenReadyRequest = onPlayWhenReadyRequest,
        skipIntervals = skipIntervals,
    )

    private fun await(future: ListenableFuture<*>) {
        val result = future.get()
        assertTrue(result == null || result == Unit, "Unexpected forwarding result: $result")
    }

    private fun invalidationBefore(events: List<String>, delegateEvent: String): ResumeInvalidation {
        val delegateIndex = events.indexOf(delegateEvent)
        assertTrue(delegateIndex > 0, "Expected $delegateEvent after an invalidation: $events")
        val value = events[delegateIndex - 1].removePrefix("invalidate:")
        return ResumeInvalidation.valueOf(value)
    }

    private class RecordingDelegate(
        private var mediaItemCount: Int,
        private var playWhenReady: Boolean,
        var currentPosition: Long = 0L,
    ) {
        val events = mutableListOf<String>()
        val seekPositions = mutableListOf<Long>()
        val relativeCalls = mutableListOf<String>()

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()

                "getMediaItemCount" -> mediaItemCount

                "getPlayWhenReady" -> playWhenReady

                "getCurrentPosition" -> currentPosition

                "getCurrentMediaItemIndex" -> 0

                "setPlayWhenReady" -> {
                    playWhenReady = args?.firstOrNull() as? Boolean ?: false
                    record("delegate:play=$playWhenReady")
                    Unit
                }

                "seekTo" -> {
                    args?.filterIsInstance<Long>()?.firstOrNull()?.let { seekPositions += it }
                    record("delegate:seek")
                    Unit
                }

                "seekToPrevious", "seekToNext", "seekBack", "seekForward" -> {
                    relativeCalls += method.name
                    Unit
                }

                "setMediaItems" -> {
                    mediaItemCount = (args?.firstOrNull() as? List<*>)?.size ?: mediaItemCount
                    record("delegate:setMedia")
                    Unit
                }

                "replaceMediaItem", "replaceMediaItems" -> {
                    record("delegate:replace")
                    Unit
                }

                "addMediaItems" -> {
                    record("delegate:add")
                    Unit
                }

                "removeMediaItems" -> {
                    record("delegate:remove")
                    Unit
                }

                "stop" -> {
                    record("delegate:stop")
                    Unit
                }

                else -> defaultValue(method.returnType)
            }
        } as Player

        private fun record(event: String) {
            events += event
        }
    }

    private companion object {
        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
