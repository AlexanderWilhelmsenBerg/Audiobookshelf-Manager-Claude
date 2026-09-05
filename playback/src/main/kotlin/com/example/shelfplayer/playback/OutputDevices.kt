package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.KnownDevice
import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-002 — turning what Android reports into a device this app can hold a policy for.
 *
 * Pure functions on purpose. Everything here is a decision about identity and classification, which is
 * where the bugs are, and none of it needs an `AudioManager` to be tested.
 *
 * [DeviceKind] remains the transport-shaped identity used by the existing per-device policy. [roleOf] is
 * separate: a Bluetooth transport is not itself evidence that the destination is a headset.
 */
object OutputDevices {

    /** The device that connected, or `null` when it is not something a book can be listened to on. */
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
     * The same device as a live output, with a semantic role kept separate from its transport.
     *
     * Classic A2DP is [AudioOutputRole.Ambiguous]: Android's public audio-device type covers earbuds,
     * speakers and many car links. BLE headsets, wired headsets and hearing aids are definite headsets;
     * built-in/BLE speakers are definite speakers; an audio bus is a car.
     */
    fun outputOf(type: Int, productName: CharSequence?, isActive: Boolean = false): AudioOutput? {
        val kind = kindOf(type) ?: return null
        val name = productName?.toString()?.trim().orEmpty()
        return AudioOutput(
            id = idOf(kind, name),
            displayName = name.ifBlank { defaultNameOf(kind) },
            kind = kind,
            isActive = isActive,
            role = roleOf(type),
        )
    }

    /** ROUTE-002's stable, permission-free identity rule. */
    private fun idOf(kind: DeviceKind, name: String): String = when {
        kind == DeviceKind.Wired -> KnownDevice.WIRED_ID
        kind == DeviceKind.Car -> KnownDevice.CAR_ID
        name.isBlank() -> "${kind.name.lowercase()}:unnamed"
        else -> "${kind.name.lowercase()}:${name.lowercase()}"
    }

    @Suppress("CyclomaticComplexMethod")
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
        AudioDeviceInfo.TYPE_BUS -> DeviceKind.Car

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        -> DeviceKind.Other

        else -> null
    }

    /**
     * Semantic destination role. This is intentionally not derived from [kindOf]: Bluetooth is a transport,
     * while Headset and Car are user-facing meanings.
     */
    @SuppressLint("InlinedApi")
    private fun roleOf(type: Int): AudioOutputRole = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> AudioOutputRole.Headset

        AudioDeviceInfo.TYPE_BUS -> AudioOutputRole.Car

        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        -> AudioOutputRole.Speaker

        // A2DP alone cannot honestly distinguish AirPods from a dashboard or a Bluetooth speaker.
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputRole.Ambiguous
        else -> AudioOutputRole.Other
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
