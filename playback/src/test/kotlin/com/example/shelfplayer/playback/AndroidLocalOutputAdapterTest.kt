package com.example.shelfplayer.playback

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLocalOutputAdapterTest {
    @Test
    fun `replacement appearance retires old preference without falling back to automatic`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        val adapter = AndroidLocalOutputAdapter(
            context,
            LiveOutputEndpointRegistry(),
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
        manager.setOutputDevices(listOf(device))
        adapter.refresh()
        val original = adapter.endpointIds.single()
        assertEquals(AudioOutputActivitySource.Unmeasured, adapter.outputs().single().activitySource)
        manager.setOutputDevices(emptyList())
        adapter.refresh()
        manager.setOutputDevices(listOf(device))
        adapter.refresh()
        val replacement = adapter.endpointIds.single()
        assertNotEquals(original, replacement)

        val fixture = fixture()
        assertFalse(adapter.requestPreference(fixture.player, original))
        assertTrue(fixture.preferredRequests.isEmpty())
        assertTrue(adapter.requestPreference(fixture.player, replacement))
        assertEquals(listOf(device), fixture.preferredRequests)
    }

    @Test
    fun `queued and late callbacks cannot publish after registration replacement`() = runTest {
        val adapter = AndroidLocalOutputAdapter(
            RuntimeEnvironment.getApplication(),
            LiveOutputEndpointRegistry(),
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        var oldPublications = 0
        var currentPublications = 0
        adapter.start { oldPublications++ }
        val old = callback(adapter)
        adapter.start { error("Repeated start must retain a single callback") }
        assertSame(old, callback(adapter))
        old.onAudioDevicesAdded(emptyArray())
        adapter.close()
        adapter.start { currentPublications++ }
        old.onAudioDevicesRemoved(emptyArray())
        callback(adapter).onAudioDevicesAdded(emptyArray())
        runCurrent()
        assertEquals(0, oldPublications)
        assertTrue(currentPublications > 0)
        adapter.close()
    }

    @Test
    fun `application scope completion unregisters output observation`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val adapter = AndroidLocalOutputAdapter(
            RuntimeEnvironment.getApplication(),
            LiveOutputEndpointRegistry(),
            scope,
            StandardTestDispatcher(testScheduler),
        )
        adapter.start { error("Cancelled scope must not publish") }
        val old = callback(adapter)
        scope.cancel()
        old.onAudioDevicesAdded(emptyArray())
        runCurrent()
        val registration = AndroidLocalOutputAdapter::class.java.getDeclaredField("callback").apply {
            isAccessible =
                true
        }
        assertEquals(null, registration.get(adapter))
        adapter.start { error("A retired application scope must not re-register") }
        assertEquals(null, registration.get(adapter))
    }

    private fun callback(adapter: AndroidLocalOutputAdapter): AudioDeviceCallback =
        AndroidLocalOutputAdapter::class.java.getDeclaredField("callback").run {
            isAccessible = true
            get(adapter) as AudioDeviceCallback
        }
}
