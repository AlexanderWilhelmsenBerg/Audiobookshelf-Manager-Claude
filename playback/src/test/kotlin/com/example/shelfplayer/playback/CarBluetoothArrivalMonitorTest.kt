package com.example.shelfplayer.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothProfile
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Only a connected car-kit hands-free link, read with Nearby devices access, opens the arrival window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarBluetoothArrivalMonitorTest {
    private val window = CarBluetoothArrivalWindow { Duration.ZERO }
    private val logged = mutableListOf<LogEvent>()
    private val monitor = CarBluetoothArrivalMonitor(
        RuntimeEnvironment.getApplication(),
        window,
        object : Logger {
            override fun log(event: LogEvent) {
                logged += event
            }
        },
    )

    @Test
    fun `connected car kit opens the window and logs no identity`() {
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, CAR, "car")

        assertNotNull(window.age())
        val event = logged.single()
        assertTrue(event.fields.none { it.toString().contains("car") }, "the peer token is never logged")
    }

    @Test
    fun `headsets and other classes never open the window`() {
        val others = listOf(
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            null,
        )
        others.forEach { monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, it, "peer") }

        assertNull(window.age())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `a connecting link is not yet an arrival`() {
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTING, CAR, "car")

        assertNull(window.age())
    }

    @Test
    fun `the car disconnecting closes its window and another peer does not`() {
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, CAR, "car")
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_DISCONNECTED, null, "headset")
        assertNotNull(window.age())

        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_DISCONNECTED, CAR, "car")
        assertNull(window.age())
    }

    @Test
    fun `losing Nearby devices access closes the window`() {
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, CAR, "car")

        monitor.onHandsFreeChanged(false, BluetoothProfile.STATE_CONNECTED, null, null)

        assertNull(window.age())
    }

    @Test
    fun `start and stop are idempotent and stop closes the window`() {
        monitor.start()
        monitor.start()
        monitor.onHandsFreeChanged(true, BluetoothProfile.STATE_CONNECTED, CAR, "car")

        monitor.stop()
        monitor.stop()

        assertNull(window.age())
        assertEquals(1, logged.size)
    }

    private companion object {
        const val CAR = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    }
}
