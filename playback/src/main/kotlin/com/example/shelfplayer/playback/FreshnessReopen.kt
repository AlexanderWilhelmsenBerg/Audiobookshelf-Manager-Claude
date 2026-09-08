package com.example.shelfplayer.playback

/**
 * Two-phase guard for the rare fallback after a remote-position seek could not be confirmed.
 *
 * [openCandidate] is deliberately side-effect free with respect to BookWave's loaded-book state. The first
 * ownership check happens in [prepareIfCurrent], before BookChanges is committed. [applyIfCurrent] performs
 * the final generation/profile/book check in the same player-thread turn as installing and playing the
 * replacement item. Returning `false` means a newer command won and the old Play must do nothing further.
 */
internal suspend fun <T> guardedFreshnessReopen(
    openCandidate: suspend () -> T?,
    prepareIfCurrent: suspend (T) -> Boolean,
    applyIfCurrent: suspend (T) -> Boolean,
): Boolean {
    val candidate = openCandidate() ?: return false
    if (!prepareIfCurrent(candidate)) return false
    return applyIfCurrent(candidate)
}
