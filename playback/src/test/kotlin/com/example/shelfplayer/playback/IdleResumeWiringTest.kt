package com.example.shelfplayer.playback

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Issue #88 — source-level reachability proof for the service callbacks Media3/Android Auto actually invoke.
 *
 * The pure candidate tests prove the policy. These assertions prove the policy is wired into the live callback
 * paths, the same distinction that docs/risks.md R-43 requires after earlier car-policy wiring drift.
 */
class IdleResumeWiringTest {

    @Test
    fun `Never policy reaches the display-only holder path`() {
        val source = serviceSource()
        val postConnect = source
            .substringAfter("override fun onPostConnect")
            .substringBefore("private suspend fun refreshResumeAccount")
        val restorer = restorerSource()

        // The service delegates the whole post-connect install to the restorer, whose behaviour
        // CarPostConnectRestorerTest proves against a real player.
        assertTrue("carPostConnectRestorer(trace).restore(action, current)" in postConnect)
        assertTrue("auto.heldResumeAfter(::refreshResumeAccount)" in postConnect)
        assertTrue("activeProfileId = auto::activeProfileId" in postConnect)
        assertTrue("AutoStartAction.None -> holdLastBook(current, profileId)" in restorer)
        assertTrue("current.setMediaItem(held.item, held.startPositionMs)" in restorer)
    }

    @Test
    fun `held item is materialized before freshness may prepare or play it`() {
        val source = serviceSource()
        val play = source
            .substringAfter("private suspend fun performFreshnessPlay")
            .substringBefore("private suspend fun applyFreshnessPlan")

        val materialize = play.indexOf("materializeHeldResume")
        val sleepClaim = play.indexOf("sleepTimer.onPlayRequest")
        val freshness = play.indexOf("resumeFreshness.preparePlay")
        assertTrue(materialize >= 0, "Play must recognize the metadata-only holder")
        assertTrue(sleepClaim > materialize, "the sleep Play claim must belong to the materialized book generation")
        assertTrue(freshness > sleepClaim, "freshness/prepare must see the fresh playable queue, not the holder")
    }

    @Test
    fun `held item cannot be journaled as local playback`() {
        val source = serviceSource()
        val snapshot = source
            .substringAfter("private fun positionSnapshot")
            .substringBefore("private data class PositionSnapshot")

        assertTrue("MediaItems.isResumePlaceholder(item)" in snapshot)
        assertTrue("return null" in snapshot)
    }

    @Test
    fun `session player maps headset Previous to the configured skip`() {
        val construction = serviceSource()
            .substringAfter("val sessionPlayer = ResumeFreshnessPlayer(")
            .substringBefore("val mediaSession")

        assertTrue("skipIntervals = { skips }" in construction)
    }

    private fun restorerSource(): String =
        File("src/main/kotlin/com/example/shelfplayer/playback/CarPostConnectRestorer.kt").readText()

    private fun serviceSource(): String =
        File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()
}
