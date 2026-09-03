package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * PRODUCT_SPEC SYNC-002 / PLAY-001 — the session command that adopts another device's position.
 *
 * ### Why adopting a position is a command and not three calls
 *
 * Everything else the app asks of the player is a single transport action that Media3 already exposes:
 * play, pause, seek, skip. Adopting a remote position is not one of those — it is *seek, confirm the player
 * got there, and only then play*, with an answer, and the confirmation has to happen on the side that owns
 * the player. Expressed as separate controller calls it was three round trips over a binder with no way to
 * tell a dropped seek from a slow one, which is the defect `AtomicResume.kt` describes.
 *
 * As a command it is one round trip, the whole operation happens inside `PlaybackService` on its own
 * `ExoPlayer`, and the `SessionResult` code says what happened.
 *
 * ### Not a notification button, which is why it lives here
 *
 * [NotificationButtons] holds the actions a listener can press. This one has no button and no icon: it is
 * the app's own private instruction to its own service. It is granted alongside them because it is granted
 * on the same condition — a trusted, first-party controller — and refused to everything else, since a seek
 * plus a play driven from outside the app is exactly the pair `ControllerTrust` withholds.
 */
internal object ResumeCommand {

    /** The action, namespaced like the rest so a stray broadcast cannot collide with it. */
    const val ACTION = "com.example.shelfplayer.playback.RESUME_AT"

    /** The position to adopt, in milliseconds on the book's own timeline (ADR-0016). */
    private const val EXTRA_POSITION_MS = "com.example.shelfplayer.playback.extra.POSITION_MS"

    /**
     * The book the caller believes is loaded.
     *
     * Carried so the service can refuse a resume that raced a book change rather than seeking the *new*
     * book to the old one's position. The controller cannot check this for itself: by the time its answer
     * came back the queue could have changed again.
     */
    private const val EXTRA_BOOK_ID = "com.example.shelfplayer.playback.extra.BOOK_ID"

    /** The command, with an empty extras template as Media3 requires. */
    fun command(): SessionCommand = SessionCommand(ACTION, Bundle.EMPTY)

    /** The arguments for one resume. */
    fun argsFor(bookId: String, positionMillis: Long): Bundle = Bundle().apply {
        putString(EXTRA_BOOK_ID, bookId)
        putLong(EXTRA_POSITION_MS, positionMillis)
    }

    /** The book id the caller sent, or `null` when the bundle is not one of ours. */
    fun bookIdFrom(args: Bundle): String? = args.getString(EXTRA_BOOK_ID)

    /** The position the caller sent, clamped, or `null` when it is absent. */
    fun positionFrom(args: Bundle): Long? =
        args.getLong(EXTRA_POSITION_MS, ABSENT).takeIf { it != ABSENT }?.coerceAtLeast(0L)

    private const val ABSENT = Long.MIN_VALUE
}
