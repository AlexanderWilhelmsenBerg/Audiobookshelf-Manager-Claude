package com.example.shelfplayer.playback

import android.content.Context
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AudioRouteLabRecorderTest.AtomicFileRenameShadow::class])
class AudioRouteLabStallIncidentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrevious() {
        File(context.noBackupFilesDir, "audio-route-lab.txt").delete()
        File(context.noBackupFilesDir, "audio-route-lab.txt.bak").delete()
    }

    @Test
    fun `first stalled output is exported and survives flood persistence and recreation`() = runTest {
        val f = Fixture(this)
        f.lab.start()
        f.lab.systemIncidentContext = { listOf("$it bluetooth-event event=a2dp-connection peer=b2") }
        f.log.write(LogLevel.Info, "AndroidAuto", "Headset take-back experiment outcome=Selected targetRouteAlias=1")
        runCurrent()
        f.stall()
        repeat(500) { f.lab.mediaButtonInput(null, MediaInputController(), MediaInputEntry.SessionCallback) }
        f.log.write(
            LogLevel.Warn,
            "Playback",
            "Playback stopped on an error cause1Type=StuckPlayerException " +
                "cause1StuckType=PlayingNoProgress cause1Timeout=10000ms privateMessage=secret",
        )
        f.lab.stop()
        runCurrent()

        val recovered = f.recorder()
        assertTrue(recovered.state.value.stallIncident.any { "headset-take-back outcome=Selected" in it })
        assertTrue(recovered.state.value.stallIncident.any { "peer=b2" in it })
        assertTrue(recovered.state.value.stallIncident.any { "PlayingNoProgress" in it })
        assertFalse(recovered.state.value.stallIncident.joinToString().contains("secret"))
        assertTrue(recovered.state.value.entries.size <= 350)
        assertTrue(recovered.state.value.stallIncident.size <= AudioStallIncidentEvidence.MAX_ROWS)
        assertTrue(recovered.report("test").contains("[first-suspected-output-stall]"))
    }

    @Test
    fun `clear deletes pinned evidence and late old events cannot restore it`() = runTest {
        val f = Fixture(this)
        f.lab.start()
        f.stall()
        runCurrent()
        val file = File(context.noBackupFilesDir, "audio-route-lab.txt")
        assertFalse(f.lab.state.value.stallIncident.isEmpty())
        assertTrue(file.exists(), "The old incident must have reached storage before Clear")
        f.log.write(LogLevel.Warn, "Playback", "Playback stopped on an error cause1StuckType=PlayingNoProgress")

        f.lab.clearAndAwait()
        runCurrent()

        assertTrue(f.lab.state.value.stallIncident.isEmpty())
        assertTrue(f.recorder().state.value.stallIncident.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `healthy playback leaves incident empty and old pinned rows expire independently`() = runTest {
        val f = Fixture(this)
        f.lab.start()
        f.lab.trackTiming(f.sample(100), f.snapshot)
        f.clock.advanceBy(1.seconds)
        f.lab.trackTiming(f.sample(200), f.snapshot)
        assertTrue(f.lab.state.value.stallIncident.isEmpty())
        f.clock.advanceBy(2.seconds)
        f.lab.trackTiming(f.sample(200), f.snapshot)
        assertFalse(f.lab.state.value.stallIncident.isEmpty())
        f.clock.advanceBy(25.hours)
        f.lab.mark("Baseline")
        assertTrue(f.lab.state.value.stallIncident.isEmpty())
        f.lab.stop()
        runCurrent()
        assertTrue(f.recorder().state.value.stallIncident.isEmpty())
    }

    @Test
    fun `native timing and player position are exported together during steady playback`() = runTest {
        val f = Fixture(this)
        f.lab.start()
        f.lab.trackTiming(f.sample(100), f.snapshot)
        f.clock.advanceBy(1.seconds)
        f.lab.trackTiming(f.sample(22_150), f.snapshot.copy(positionMs = 1_100))
        f.clock.advanceBy(1.seconds)
        f.lab.trackTiming(f.sample(44_200), f.snapshot.copy(positionMs = 2_100))
        val raw = f.lab.state.value.entries.filter { "track-timing" in it }.last()

        assertTrue("headFrames=44200" in raw)
        assertTrue("positionMs=2100" in raw)
        assertTrue("playerState=3" in raw)
    }

    @Test
    fun `transition retains denser samples briefly and pins the seek reason with listener marking`() = runTest {
        val f = Fixture(this)
        val atNs = f.clock.elapsed().inWholeNanoseconds
        val evidence = "trackEpoch=1 headFrames=100 trackRoute=d1:Ambiguous"
        f.lab.trackTimingBoundary(AudioTimingBoundary.ProjectionDeparture, atNs, f.snapshot, evidence)
        f.lab.playerDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, 10, 20, true)
        assertTrue(f.lab.state.value.entries.isEmpty())

        f.lab.start()
        f.lab.trackTimingBoundary(AudioTimingBoundary.ProjectionDeparture, atNs, f.snapshot, evidence)
        f.lab.trackTiming(f.sample(100), f.snapshot)
        f.clock.advanceBy(250.milliseconds)
        f.lab.trackTiming(f.sample(5_612), f.snapshot)
        f.clock.advanceBy(250.milliseconds)
        f.lab.trackTiming(f.sample(11_125), f.snapshot)
        f.clock.advanceBy(3.seconds)
        f.lab.trackTiming(f.sample(77_275), f.snapshot)
        f.clock.advanceBy(250.milliseconds)
        f.lab.trackTiming(f.sample(82_787), f.snapshot)
        f.lab.playerDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, 3_500, 4_000, true)
        f.lab.heardAt("Skipping")

        val state = f.lab.state.value
        assertEquals(4, state.entries.count { "track-timing" in it })
        assertTrue(state.stallIncident.any { "route-boundary edge=ProjectionDeparture" in it && evidence in it })
        assertTrue(
            state.stallIncident.any {
                "player-discontinuity" in it && "previousMs=3500 nextMs=4000 sameItem=true" in it
            },
        )
    }

    private inner class Fixture(scope: TestScope) {
        val clock = TestAppClock()
        val log = EventLog(clock)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val background = scope.backgroundScope
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        private val router =
            AudioOutputRouter(context, logger, background, dispatcher, volumeEffects = volumeEffectsForTest(dispatcher))
        val lab = recorder()
        val snapshot = AudioTrackPlaybackSnapshot(true, Player.STATE_READY, 0, false, 100, 5_000, false, 1, 0)
        fun recorder() = AudioRouteLab(context, log, clock, router, background, dispatcher)
        fun sample(head: Long) = AudioTrackTimingSample(
            1, clock.elapsed().inWholeNanoseconds, 3, 22_050, 2, 2, 11_025,
            head, null, null, 0, 1_000, 1_000,
        )
        fun stall() {
            lab.trackTiming(sample(100), snapshot)
            clock.advanceBy(2.seconds)
            lab.trackTiming(sample(100), snapshot)
        }
    }
}
