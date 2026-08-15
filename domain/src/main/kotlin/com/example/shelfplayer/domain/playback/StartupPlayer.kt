package com.example.shelfplayer.domain.playback

import com.example.shelfplayer.core.model.LibraryItemId

/**
 * PRODUCT_SPEC ROUTE-003 — the two things a startup mode can ask the player to do.
 *
 * A seam, for the reason `SmartDownload` is one: the real player lives in `:playback`, which depends on
 * `:domain`, so a use case that named it would invert the dependency. The default implementation does
 * nothing, so a graph with none bound behaves exactly as `StartupMode.OnMediaCommand` does — which is also
 * the default, and means the safe outcome is what a missing binding produces.
 */
interface StartupPlayer {

    /** Loads the book and leaves it paused. */
    suspend fun arm(bookId: LibraryItemId)

    /** Loads the book and plays it. */
    suspend fun play(bookId: LibraryItemId)

    companion object {
        /** What a graph with no player bound uses: nothing happens, which is the default mode anyway. */
        val None: StartupPlayer = object : StartupPlayer {
            override suspend fun arm(bookId: LibraryItemId) = Unit

            override suspend fun play(bookId: LibraryItemId) = Unit
        }
    }
}
