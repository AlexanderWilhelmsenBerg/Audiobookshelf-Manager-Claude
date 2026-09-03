package com.example.shelfplayer.playback

/**
 * PRODUCT_SPEC SYNC-002 — the three commands a resume issues, and the order that makes them one thing.
 *
 * ### Why this is its own type rather than three lines inside the controller
 *
 * It is the ordering that was the defect. Adopting another device's position used to be two calls from the
 * ViewModel — `seekTo(remote)` then `togglePlayPause()` — each of which launched its **own** coroutine on
 * the main dispatcher, and `seekTo` reached for the cached `controller` while `togglePlayPause` called
 * `connect()`. So a resume after the controller had been released did nothing but play: the seek returned
 * early on a null controller, silently, and the listener heard the old position. Reported from a device,
 * with a screenshot showing a Play row at the stale position and no seek recorded anywhere.
 *
 * Media3's `MediaController` is final and cannot be faked, which is why `PlaybackController` has no unit
 * tests and why this defect survived review twice. Behind this interface the ordering is a pure function
 * over a recorder, so `ResumeSurfaceTest` can assert *prepare, then seek, then play* rather than a comment
 * claiming it.
 */
internal interface ResumeSurface {
    /** `true` when the player is idle or holding an error — the two states `play()` cannot leave. */
    val needsPreparing: Boolean

    fun prepare()

    fun seekTo(positionMillis: Long)

    fun play()
}

/**
 * Resumes, at [positionMillis] when there is one to adopt.
 *
 * The order is the whole point:
 *
 *  1. **prepare if needed** — a seek on an idle player is discarded, so this cannot come after;
 *  2. **seek** — before any audio, so nothing is heard from the position being left behind;
 *  3. **play** — last, so the first sound is from where the listener should be.
 *
 * `null` means keep the current position and simply resume, which is the ordinary case: most Plays have no
 * other device to catch up with.
 *
 * Negative positions are clamped rather than rejected. A server that reports a nonsense position should
 * start the book, not fail the resume — product priority 1.
 */
internal fun ResumeSurface.resumeAt(positionMillis: Long?) {
    if (needsPreparing) prepare()
    if (positionMillis != null) seekTo(positionMillis.coerceAtLeast(0L))
    play()
}
