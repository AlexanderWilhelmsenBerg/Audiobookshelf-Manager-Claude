package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** Registered only while an early debug case is applied. The address is used for an in-memory join only. */
internal class HeadsetStreamReceiver(private val context: Context, private val stream: (String, Boolean) -> Unit) :
    BroadcastReceiver() {
    private var registered = false

    fun watch(enabled: Boolean) {
        if (enabled == registered) return
        registered = if (enabled) {
            try {
                ContextCompat.registerReceiver(
                    context,
                    this,
                    IntentFilter(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                true
            } catch (_: SecurityException) {
                false
            }
        } else {
            context.unregisterReceiver(this)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (!registered || intent.action != BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED ||
            !context.hasBluetoothIdentityAccess()
        ) {
            return
        }
        val playing = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothA2dp.STATE_PLAYING -> true
            BluetoothA2dp.STATE_NOT_PLAYING -> false
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val address = try {
            device?.address
        } catch (_: SecurityException) {
            null
        }
        address?.let { stream(it, playing) }
    }
}
