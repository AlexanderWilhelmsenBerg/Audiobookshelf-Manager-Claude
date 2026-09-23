package com.example.shelfplayer

import org.junit.Test
import java.io.File
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
    fun `every started Activity observes existing and replacement sessions`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/MainActivity.kt").readText()
        val onCreate = source
            .substringAfter("override fun onCreate")
            .substringBefore("setContent {")

        assertTrue("repeatOnLifecycle(Lifecycle.State.STARTED)" in onCreate)
        assertEquals(
            1,
            onCreate.split("playbackController.observeExistingSessions()").size - 1,
            "notification entry, warm return, recreation and live replacement must share one lifecycle seam",
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
