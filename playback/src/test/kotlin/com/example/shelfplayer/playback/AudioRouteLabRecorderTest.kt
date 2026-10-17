package com.example.shelfplayer.playback

import android.content.Context
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
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRouteLabRecorderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetCapture() {
        context.getSharedPreferences("audio-route-lab", Context.MODE_PRIVATE).edit().clear().commit()
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
        val router = AudioOutputRouter(context, logger, backgroundScope, dispatcher)
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
        val router = AudioOutputRouter(context, logger, backgroundScope, dispatcher)
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
        assertTrue(report.contains("test-step=ButtonA"))
        assertFalse(report.contains("private-headset-name"))
        assertFalse(report.contains("private-test-step"))
    }
}
