package com.example.shelfplayer.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Process-private correlation only. Never persist/export the address, digest, or derived endpoint token. */
internal class BluetoothEndpointIdentity {
    private val salt = UUID.randomUUID().toString()

    fun capability(type: Int, address: String?): BluetoothIdentityCapability = when {
        type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> BluetoothIdentityCapability.Unsupported

        address.isNullOrEmpty() -> BluetoothIdentityCapability.Empty

        address.contains('X', ignoreCase = true) || address.uppercase(Locale.ROOT) in PLACEHOLDERS ->
            BluetoothIdentityCapability.Redacted

        !MAC.matches(address.uppercase(Locale.ROOT)) -> BluetoothIdentityCapability.Invalid

        else -> BluetoothIdentityCapability.Verified
    }

    fun key(type: Int, address: String?): String? {
        if (capability(type, address) != BluetoothIdentityCapability.Verified) return null
        val canonical = address?.uppercase(Locale.ROOT) ?: return null
        return Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("$salt:$canonical".toByteArray()),
        )
    }

    private companion object {
        val MAC = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
        val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")
    }
}

internal fun Context.hasBluetoothIdentityAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Cached ports may remain redacted after a grant; fresh framework discovery is required. */
internal fun LiveOutputEndpointRegistry.observationOf(device: AudioDeviceInfo): LiveOutputEndpointRegistry.Observation {
    val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && identityAccess) {
        runCatching { device.address }
    } else {
        Result.success(null)
    }
    val capability = when {
        device.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
            BluetoothIdentityCapability.Unsupported

        !identityAccess -> BluetoothIdentityCapability.PermissionMissing

        address.isFailure -> BluetoothIdentityCapability.ReadFailure

        else -> bluetoothCapability(device.type, address.getOrNull())
    }
    return LiveOutputEndpointRegistry.Observation(
        device.id,
        device.type,
        device.productName?.toString(),
        bluetoothIdentity(device.type, address.getOrNull()),
        capability,
    )
}

internal fun LiveOutputEndpointRegistry.find(device: AudioDeviceInfo): LiveOutputEndpointRegistry.Endpoint? =
    observationOf(device).let { find(it.platformId, it.type, it.productName, it.identity) }
