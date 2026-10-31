package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.os.Build
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Process-private correlation only. Never persist/export the address, digest, or derived endpoint token. */
internal class BluetoothEndpointIdentity {
    private val salt = UUID.randomUUID().toString()

    fun key(type: Int, address: String?): String? {
        val canonical = address?.uppercase(Locale.ROOT) ?: return null
        val unicast = MAC.matches(canonical) && canonical.take(2).toInt(HEX_RADIX) and 1 == 0
        if (type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || !unicast || canonical in PLACEHOLDERS) return null
        return Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("$salt:$canonical".toByteArray()),
        )
    }

    private companion object {
        const val HEX_RADIX = 16
        val MAC = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
        val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")
    }
}

/** Android boundary shared by inventory and actual-track reads; no permissions or Bluetooth scans added. */
internal fun LiveOutputEndpointRegistry.observationOf(device: AudioDeviceInfo): LiveOutputEndpointRegistry.Observation =
    LiveOutputEndpointRegistry.Observation(
        device.id,
        device.type,
        device.productName?.toString(),
        bluetoothIdentity(
            device.type,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { device.address }.getOrNull() else null,
        ),
    )

internal fun LiveOutputEndpointRegistry.find(device: AudioDeviceInfo): LiveOutputEndpointRegistry.Endpoint? =
    observationOf(device).let { find(it.platformId, it.type, it.productName, it.identity) }
