package com.example.shelfplayer

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Issue #88 — cold app startup may reconcile server progress only through the cache-miss resume seam. */
class StartupResumeFallbackWiringTest {

    @Test
    fun `cold startup uses cache-miss server reconciliation before startup mode`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/ShelfPlayerApplication.kt").readText()
        val startup = source
            .substringAfter("sessionRestorer.restoreActiveSession()")
            .substringBefore("sleepTimers.closeOrphanedSessions()")

        assertTrue("auto.lastPlayedAfter" in startup)
        assertTrue("syncAccount()" in startup)
        assertTrue(startup.indexOf("auto.lastPlayedAfter") < startup.indexOf("applyStartupMode"))
    }
}
