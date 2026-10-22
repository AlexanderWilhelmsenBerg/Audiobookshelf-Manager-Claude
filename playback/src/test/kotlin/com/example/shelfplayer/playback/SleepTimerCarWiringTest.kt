package com.example.shelfplayer.playback

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * PD-002 — source-sensitive checks retained only for the narrow MediaSession button/command
 * wiring. The controller/projection and sleep-connected semantics are now verified by the
 * production CarProjectionLifecycleTest and CarLifecycleAuthorityTest behavior tests.
 */
class SleepTimerCarWiringTest {

    @Test
    fun `the projection label is decided from the car-bound state`() {
        val publish = serviceSource()
            .substringAfter("private fun publishSleepTimerMetadata(")
            .substringBefore("private fun observeSkipIntervals(")

        assertTrue("SleepTimerMediaMetadata.projectionLabel(" in publish)
        assertTrue("carConnections.isConnected()" in publish)
        assertTrue("replaceMediaItem(" in publish, "restoring must never interrupt playback")
    }

    @Test
    fun `every button publish re-evaluates the projection so a disconnect brings the countdown back`() {
        val publish = serviceSource()
            .substringAfter("private fun publishMediaButtons(")
            .substringBefore("private fun mediaButtons(")

        assertTrue("publishSleepTimerMetadata(sleepTimerState)" in publish)
    }

    @Test
    fun `a car controller is not granted and is refused the extend command`() {
        val source = serviceSource()
        val connect = source
            .substringAfter("override fun onConnect(")
            .substringBefore("override fun onDisconnected(")
        val grant = connect.indexOf("ACTION_EXTEND_SLEEP_TIMER")
        val guard = connect.indexOf("!controller.isCar()")
        assertTrue(grant >= 0 && guard in 0 until grant, "the extend grant must sit under a non-car guard")

        val refusal = source
            .substringAfter("private fun customCommandRefusal(")
            .substringBefore("override fun onCustomCommand(")
        val extend = refusal.indexOf("ACTION_EXTEND_SLEEP_TIMER")
        val refused = refusal.indexOf("SessionError.ERROR_NOT_SUPPORTED")
        assertTrue("controller.isCar()" in refusal)
        assertTrue(extend >= 0 && refused > extend, "a car's extend command must be refused as unsupported")

        val custom = source.substringAfter("override fun onCustomCommand(")
        assertTrue(custom.indexOf("customCommandRefusal(") in 0 until custom.indexOf("sleepTimer.extend()"))
    }

    private fun serviceSource(): String =
        File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()
}
