package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.KnownDevice
import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-002 — turning what Android reports into a device this app can hold a policy for.
 *
 * Pure functions on purpose. Everything here is a decision about identity and classification, which is
 * where the bugs are, and none of it needs an `AudioManager` to be tested.
 */
object OutputDevices {

    /**
     * The device that connected, or `null` when it is not something a book can be listened to on.
     *
     * The built-in earpiece, a telephony device, an HDMI port: connecting one of those is not somebody
     * putting headphones in, and arming a book because a phone call routed audio would be noise.
     */
    fun of(type: Int, productName: CharSequence?, at: Instant): KnownDevice? {
        val kind = kindOf(type) ?: return null
        val name = productName?.toString()?.trim().orEmpty()
        return KnownDevice(
            id = idOf(kind, name),
            displayName = name.ifBlank { defaultNameOf(kind) },
            kind = kind,
            lastSeenAt = at,
        )
    }

    /**
     * The same device as something a listener can send audio to **now**, or `null` for one they cannot.
     *
     * Deliberately the same [idOf] and [kindOf] as [of]. The settings list and the player's chooser have to
     * agree about what a device *is*, or a listener sets a policy on one row and picks a different-looking
     * row in the player, with nothing to tell them the two are the same earbuds. Sharing the rule makes that
     * unrepresentable rather than merely unlikely.
     *
     * The wired category is the case that shows why: a 3.5mm jack has no name, so both features must call it
     * the one wired row or the chooser would offer a device the policy list has never heard of.
     */
    fun outputOf(type: Int, productName: CharSequence?, isActive: Boolean = false): AudioOutput? {
        val kind = kindOf(type) ?: return null
        val name = productName?.toString()?.trim().orEmpty()
        return AudioOutput(
            id = idOf(kind, name),
            displayName = name.ifBlank { defaultNameOf(kind) },
            kind = kind,
            isActive = isActive,
        )
    }

    /**
     * ROUTE-002's identity rule.
     *
     * Wired is a **category**: a 3.5mm jack reports no name and no address, so there is one wired policy
     * rather than a fiction of one per pair of headphones. Everything else is keyed by the name it
     * advertises, lower-cased so a device that reports its name with different capitalisation between
     * connections does not become two rows.
     *
     * Never a hardware address. `AudioDeviceInfo.getAddress` returns nothing useful for Bluetooth without
     * `BLUETOOTH_CONNECT`, and ROUTE-002 asks for the minimum Nearby Devices permission — which turns out
     * to be none at all. The cost is that two identically named headsets share a policy, which is a better
     * trade than a permission prompt on a listening app.
     */
    private fun idOf(kind: DeviceKind, name: String): String = when {
        kind == DeviceKind.Wired -> KnownDevice.WIRED_ID
        kind == DeviceKind.Car -> KnownDevice.CAR_ID
        name.isBlank() -> "${kind.name.lowercase()}:unnamed"
        else -> "${kind.name.lowercase()}:${name.lowercase()}"
    }

    /**
     * What kind of thing this is, or `null` for one that cannot reasonably play a book.
     *
     * `TYPE_BUILTIN_SPEAKER` is [DeviceKind.Speaker] rather than excluded, because it is the device
     * ROUTE-002's explicit-content clause is really about — and because it is what audio falls back to when
     * headphones are unplugged, which is a case somebody may well want set to `Never`.
     */
    @Suppress("CyclomaticComplexMethod")
    // InlinedApi: `TYPE_HEARING_AID` (28), `TYPE_BLE_HEADSET` and `TYPE_BLE_SPEAKER` (31) are compile-time
    // `int` constants, and every use here compares one against a value the platform supplied. An API 26
    // device never reports those types, so the branch is simply never taken — there is no call to a method
    // that does not exist, which is what the check is really for.
    @SuppressLint("InlinedApi")
    private fun kindOf(type: Int): DeviceKind? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> DeviceKind.Wired

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> DeviceKind.Bluetooth

        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        -> DeviceKind.Speaker

        AudioDeviceInfo.TYPE_HEARING_AID -> DeviceKind.HearingAid

        // A car's audio bus. Android Auto itself arrives as a media *controller* rather than as an audio
        // device, and `PlaybackService` handles that separately; this covers a head unit wired in as audio.
        AudioDeviceInfo.TYPE_BUS -> DeviceKind.Car

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        -> DeviceKind.Other

        // Everything else — the earpiece, telephony, HDMI, a remote submix — is not somebody settling down
        // with a book.
        else -> null
    }

    /** What to call a device that reports no name. Never a hardware address (ROUTE-002). */
    private fun defaultNameOf(kind: DeviceKind): String = when (kind) {
        DeviceKind.Wired -> "Wired headphones"
        DeviceKind.Bluetooth -> "Bluetooth audio"
        DeviceKind.Car -> "Car audio"
        DeviceKind.HearingAid -> "Hearing aid"
        DeviceKind.Speaker -> "Speaker"
        DeviceKind.Other -> "Audio device"
    }
}
