package com.example.shelfplayer.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLAY-002 / ROUTE-002 — presentation keeps the origin of route activity, not just a Boolean. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputActivityEvidenceTest {
    @Test
    fun `measured headset outranks a conflicting framework route and listener preference`() = runTest {
        val fixture = Fixture(this)
        fixture.predict(fixture.speaker)
        fixture.measured = setOf(fixture.id(fixture.headset))
        fixture.router.select(fixture.id(fixture.car))

        assertEquals(fixture.id(fixture.headset), fixture.active().single().id)
        assertEquals(AudioOutputActivitySource.PlayingAudioTrack, fixture.active().single().activitySource)
        assertEquals(fixture.id(fixture.car), fixture.router.selectedId.value)
    }

    @Test
    fun `framework prediction stays inferred when the playing track is unknown`() = runTest {
        val fixture = Fixture(this)
        fixture.predict(fixture.speaker)
        fixture.router.select(fixture.id(fixture.headset))

        assertEquals(fixture.id(fixture.speaker), fixture.active().single().id)
        assertEquals(AudioOutputActivitySource.FrameworkMediaPolicy, fixture.active().single().activitySource)
    }

    @Test
    fun `empty framework prediction does not upgrade a preference to route evidence`() = runTest {
        val fixture = Fixture(this)
        fixture.predict()
        fixture.router.select(fixture.id(fixture.headset))

        assertTrue(fixture.active().isEmpty())
        assertEquals(fixture.id(fixture.headset), fixture.router.selectedId.value)
    }

    @Test
    @Config(sdk = [32])
    fun `older Android preference fallback is labelled as selected preference`() = runTest {
        val fixture = Fixture(this)
        fixture.router.select(fixture.id(fixture.headset))

        assertEquals(fixture.id(fixture.headset), fixture.active().single().id)
        assertEquals(AudioOutputActivitySource.SelectedPreference, fixture.active().single().activitySource)
    }

    @Test
    @Config(sdk = [32])
    fun `older Android Automatic has no measured or selected route evidence`() = runTest {
        val fixture = Fixture(this)

        assertEquals(3, fixture.router.outputs.value.size)
        assertTrue(fixture.active().isEmpty())
        assertTrue(fixture.router.outputs.value.all { it.activitySource == AudioOutputActivitySource.Unmeasured })
    }

    @Test
    fun `one publish samples the measured route exactly once`() = runTest {
        val fixture = Fixture(this)
        fixture.measured = setOf(fixture.id(fixture.headset))
        val before = fixture.reads
        fixture.router.resettle()

        assertEquals(1, fixture.reads - before)
        assertEquals(fixture.id(fixture.headset), fixture.active().single().id)
    }

    @Test
    fun `multiple measured routes remain multiple and keep measured provenance`() = runTest {
        val fixture = Fixture(this)
        fixture.measured = setOf(fixture.id(fixture.headset), fixture.id(fixture.speaker))
        fixture.router.resettle()

        assertEquals(fixture.measured, fixture.active().map(AudioOutput::id).toSet())
        assertTrue(fixture.active().all { it.activitySource == AudioOutputActivitySource.PlayingAudioTrack })
        assertFalse(fixture.router.outputs.value.first { it.id == fixture.id(fixture.car) }.isActive)
    }

    private class Fixture(scope: TestScope) {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val speaker = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val car = device(3, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        private val context = RuntimeEnvironment.getApplication()
        private val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        private val registry = LiveOutputEndpointRegistry()
        var measured: Set<String>? = null
        var reads = 0
        val router = AudioOutputRouter(
            context,
            object : Logger {
                override fun log(event: LogEvent) = Unit
            },
            scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler),
            registry,
        )

        init {
            manager.setOutputDevices(listOf(headset, speaker, car))
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) {
                    _,
                    method,
                    _,
                ->
                when (method.name) {
                    "addListener", "removeListener", "setPreferredAudioDevice" -> null
                    else -> error("Unexpected player call: ${method.name}")
                }
            } as ExoPlayer
            router.attach(player) {
                reads++
                measured
            }
        }

        fun id(device: AudioDeviceInfo): String = requireNotNull(
            registry.find(device.id, device.type, device.productName?.toString()),
        ).id

        fun active(): List<AudioOutput> = router.outputs.value.filter(AudioOutput::isActive)

        fun predict(vararg devices: AudioDeviceInfo) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            manager.setAudioDevicesForAttributes(attributes, ImmutableList.copyOf(devices))
        }

        private fun device(id: Int, type: Int): AudioDeviceInfo {
            val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
            val port = ReflectionHelpers.getField<Any>(device, "mPort")
            ReflectionHelpers.setField(port, "mName", "fixture-$id")
            val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
            ReflectionHelpers.setField(handle, "mId", id)
            return device
        }
    }
}
