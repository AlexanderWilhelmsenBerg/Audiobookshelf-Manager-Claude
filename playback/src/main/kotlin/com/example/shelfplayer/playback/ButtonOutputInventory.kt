package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/** Physical endpoint evidence stays in process. A unique candidate is a routing preference, never key provenance. */
internal class ButtonOutputInventory(private val context: Context, private val registry: LiveOutputEndpointRegistry) {
    fun matchingPeer(address: String, live: Map<String, AudioDeviceInfo>, collisions: Set<String>): String? {
        if (!context.hasBluetoothIdentityAccess()) return null
        val identity = registry.bluetoothIdentity(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address) ?: return null
        return live.filter { (id, info) -> id !in collisions && registry.find(info)?.identity == identity }
            .keys.singleOrNull()
    }

    fun candidates(live: Map<String, AudioDeviceInfo>, collisions: Set<String>): Set<String> {
        if (collisions.isNotEmpty()) return emptySet()
        val candidates = live.filterValues { info ->
            val output = registry.find(info)?.output ?: return@filterValues false
            output.role == AudioOutputRole.Headset || (output.isHeadsetCandidate && deviceClass(info) !in NON_HEADSETS)
        }.keys
        return candidates.toSet()
    }

    fun soleHeadset(candidates: Set<String>, live: Map<String, AudioDeviceInfo>): String? {
        val id = candidates.singleOrNull() ?: return null
        val info = live[id] ?: return null
        return id.takeIf {
            registry.find(info)?.output?.role == AudioOutputRole.Headset ||
                deviceClass(info) in WEARABLES
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceClass(info: AudioDeviceInfo): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !context.hasBluetoothIdentityAccess()) return null
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            adapter.getRemoteDevice(info.address).bluetoothClass?.deviceClass
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val WEARABLES =
            setOf(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)
        val NON_HEADSETS = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        )
    }
}
