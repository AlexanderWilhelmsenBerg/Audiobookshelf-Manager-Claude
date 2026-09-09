package com.example.shelfplayer.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        player.handleSetPlayWhenReady(true).get()

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

        player.handleSetPlayWhenReady(true).get()

        assertEquals(1, preparations)
        assertTrue(delegate.events.isEmpty())
    }

    @Test
    fun `Pause invalidates before it is forwarded`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = true)
        val events = mutableListOf<String>()
        delegate.externalEvents = events
        val player = forwarding(
            delegate = delegate,
            invalidate = { origin -> events += "invalidate:$origin" },
        )

        player.handleSetPlayWhenReady(false).get()

        assertEquals(
            listOf("invalidate:Pause", "delegate:play=false"),
            events,
        )
    }

    @Test
    fun `seek media replacement and Stop invalidate before forwarding`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val events = mutableListOf<String>()
        delegate.externalEvents = events
        val player = forwarding(
            delegate = delegate,
            invalidate = { origin -> events += "invalidate:$origin" },
        )
        val replacement = listOf(MediaItem.Builder().setMediaId("replacement").build())

        player.handleSeek(0, 1_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).get()
        player.handleReplaceMediaItems(0, 1, replacement).get()
        player.handleStop().get()

        assertEquals(ResumeInvalidation.Seek, invalidationBefore(events, "delegate:seek"))
        assertEquals(ResumeInvalidation.MediaChanged, invalidationBefore(events, "delegate:replace"))
        assertEquals(ResumeInvalidation.Stop, invalidationBefore(events, "delegate:stop"))
    }

    @Test
    fun `setting a different media item invalidates the pending freshness request`() {
        val delegate = RecordingDelegate(mediaItemCount = 1, playWhenReady = false)
        val invalidations = mutableListOf<ResumeInvalidation>()
        val player = forwarding(delegate = delegate, invalidate = invalidations::add)

        player.handleSetMediaItems(
            listOf(MediaItem.Builder().setMediaId("other").build()),
            startIndex = 0,
            startPositionMs = 0L,
        ).get()

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

        player.handleSetPlayWhenReady(true).get()

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

        player.handleSetPlayWhenReady(true).get()

        assertEquals(0, preparations)
        assertFalse(freshTokenRead)
        assertEquals(listOf("delegate:play=true"), delegate.events)
    }

    private fun forwarding(
        delegate: RecordingDelegate,
        prepare: () -> Unit = {},
        consumeFreshStart: () -> Boolean = { false },
        invalidate: (ResumeInvalidation) -> Unit = {},
    ) = ResumeFreshnessPlayer(
        delegate = delegate.player,
        preparePlay = {
            prepare()
            Futures.immediateFuture(Unit)
        },
        consumeFreshStart = consumeFreshStart,
        invalidate = invalidate,
    )

    private fun invalidationBefore(events: List<String>, delegateEvent: String): ResumeInvalidation {
        val delegateIndex = events.indexOf(delegateEvent)
        assertTrue(delegateIndex > 0, "Expected $delegateEvent after an invalidation: $events")
        val value = events[delegateIndex - 1].removePrefix("invalidate:")
        return ResumeInvalidation.valueOf(value)
    }

    private class RecordingDelegate(
        private var mediaItemCount: Int,
        private var playWhenReady: Boolean,
    ) {
        val events = mutableListOf<String>()
        var externalEvents: MutableList<String>? = null

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getMediaItemCount" -> mediaItemCount
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> {
                    playWhenReady = args?.firstOrNull() as? Boolean ?: false
                    record("delegate:play=$playWhenReady")
                    Unit
                }
                "seekTo" -> {
                    record("delegate:seek")
                    Unit
                }
                "setMediaItems" -> {
                    mediaItemCount = (args?.firstOrNull() as? List<*>)?.size ?: mediaItemCount
                    record("delegate:setMedia")
                    Unit
                }
                "replaceMediaItems" -> {
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
            externalEvents?.add(event)
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
