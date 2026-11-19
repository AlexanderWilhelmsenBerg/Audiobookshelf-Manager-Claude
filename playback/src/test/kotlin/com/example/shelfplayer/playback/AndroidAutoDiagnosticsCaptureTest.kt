package com.example.shelfplayer.playback

import android.content.Context
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AudioRouteLabRecorderTest.AtomicFileRenameShadow::class])
class AndroidAutoDiagnosticsCaptureTest {
    @Test
    fun `existing attempt details stay available without obsolete failure counts entering the export index`() {
        val at = Instant.parse("2026-11-19T10:00:00Z")
        val legacy = AndroidAutoCaptureAttempt(1, at, 0.seconds, "projection", true, "headset")
        legacy.interrupted(at, 0.seconds, "audioFocusLoss")
        legacy.end(at.plusSeconds(1), 1.seconds, "projection-disconnect")
        val oldRow = legacy.snapshot(1.seconds).apply { remove("recoveryStatsVersion") }

        val report = JSONObject(AndroidAutoCaptureReport.format("build test", 34, at, listOf(oldRow)))
        assertEquals(1, report.getInt("legacyAttemptsExcludedFromRecoveryStats"))
        assertEquals(0, report.getJSONArray("failedHeadsetReconnects").length())
        assertEquals(1, report.getJSONArray("attempts").getJSONObject(0).getJSONArray("headsetReconnects").length())
    }

    @Test
    fun `service counts AudioTrack recovery through an explicit headphone selection`() = runTest {
        PlaybackServiceCommandContinuityFixture(this, seedActualPlayback = true).use { player ->
            player.installServiceContinuityCallbacks()
            player.startHealthyHeadsetWithoutCar()
            val clock = player.service.clock as TestAppClock
            val recorder = AndroidAutoDiagnosticsCapture(
                RuntimeEnvironment.getApplication(),
                clock,
                EventLog(clock),
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )
            player.service.androidAutoDiagnostics = recorder
            recorder.setEnabled(true)
            recorder.confirmed("projection", true, null)
            player.service.audioOutputs.outputIntentOwner.select(
                player.endpointId,
                OutputIntentOwner.Destination.Output,
            )
            player.observeAudibleServiceRoute()
            recorder.interrupted("routeSafety")
            clock.advanceBy(700.milliseconds)
            player.observeAudibleServiceRoute()

            val report = JSONObject(recorder.report("build test"))
            val stats = report.getJSONObject("headsetRecoveryMs").getJSONObject("automatic")
            assertEquals(1, stats.getInt("count"))
            assertEquals(700, stats.getInt("minMs"))
        }
    }

    @Test
    fun `only records from the first car signal to the end of the same connection`() = runTest {
        val f = Fixture(this)
        f.capture.setEnabled(true)
        f.key(1)
        f.capture.carBluetoothLink(true, "endpoint-secret")
        f.capture.confirmed("media-session", true, "endpoint-secret")
        f.capture.confirmed("projection", true, "endpoint-secret")
        f.key(127)
        f.capture.disconnected("projection-disconnect")
        f.key(2)

        val report = f.report()
        val attempts = report.getJSONArray("attempts")
        assertEquals(1, attempts.length())
        val attempt = attempts.getJSONObject(0)
        assertEquals(1L, attempt.getLong("attempt"))
        assertTrue(attempt.getBoolean("headsetPresentAtConnect"))
        assertEquals("projection-disconnect", attempt.getString("endReason"))
        val events = attempt.getJSONArray("events").toString()
        assertTrue(events.contains("keyCode=127 caller=BluetoothService"))
        assertFalse(events.contains("keyCode=1 "))
        assertFalse(events.contains("keyCode=2 "))
        assertFalse(report.toString().contains("secret"))
    }

    @Test
    fun `report indexes failed automatic reconnects and separates recovery times after Play`() = runTest {
        val f = Fixture(this)
        f.capture.setEnabled(true)
        f.capture.confirmed("projection", true, "held-headset")
        f.capture.interrupted("audioFocusLoss")
        f.clock.advanceBy(500.milliseconds)
        f.capture.verifiedHeadset("different-headset")
        f.clock.advanceBy(250.milliseconds)
        f.capture.verifiedHeadset("held-headset")
        f.capture.interrupted("routeSafety")
        f.clock.advanceBy(3.seconds)
        f.capture.transport(play = true)
        f.clock.advanceBy(200.milliseconds)
        f.capture.verifiedHeadset("held-headset")
        f.capture.interrupted("routeSafety")
        f.capture.transport(play = false)
        f.capture.disconnected("projection-disconnect")
        f.capture.confirmed("media-session", false, null)
        f.capture.disconnected("final-car-disconnect")

        val report = f.report()
        assertFalse(report.getJSONArray("attemptIndex").getJSONObject(1).getBoolean("headsetPresentAtConnect"))
        val failures = report.getJSONArray("failedHeadsetReconnects")
        assertEquals(1, failures.length())
        assertEquals(2, failures.getJSONObject(0).getInt("reconnect"))
        val stats = report.getJSONObject("headsetRecoveryMs")
        val automatic = stats.getJSONObject("automatic")
        assertEquals(750, automatic.getInt("minMs"))
        assertEquals(750, automatic.getInt("maxMs"))
        assertEquals(750.0, automatic.getDouble("avgMs"))
        assertEquals(3200.0, stats.getJSONObject("afterPlay").getDouble("avgMs"))
    }

    @Test
    fun `unconfirmed car link times out and remains distinguishable from Android Auto`() = runTest {
        val f = Fixture(this)
        f.capture.setEnabled(true)
        f.capture.carBluetoothLink(true, "held-headset")
        f.capture.interrupted("routeSafety")
        f.clock.advanceBy(31.seconds)
        advanceTimeBy(31.seconds)
        runCurrent()

        assertNull(f.capture.state.value.recordingAttempt)
        val report = f.report()
        val attempt = report.getJSONArray("attemptIndex").getJSONObject(0)
        assertTrue(attempt.isNull("connectedAtUtc"))
        assertEquals("connection-unconfirmed", attempt.getString("endReason"))
        assertEquals(1, report.getJSONArray("failedHeadsetReconnects").length())
    }

    @Test
    fun `history survives recreation and Clear keeps the current attempt recording`() = runTest {
        val f = Fixture(this)
        f.capture.setEnabled(true)
        f.capture.confirmed("projection", false, null)
        f.capture.disconnected("projection-disconnect")
        advanceTimeBy(501)
        runCurrent()

        val restored = f.open()
        assertTrue(restored.state.value.enabled)
        assertEquals(1, f.report(restored).getJSONArray("attempts").length())
        restored.confirmed("projection", true, "restored-headset")
        f.key(85)
        restored.clearAndAwait()
        assertEquals(2L, restored.state.value.recordingAttempt)
        f.key(126)

        val attempts = f.report(restored).getJSONArray("attempts")
        assertEquals(1, attempts.length())
        val events = attempts.getJSONObject(0).getJSONArray("events").toString()
        assertFalse(events.contains("keyCode=85"))
        assertTrue(events.contains("keyCode=126"))
    }

    private class Fixture(scope: TestScope) {
        private val context: Context = RuntimeEnvironment.getApplication()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val background = scope.backgroundScope
        val clock = TestAppClock()
        private val log = EventLog(clock)
        val capture = open()

        fun open() = AndroidAutoDiagnosticsCapture(context, clock, log, background, dispatcher)

        fun key(code: Int) = log.write(
            LogLevel.Info,
            "AndroidAuto",
            "MediaSession hardware media button observed keyCode=$code caller=BluetoothService title=secret",
        )

        suspend fun report(source: AndroidAutoDiagnosticsCapture = capture) = JSONObject(source.report("build test"))
    }
}
