package com.example.shelfplayer

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #75 — the lifecycle path that makes the already-live MediaSession reachable from a recreated UI.
 *
 * The Media3 behavior itself is covered in :playback. These assertions pin the production call chain around
 * it so a perfectly tested helper cannot become dead code while the mini player silently regresses again.
 */
class PlaybackSessionEntryReachabilityTest {

    @Test
    fun `every Activity foreground entry asks to attach to an existing session`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/MainActivity.kt").readText()
        val onStart = source
            .substringAfter("override fun onStart()")
            .substringBefore("override fun onCreate")

        assertTrue("super.onStart()" in onStart)
        assertEquals(
            1,
            onStart.split("playbackController.attachToExistingSession()").size - 1,
            "notification entry, warm return and Activity recreation must all cross the existing-session seam",
        )
    }

    @Test
    fun `the global mini player is still driven only by loaded session state`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/feature/player/MiniPlayer.kt").readText()

        assertTrue(
            "visible = state.bookId != null" in source,
            "the lifecycle fix must feed the existing global visibility invariant, not add a second flag",
        )
    }
}
