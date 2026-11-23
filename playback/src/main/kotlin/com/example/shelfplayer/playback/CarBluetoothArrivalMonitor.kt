package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info

/**
 * Feeds [CarBluetoothArrivalWindow] from the public hands-free connection broadcast.
 *
 * Needs the optional Nearby devices access the output chooser already asks for; without it nothing is
 * read and the window never opens, which leaves the existing Auto-boundary behavior. Only the device class
 * Android reports for a car kit opens the window. No scan, no connection, no address in any log.
 */
internal class CarBluetoothArrivalMonitor(
    private val context: Context,
    private val window: CarBluetoothArrivalWindow,
    private val logger: Logger,
    private val onCarLink: () -> Unit = {},
) {
    private var registered = false

    /** Salted per instance: the same car can be recognized leaving, and nothing outlives this monitor. */
    private val identity = BluetoothEndpointIdentity()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) onHandsFreeChanged(intent)
        }
    }

    fun start() {
        if (registered) return
        registered = try {
            // Bluetooth is a privileged sender outside the system UID. The action is protected.
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop() {
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by a process teardown; the window is closed below either way.
            }
            registered = false
        }
        window.close()
    }

    private fun onHandsFreeChanged(intent: Intent) {
        val access = context.hasBluetoothIdentityAccess()
        val device = if (access) {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            null
        }
        onHandsFreeChanged(
            access = access,
            state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED),
            deviceClass = device?.let(::deviceClass),
            peer = device?.let(::peerKey),
        )
    }

    /** The whole policy of this adapter, free of framework objects so it can be tested directly. */
    internal fun onHandsFreeChanged(access: Boolean, state: Int, deviceClass: Int?, peer: String?) {
        when {
            !access -> window.close()

            state == BluetoothProfile.STATE_DISCONNECTED -> window.onHandsFreeDisconnected(peer)

            state == BluetoothProfile.STATE_CONNECTED && deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> {
                window.onCarHandsFreeConnected(peer)
                logger.info(
                    LogCategory.AndroidAuto,
                    "Car-class hands-free link connected; arrival window opened",
                    LogField.Millis("window", CarBluetoothArrivalWindow.LIFETIME.inWholeMilliseconds),
                )
                onCarLink()
            }
        }
    }

    @SuppressLint("MissingPermission") // Guarded by hasBluetoothIdentityAccess() before any device read.
    private fun deviceClass(device: BluetoothDevice): Int? = try {
        device.bluetoothClass?.deviceClass
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun peerKey(device: BluetoothDevice): String? = try {
        identity.key(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, device.address)
    } catch (_: SecurityException) {
        null
    }
}
