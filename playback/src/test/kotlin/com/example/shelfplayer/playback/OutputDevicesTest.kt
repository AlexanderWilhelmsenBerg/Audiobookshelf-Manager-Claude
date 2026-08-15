package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC ROUTE-002 — classification and identity, which is where this feature's bugs live.
 *
 * Two devices that should share a policy and do not, or two that should not and do, are both invisible
 * until somebody's book starts in the wrong room. None of it needs an `AudioManager`, which is why
 * `OutputDevices` is a pure object.
 */
// The same InlinedApi reasoning as `OutputDevices.kindOf`: these are `int` constants, and a test that
// could not name the newer device types could not prove they are classified.
@SuppressLint("InlinedApi")
class OutputDevicesTest {

    /**
     * ROUTE-002: *"Wired headset is represented as a device category because a stable device identity may
     * be unavailable."*
     *
     * A 3.5mm jack and a USB-C headset are both "wired headphones" to a listener, and neither reports a
     * name worth keying on. One policy, one row in settings.
     */
    @Test
    fun `every wired output shares one identity`() {
        val jack = device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, null)
        val headset = device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Some Headset")
        val usb = device(AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C Audio")

        assertEquals(KnownDevice.WIRED_ID, jack.id)
        assertEquals(KnownDevice.WIRED_ID, headset.id)
        assertEquals(KnownDevice.WIRED_ID, usb.id)
        assertEquals(DeviceKind.Wired, jack.kind)
    }

    @Test
    fun `a bluetooth device is identified by the name it advertises`() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Ada's Earbuds")

        assertEquals(DeviceKind.Bluetooth, earbuds.kind)
        assertEquals("bluetooth:ada's earbuds", earbuds.id)
        assertEquals("Ada's Earbuds", earbuds.displayName)
    }

    /**
     * One headset announcing A2DP and its LE profile must not become two rows in settings.
     *
     * They are the same kind, so the id matches and the debounce sees one device rather than two.
     */
    @Test
    fun `the same headset over two bluetooth profiles is one device`() {
        val a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Earbuds")
        val le = device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Earbuds")

        assertEquals(a2dp.id, le.id)
    }

    /** A device that reports its name with different capitalisation between connections is one device. */
    @Test
    fun `identity ignores case`() {
        assertEquals(
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "EARBUDS").id,
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "earbuds").id,
        )
    }

    /** ROUTE-002 asks for a hearing aid to be able to have a policy of its own, so it must classify as one. */
    @Test
    fun `a hearing aid is its own kind`() {
        assertEquals(DeviceKind.HearingAid, device(AudioDeviceInfo.TYPE_HEARING_AID, "Aid").kind)
    }

    /**
     * The classification ROUTE-002's explicit-content clause depends on.
     *
     * A speaker is the case where starting a book affects people who did not choose to hear it, so getting
     * this wrong is the difference between a warning shown and a warning missed.
     */
    @Test
    fun `speakers are classified as speakers`() {
        assertEquals(DeviceKind.Speaker, device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null).kind)
        assertEquals(DeviceKind.Speaker, device(AudioDeviceInfo.TYPE_BLE_SPEAKER, "Kitchen").kind)
        assertEquals(true, device(AudioDeviceInfo.TYPE_BLE_SPEAKER, "Kitchen").isSpeaker)
    }

    /**
     * Things that are not somebody settling down with a book.
     *
     * The earpiece is a phone call and telephony is a phone call. Arming a book because a call routed audio
     * would put a book in the notification shade every time the phone rang.
     */
    @Test
    fun `a call route is not a listening device`() {
        assertNull(OutputDevices.of(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, null, AT))
        assertNull(OutputDevices.of(AudioDeviceInfo.TYPE_TELEPHONY, null, AT))
        assertNull(OutputDevices.of(AudioDeviceInfo.TYPE_HDMI, null, AT))
    }

    /** ROUTE-002 forbids hardware addresses in ordinary UI, so an unnamed device gets a readable name. */
    @Test
    fun `a device with no name still has something to call it`() {
        val unnamed = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "   ")

        assertEquals("Bluetooth audio", unnamed.displayName)
        assertEquals("bluetooth:unnamed", unnamed.id)
    }

    /** Every new device starts at the requirement's default, whatever it is. */
    @Test
    fun `a newly seen device is armed rather than set to play`() {
        assertEquals(DevicePolicy.ArmOnly, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "New").policy)
        assertEquals(DevicePolicy.Default, DevicePolicy.ArmOnly)
    }

    private fun device(type: Int, name: String?): KnownDevice =
        assertNotNull(OutputDevices.of(type, name, AT), "type $type was not classified")

    private companion object {
        val AT: Instant = Instant.parse("2026-08-15T09:00:00Z")
    }
}
