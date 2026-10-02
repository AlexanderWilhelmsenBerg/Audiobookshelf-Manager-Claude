package com.example.shelfplayer.playback

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #174 — source-level reachability proof that the live Media3 callbacks take the profile-row path.
 *
 * `AutoBrowseTreeTest`, `AutoLibraryTest` and `ProfileRowSelectionTest` prove what a profile row is and what
 * it is answered with. These prove the service and the hand-over are actually wired to them — the distinction
 * docs/risks.md R-43 requires, because a policy that is tested and never called looks exactly like a fix.
 */
class CarProfileSelectionWiringTest {

    @Test
    fun `a selected profile row is routed before any media resolution can run`() {
        val setMediaItems = serviceSource()
            .substringAfter("override fun onSetMediaItems(")
            .substringBefore("private fun selectProfileFromRow(")

        val selection = setMediaItems.indexOf("AutoLibrary.profileSelectionOf(mediaItems)")
        val resolution = setMediaItems.indexOf("setMediaItems(session, controller, mediaItems")
        assertTrue(selection >= 0, "onSetMediaItems must recognise a selected profile row")
        assertTrue(resolution > selection, "a profile row must be handled before book resolution")
    }

    @Test
    fun `a profile row is answered only through the shared switch and never with media`() {
        val select = serviceSource()
            .substringAfter("private fun selectProfileFromRow(")
            .substringBefore("private suspend fun setMediaItems(")

        assertTrue("session.mayBrowse(controller)" in select, "only a library-capable controller may switch")
        assertTrue("ProfileRowSelection.refused()" in select)
        assertTrue("ProfileRowSelection.answer(" in select)
        assertTrue("switched = future { switchProfileFromCar(profileId) }" in select)
        assertTrue("whenSwitchThrows = {" in select, "a throwing switch must still reach the car")
        assertFalse("MediaItemsWithStartPosition(" in select, "a profile row must never be answered with media")
    }

    @Test
    fun `re-selecting the active profile is decided inside the serialised switch`() {
        val switch = serviceSource()
            .substringAfter("private suspend fun switchProfileFromCar(")
            .substringBefore("private suspend fun switchTo(")

        val locked = switch.indexOf("carProfileSwitch.withLock")
        val active = switch.indexOf("auto.isActiveProfile(profileId)")
        val switched = switch.indexOf("switchTo(profileId)")
        assertTrue(locked >= 0, "car profile switches must be serialised")
        assertTrue(active > locked, "the active-profile check must run inside the lock")
        assertTrue(switched > active, "only a profile that is not already active is switched to")
    }

    @Test
    fun `the hand-over attaches to a live session before it pauses and flushes`() {
        val handOver = controllerSource()
            .substringAfter("suspend fun handOver(outgoing: ProfileId)")
            .substringBefore("private suspend fun attachForHandOver()")

        val attach = handOver.indexOf("attachForHandOver()")
        val pause = handOver.indexOf("media.pause()")
        assertTrue(attach >= 0, "a car-only session must still be handed over")
        assertTrue(pause > attach, "the attach must happen before the pause and flush it enables")
    }

    /**
     * A car switch reaches `handOver` on a background dispatcher, and Media3 throws when a controller is
     * released off its application looper — which made every car switch fail before the profile changed.
     */
    @Test
    fun `the hand-over releases its controller on the main thread`() {
        val handOver = controllerSource()
            .substringAfter("suspend fun handOver(outgoing: ProfileId)")
            .substringBefore("private suspend fun stopAndFlush(")
        val onMain = handOver.substringAfter("withContext(mainDispatcher) {", missingDelimiterValue = "")

        assertTrue("release()" in onMain, "release() must run inside the main-thread block")
        assertTrue(
            "release()" !in handOver.substringBefore("withContext(mainDispatcher) {"),
            "nothing may release the controller before switching to the main thread",
        )
    }

    /** A display-only holder carries a cached position; the hand-over must never journal it as progress. */
    @Test
    fun `the hand-over never records the idle-session holder's position`() {
        val flush = controllerSource()
            .substringAfter("private suspend fun stopAndFlush(")
            .substringBefore("private suspend fun attachForHandOver()")

        val guard = flush.indexOf("!MediaItems.isResumePlaceholder(item)")
        val record = flush.indexOf("playbackRepository.recordPosition(")
        assertTrue(guard >= 0, "the holder must be excluded from the flush")
        assertTrue(record > guard, "the exclusion must guard the write")
    }

    private fun serviceSource(): String =
        File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()

    private fun controllerSource(): String =
        File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackController.kt").readText()
}
