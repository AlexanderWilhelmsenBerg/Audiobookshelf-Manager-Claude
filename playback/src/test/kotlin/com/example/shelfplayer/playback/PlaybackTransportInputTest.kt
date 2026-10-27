package com.example.shelfplayer.playback

import androidx.media3.common.Player
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exact Media3 raw event interpretation shared by every service-side transport path. */
internal class PlaybackTransportInputTest {
    @Test
    fun `local safety pause excludes rewind but does not invent a focus interruption`() {
        val safety = PlaybackTransportInput.interpret(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            routeSafetyPause = true,
        )
        assertFalse(safety.isUserPause)
        assertNull(safety.interruptionReason)
        assertEquals("routeSafety", safety.reasonName)

        val focus = PlaybackTransportInput.interpret(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            routeSafetyPause = true,
        )
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, focus.interruptionReason)
        assertEquals("audioFocusLoss", focus.reasonName)
        assertEquals(
            "userRequest",
            PlaybackTransportInput.interpret(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, true).reasonName,
        )
    }

    @Test
    fun `every Play supersedes pending continuity regardless of reason`() {
        val reasons = listOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            -99,
        )
        reasons.forEach { reason ->
            val result = PlaybackTransportInput.interpret(playWhenReady = true, reason)
            assertTrue(result.isPlay)
            assertFalse(result.isUserPause)
            assertNull(result.interruptionReason)
        }
    }

    @Test
    fun `focus loss and noisy are platform pauses rather than listener intent`() {
        val focus = PlaybackTransportInput.interpret(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
        )
        assertFalse(focus.isUserPause)
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, focus.interruptionReason)
        assertEquals("audioFocusLoss", focus.reasonName)

        val noisy = PlaybackTransportInput.interpret(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertFalse(noisy.isUserPause)
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY, noisy.interruptionReason)
        assertEquals("becomingNoisy", noisy.reasonName)
    }

    @Test
    fun `user and remote pauses reset recovery but do not identify a physical button`() {
        listOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
        ).forEach { reason ->
            val result = PlaybackTransportInput.interpret(false, reason)
            assertFalse(result.isPlay)
            assertTrue(result.isUserPause)
            assertNull(result.interruptionReason)
        }
    }

    @Test
    fun `end and unknown pauses do not invent focus events or listener intent`() {
        listOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG,
            -99,
        ).forEach { reason ->
            val result = PlaybackTransportInput.interpret(false, reason)
            assertFalse(result.isPlay)
            assertFalse(result.isUserPause)
            assertNull(result.interruptionReason)
        }
        assertEquals("unknown", PlaybackTransportInput.interpret(false, -99).reasonName)
    }
}
