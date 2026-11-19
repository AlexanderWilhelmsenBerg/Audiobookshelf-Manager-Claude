package com.example.shelfplayer.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFocusDiagnosticsTest {
    private val clock = TestAppClock()
    private val records = mutableListOf<List<LogField>>()
    private val observer = PlaybackFocusDiagnostics(clock, records::add)
    private var ready = true
    private var playing = true
    private var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private val player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPlayWhenReady" -> ready
            "isPlaying" -> playing
            "getPlaybackSuppressionReason" -> suppression
            "getPlaybackState" -> Player.STATE_READY
            else -> error("Diagnostic must not call ${method.name}")
        }
    } as Player

    @Test
    fun `loss or denial is not falsely labelled a raw permanent loss or car requester`() {
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals("loss-or-denial", field("focusObservation"))
        assertEquals("media3-derived", field("focusEvidence"))
        assertEquals("unavailable", field("androidFocusChange"))
        assertEquals("unknown", field("focusRequester"))
        assertEquals("not-exposed", field("focusRequesterEvidence"))
    }

    @Test
    fun `temporary suppression and its clearance are not raw duck or gain claims`() {
        playing = false
        suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        events(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
        assertEquals("transient-suppression", field("focusObservation"))
        clock.advanceBy(250.milliseconds)
        suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        events(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
        assertEquals("suppression-cleared", field("focusObservation"))
        assertEquals(250L, millis("sinceFocusEvent"))
        assertEquals("unavailable", field("androidFocusChange"))
        playing = true
        events(Player.EVENT_IS_PLAYING_CHANGED)
        assertEquals("playback-active", field("focusObservation"))
        assertEquals("not-measured", field("audibility"))
    }

    @Test
    fun `duplicate focus notifications keep the first interruption clock`() {
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        clock.advanceBy(4300.milliseconds)
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals(4300L, millis("sinceFocusEvent"))
        assertEquals("1", field("focusSequence"))
        playing = true
        ready = true
        events(Player.EVENT_IS_PLAYING_CHANGED)
        assertEquals(4300L, millis("sinceFocusEvent"))
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals("2", field("focusSequence"))
        assertEquals(0L, millis("sinceFocusEvent"))
    }

    @Test
    fun `user pause terminates correlation and later play is not labelled focus recovery`() {
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals("transport-paused", field("focusObservation"))
        val size = records.size
        playing = true
        transport(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        events(Player.EVENT_IS_PLAYING_CHANGED)
        assertEquals(size, records.size)
    }

    @Test
    fun `media change ends correlation and recreated listener has no old interruption`() {
        transport(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        events(Player.EVENT_MEDIA_ITEM_TRANSITION)
        assertEquals("media-transition", field("focusObservation"))
        val next = PlaybackFocusDiagnostics(clock, records::add)
        val size = records.size
        playing = true
        next.onEvents(player, eventSet(Player.EVENT_IS_PLAYING_CHANGED))
        assertEquals(size, records.size)
    }

    @Test
    fun `unrelated state changes and unknown suppression do not invent focus loss`() {
        events(Player.EVENT_PLAYBACK_STATE_CHANGED)
        suppression = 9876
        playing = false
        events(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
        assertTrue(records.isEmpty())
    }

    private fun transport(value: Boolean, reason: Int) {
        ready = value
        if (!value) playing = false
        observer.onPlayWhenReadyChanged(value, reason)
        events(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    }

    private fun events(vararg flags: Int) = observer.onEvents(player, eventSet(*flags))

    private fun eventSet(vararg flags: Int) = Player.Events(FlagSet.Builder().addAll(*flags).build())

    private fun field(key: String) = records.last().filterIsInstance<LogField.Public>().single { it.key == key }.value

    private fun millis(key: String) = records.last().filterIsInstance<LogField.Millis>().single { it.key == key }.value
}
