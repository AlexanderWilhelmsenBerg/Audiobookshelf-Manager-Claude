package com.example.shelfplayer.playback

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AudioRouteLabRecorderTest.AtomicFileRenameShadow::class])
class AudioRouteLabRecorderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetCapture() {
        context.getSharedPreferences("audio-route-lab", Context.MODE_PRIVATE).edit { clear() }
        File(context.noBackupFilesDir, "audio-route-lab.txt").delete()
        File(context.noBackupFilesDir, "audio-route-lab.txt.bak").delete()
    }

    @Test
    fun `captures every rapid media command and recovers it after a new recorder instance`() = runTest {
        val clock = object : AppClock {
            override fun now(): Instant = Instant.now()

            override fun elapsed(): Duration = Duration.ZERO
        }
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val log = EventLog(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val router =
            AudioOutputRouter(
                context,
                logger,
                backgroundScope,
                dispatcher,
                volumeEffects = volumeEffectsForTest(dispatcher),
            )
        val recorder = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)
        recorder.start()
        runCurrent()

        repeat(28) { index ->
            log.write(
                LogLevel.Info,
                "AndroidAuto",
                "MediaSession transport request observed requested=Play controllerKind=external " +
                    "keyCode=$index privateDeviceName=SecretDevice mediaTitle=SecretBook",
            )
        }
        runCurrent()
        assertEquals(28, recorder.state.value.entries.count { it.contains("transport-intent") })
        val export = recorder.report("test-build")
        assertTrue(export.contains("keyCode=27"))
        assertFalse(export.contains("SecretDevice"))
        assertFalse(export.contains("SecretBook"))

        recorder.stop()
        runCurrent()
        log.write(LogLevel.Info, "AndroidAuto", "MediaSession transport request observed requested=Pause")
        runCurrent()
        assertEquals(28, recorder.state.value.entries.count { it.contains("transport-intent") })
        assertEquals(
            28,
            File(context.noBackupFilesDir, "audio-route-lab.txt").readLines().count { it.contains("transport-intent") },
            "the committed incident file must contain every accepted command before restoration",
        )
        val restored = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)
        assertFalse(restored.state.value.recording)
        assertEquals(28, restored.state.value.entries.count { it.contains("transport-intent") })
        assertTrue(restored.report("same-build").contains("diagnostic-stop"))

        restored.clear()
        runCurrent()
        assertFalse(File(context.noBackupFilesDir, "audio-route-lab.txt").exists())
        assertTrue(restored.state.value.entries.isEmpty())
    }

    @Test
    fun `track safety observer is present without recording diagnostic events`() = runTest {
        val clock = object : AppClock {
            override fun now(): Instant = Instant.now()
            override fun elapsed(): Duration = Duration.ZERO
        }
        val log = EventLog(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router =
            AudioOutputRouter(
                context,
                logger,
                backgroundScope,
                dispatcher,
                volumeEffects = volumeEffectsForTest(dispatcher),
            )
        val lab = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)
        val monitor = AudioTrackRouteMonitor(context, lab)

        // Passive route verification is always installed, but recording is opt-in.
        val provider = requireNotNull(monitor.providerForPlayer())
        assertFalse(lab.state.value.recording)
        assertTrue(lab.state.value.entries.isEmpty())

        lab.start()
        // The existing provider was created before recording; it emitted no diagnostic event.
        assertTrue(lab.state.value.reportStatus.contains("Awaiting next player creation"))
        val recordedProvider = requireNotNull(monitor.providerForPlayer())
        assertTrue(lab.state.value.reportStatus.contains("Installed"))
        // Constructing/releasing the delegate creates no extra playback track.
        recordedProvider.release()
        provider.release()
        lab.stop()
        runCurrent()
    }

    @Test
    fun `recording activated after a player creation waits for the next provider decision`() = runTest {
        val clock = object : AppClock {
            override fun now(): Instant = Instant.now()
            override fun elapsed(): Duration = Duration.ZERO
        }
        val log = EventLog(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router =
            AudioOutputRouter(
                context,
                logger,
                backgroundScope,
                dispatcher,
                volumeEffects = volumeEffectsForTest(dispatcher),
            )
        val lab = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)
        val monitor = AudioTrackRouteMonitor(context, lab)

        // The in-memory safety observer is present, even though diagnostic capture is off.
        val firstProvider = requireNotNull(monitor.providerForPlayer())
        lab.start()
        assertTrue(lab.state.value.reportStatus.contains("Awaiting next player creation"))
        monitor.sample()
        assertFalse(lab.report("test").contains("observer-provider-installed"))

        // The next natural creation begins emitting diagnostic lifecycle events.
        val provider = requireNotNull(monitor.providerForPlayer())
        assertTrue(lab.report("test").contains("observer-player-create recording=true"))
        assertTrue(lab.report("test").contains("observer-provider-installed"))
        assertTrue(lab.state.value.reportStatus.contains("awaiting Media3 AudioTrack"))
        provider.release()
        firstProvider.release()
        assertTrue(lab.report("test").contains("observer-provider-released"))
        lab.stop()
        runCurrent()
    }

    @Test
    fun `track events reject arbitrary device metadata and recording off is inert`() = runTest {
        val clock = object : AppClock {
            override fun now(): Instant = Instant.now()
            override fun elapsed(): Duration = Duration.ZERO
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val log = EventLog(clock)
        val router =
            AudioOutputRouter(
                context,
                logger,
                backgroundScope,
                dispatcher,
                volumeEffects = volumeEffectsForTest(dispatcher),
            )
        val lab = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)

        lab.trackLifecycle("provider-installed")
        lab.actualTrackEvidence("playing-route-unknown")
        assertTrue(lab.state.value.entries.isEmpty())
        lab.start()
        lab.trackLifecycle("provider-installed")
        lab.trackLifecycle("device-name=HeadsetABC")
        lab.actualTrackEvidence("playing-to=privateDeviceName:Car measured=AudioTrack")
        lab.actualTrackEvidence("playing-route-unknown password=token")
        lab.actualTrackEvidence("paused-no-route-measurement")
        lab.actualTrackEvidence("playing-to=d7:Ambiguous measured=AudioTrack")
        lab.actualTrackEvidence("playing-to=d3:Ambiguous+d4:Ambiguous measured=AudioTrack")
        val report = lab.report("test")
        assertTrue(report.contains("observer-provider-installed"))
        assertTrue(report.contains("actual-track paused-no-route-measurement"))
        assertTrue(report.contains("actual-track playing-to=d7:Ambiguous measured=AudioTrack"))
        assertTrue(report.contains("actual-track playing-to=d3:Ambiguous+d4:Ambiguous measured=AudioTrack"))
        assertFalse(report.contains("HeadsetABC"))
        assertFalse(report.contains("privateDeviceName"))
        assertFalse(report.contains("password=token"))
        lab.stop()
        runCurrent()
    }

    @Test
    fun `capture is strictly opt in and manual observations use fixed safe labels`() = runTest {
        val clock = object : AppClock {
            override fun now(): Instant = Instant.now()

            override fun elapsed(): Duration = Duration.ZERO
        }
        val log = EventLog(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router =
            AudioOutputRouter(
                context,
                logger,
                backgroundScope,
                dispatcher,
                volumeEffects = volumeEffectsForTest(dispatcher),
            )
        val lab = AudioRouteLab(context, log, clock, router, backgroundScope, dispatcher)
        lab.heardAt("Car")
        lab.mark("AndroidAuto")
        assertTrue(lab.state.value.entries.isEmpty())
        lab.start()
        lab.heardAt("private-headset-name")
        lab.mark("private-test-step")
        lab.heardAt("HeadsetA")
        lab.mark("ButtonA")
        lab.stop()
        runCurrent()
        val report = lab.report("test")
        assertTrue(report.contains("user-heard=HeadsetA"))
        assertTrue(report.contains("test-armed step=ButtonA"))
        assertFalse(report.contains("private-headset-name"))
        assertFalse(report.contains("private-test-step"))
    }

    /**
     * Android/Linux rename replaces an existing file; host Java File.renameTo does not on Windows.
     * Only that private platform primitive is adapted for this test class. AtomicFile's real
     * startWrite/finishWrite/openRead transactions and every recorder assertion remain in use.
     */
    @Implements(AtomicFile::class)
    // Robolectric requires a public empty constructor even for a shadow of static methods only.
    @Suppress("UtilityClassWithPublicConstructor")
    class AtomicFileRenameShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun rename(source: File, destination: File) {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }
}
