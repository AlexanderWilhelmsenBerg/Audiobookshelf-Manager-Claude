package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.KnownDevice
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — the chooser and the policy list describe the same devices.
 *
 * ### Why this is the test worth having
 *
 * Two features now name output devices: Settings, which holds a *policy* for each one, and the player's
 * chooser, which sends audio to one. They are built from the same [OutputDevices] rule on purpose. If they
 * ever diverge, a listener sets "auto-play" on a row called *Pixel Buds Pro* and picks a row that looks
 * identical in the player, and nothing anywhere tells them the app thinks those are two different devices —
 * the policy silently stops applying and there is no symptom to report.
 *
 * A test that checked each mapping separately would pass while they drifted. These compare the two against
 * each other, so the only way to satisfy them is to keep one rule.
 */
class AudioOutputIdentityTest {

    /**
     * The invariant. Every type that is a device is also an output, with the same id, name and kind.
     *
     * Deleting the shared `idOf` call from either function — or reordering the wired/car sentinels in one of
     * them — turns this red.
     */
    @Test
    fun `a device and an output are the same thing under two names`() {
        TYPES.forEach { (type, name) ->
            val device = OutputDevices.of(type, name, AT)
            val output = OutputDevices.outputOf(type, name)

            if (device == null) {
                assertNull(output, "type $type is not a listening device, so it must not be an output")
                return@forEach
            }
            val found = assertNotNull(output, "type $type is a device but not an output")
            assertEquals(device.id, found.id, "type $type has two identities")
            assertEquals(device.displayName, found.displayName, "type $type has two names")
            assertEquals(device.kind, found.kind, "type $type has two kinds")
        }
    }

    /**
     * ROUTE-002's wired *category*, from the chooser's side.
     *
     * A 3.5mm jack and a USB headset are one row in Settings because neither reports a stable identity. The
     * chooser has to agree, or it would offer two rows that share one policy.
     */
    @Test
    fun `every wired output is the one wired row`() {
        val jack = assertNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, null))
        val usb = assertNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_USB_HEADSET, "Some USB thing"))

        assertEquals(KnownDevice.WIRED_ID, jack.id)
        assertEquals(KnownDevice.WIRED_ID, usb.id)
    }

    /** PRODUCT_SPEC 14.5 — an id is a kind and a name the device advertises, never a hardware address. */
    @Test
    fun `an output id carries a kind and never an address`() {
        val buds = assertNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pixel Buds Pro"))

        assertEquals("bluetooth:pixel buds pro", buds.id)
        assertTrue(buds.id.startsWith(DeviceKind.Bluetooth.name.lowercase()))
        // `AudioOutputRouter` logs the half before the colon, so that half must never carry the name.
        assertEquals("bluetooth", buds.id.substringBefore(':'))
    }

    /**
     * The speaker question, which the chooser asks for a different reason than ROUTE-002 does.
     *
     * ROUTE-002 asks before *starting* audio in a room. [AudioOutput.isSpeaker] is what makes a chooser able
     * to reason about the same case; both must answer alike for the built-in speaker.
     */
    @Test
    fun `the phone speaker is a speaker to both`() {
        val device = assertNotNull(OutputDevices.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null, AT))
        val output = assertNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null))

        assertTrue(device.isSpeaker)
        assertTrue(output.isSpeaker)
    }

    /** Nothing is active unless something chose it. `AudioOutputRouter` is the only thing that can say. */
    @Test
    fun `an output is not active merely by existing`() {
        val output = assertNotNull(OutputDevices.outputOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))

        assertEquals(false, output.isActive)
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-08-29T09:00:00Z")

        /** Every type the classifier has an opinion about, plus three it must refuse. */
        val TYPES: List<Pair<Int, String?>> = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET to null,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to null,
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Pixel Buds Pro",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to null,
            AudioDeviceInfo.TYPE_BUS to null,
            AudioDeviceInfo.TYPE_USB_DEVICE to "Dock",
            AudioDeviceInfo.TYPE_DOCK to null,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to null,
            AudioDeviceInfo.TYPE_TELEPHONY to null,
            AudioDeviceInfo.TYPE_HDMI to null,
        )
    }
}
