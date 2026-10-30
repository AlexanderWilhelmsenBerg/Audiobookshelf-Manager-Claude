package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.core.content.edit
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AudioRouteLabRecorderTest.AtomicFileRenameShadow::class])
class AudioRouteLabCapabilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreviousCapture() {
        context.getSharedPreferences("audio-route-lab", Context.MODE_PRIVATE).edit { clear() }
        File(context.noBackupFilesDir, "audio-route-lab.txt").delete()
        File(context.noBackupFilesDir, "audio-route-lab.txt.bak").delete()
    }

    @Test
    fun `separate remote mixes correlate with classifier track aliases without becoming selectable outputs`() =
        runTest {
            val remoteMix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX
            } else {
                error("The remote-mix fixture requires API31+; this test is configured for API34")
            }
            val manager = shadowOf(context.getSystemService(AudioManager::class.java))
            manager.setOutputDevices(
                listOf(device(11, remoteMix), device(12, remoteMix)),
            )
            val fixture = Fixture(this)
            fixture.lab.start()
            val first = fixture.lab.deviceAlias(11)
            val second = fixture.lab.deviceAlias(12)
            assertNotEquals("unmapped", first)
            assertNotEquals(first, second)
            val platform = context.getSystemService(AudioManager::class.java)
            platform.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
                requireNotNull(it.channelCounts) { "fixture channel counts missing" }
                requireNotNull(it.sampleRates) { "fixture sample rates missing" }
            }
            fixture.classifier.start()
            assertTrue(fixture.classifier.state.value.recording, fixture.classifier.state.value.status)
            val rows = fixture.classifier.state.value.current.joinToString("\n")
            assertContains(rows, "trackAlias=$first")
            assertContains(rows, "trackAlias=$second")
            assertContains(rows, "evidence=remote-mix-not-endpoint")
            runCurrent()
            assertTrue(fixture.router.outputs.value.isEmpty(), "a diagnostic alias must not add a selectable endpoint")
            assertFalse("private-name" in fixture.lab.report("test"))
            fixture.classifier.stop()
            fixture.lab.stop()
            runCurrent()
        }

    @Test
    fun `a late write signal after Clear cannot recreate the old capture or suppress a new snapshot`() = runTest {
        val fixture = Fixture(this)
        fixture.lab.start()
        fixture.lab.heardAt("Car")
        runCurrent()
        fixture.lab.clear()
        runCurrent()
        val file = File(context.noBackupFilesDir, "audio-route-lab.txt")
        assertFalse(file.exists())
        fixture.lab.playbackCall("requestWrite")
        runCurrent()
        assertFalse(file.exists(), "a delayed old producer must not recreate the cleared file")
        fixture.lab.start()
        fixture.lab.heardAt("HeadsetA")
        fixture.lab.playbackCall("requestWrite")
        runCurrent()
        assertContains(file.readText(), "user-heard=HeadsetA")
        assertFalse("user-heard=Car" in file.readText())
        fixture.lab.stop()
        runCurrent()
    }

    private fun device(id: Int, type: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(port, "mName", "private-name")
        // The builder bypasses AudioPort's constructor; real framework capabilities are non-null.
        ReflectionHelpers.setField(port, "mSamplingRates", intArrayOf(48000))
        ReflectionHelpers.setField(port, "mChannelMasks", intArrayOf(AudioFormat.CHANNEL_OUT_STEREO))
        ReflectionHelpers.setField(port, "mChannelIndexMasks", intArrayOf())
        val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
        ReflectionHelpers.setField(handle, "mId", id)
        return device
    }

    private inner class Fixture(scope: TestScope) {
        private val clock = TestAppClock()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val router = AudioOutputRouter(
            context,
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            scope.backgroundScope,
            dispatcher,
            volumeEffects = volumeEffectsForTest(dispatcher),
        )
        val lab = AudioRouteLab(context, EventLog(clock), clock, router, scope.backgroundScope, dispatcher)
        val classifier = OutputClassifier(context, lab)
    }
}
