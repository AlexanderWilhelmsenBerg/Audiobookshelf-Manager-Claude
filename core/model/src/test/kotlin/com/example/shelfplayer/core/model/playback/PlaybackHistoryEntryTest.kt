package com.example.shelfplayer.core.model.playback

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-003 — where tapping a history row takes a listener.
 *
 * The list is the undo a seek never had, so for a jump the answer is "where you were before". The
 * exception is a row about **another device's** listening, and getting that one backwards is a defect a
 * device run found: it landed the listener at the position they had been at before the other device
 * played, which is indistinguishable from the app having ignored the other device.
 */
class PlaybackHistoryEntryTest {

    /** A seek is undone: tapping goes back to the position the jump replaced. */
    @Test
    fun `a jump returns to where it came from`() {
        assertEquals(2.hours, entry(PlaybackEvent.Seek, from = 2.hours, to = 3.hours).returnTo)
    }

    /** A marker has no "before", so its own position is the useful place to go. */
    @Test
    fun `a marker returns to its own position`() {
        assertEquals(3.hours, entry(PlaybackEvent.Pause, from = null, to = 3.hours).returnTo)
    }

    /**
     * **Another device's session returns to where it reached, not where it opened.**
     *
     * `from` is where that session started — which is where *this* device was beforehand. Returning there
     * is the one answer that undoes the very thing the row exists to report.
     */
    @Test
    fun `another device's session returns to where it reached`() {
        assertEquals(
            3.hours,
            entry(PlaybackEvent.ServerSession, from = 2.hours, to = 3.hours).returnTo,
        )
    }

    /**
     * A remote *progress* row keeps the undo, and that is deliberate.
     *
     * There `from` is where this device was when a sync moved it. The move has already been applied, so
     * "take me back to where I was" is a real thing to want and the only way back to it.
     */
    @Test
    fun `a remote progress row still undoes the move`() {
        assertEquals(
            2.hours,
            entry(PlaybackEvent.RemoteProgress, from = 2.hours, to = 3.hours).returnTo,
        )
    }

    private fun entry(event: PlaybackEvent, from: Duration?, to: Duration) = PlaybackHistoryEntry(
        id = "entry",
        event = event,
        from = from,
        to = to,
        detail = 20.minutes,
        at = Instant.ofEpochSecond(1_700_000_000),
    )
}
