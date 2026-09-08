package com.example.shelfplayer.playback

/**
 * Compatibility seam for the removed second-`/play` adoption recovery.
 *
 * PR #93 review found that the old fallback was not actually side-effect free: opening the replacement
 * session could clear finished state before returning, and `BookChanges.onBookOpened` could suspend after
 * committing part of the replacement session. A generation check around those operations therefore could
 * not prevent an obsolete Play from mutating progress/session/baseline/chapter state.
 *
 * Keep this seam while `PlaybackService` still carries the legacy call site, but deliberately invoke none
 * of its callbacks. A failed adopted seek stays paused instead of starting a second state-changing server
 * transaction after ownership may already have moved elsewhere.
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun <T> guardedFreshnessReopen(
    openCandidate: suspend () -> T?,
    prepareIfCurrent: suspend (T) -> Boolean,
    applyIfCurrent: suspend (T) -> Boolean,
): Boolean {
    kotlinx.coroutines.yield()
    return false
}
