package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat

/** Public profile observation, never a socket, discovery, connection request or active-device setter. */
@SuppressLint("MissingPermission") // Access checked at every sampling boundary; revocation fails closed.
internal class BluetoothProfileProbe(
    private val context: Context,
    private val registry: LiveOutputEndpointRegistry,
    private val main: Handler,
) {
    data class Peer(val identity: String?, val streaming: Boolean?)
    data class Snapshot(val profile: String, val status: String, val peers: List<Peer> = emptyList())
    private class Session(val adapter: BluetoothAdapter, val changed: () -> Unit) {
        val proxies = mutableMapOf<Int, BluetoothProfile>()
        val statuses = mutableMapOf<Int, String>()
        var receiver: BroadcastReceiver? = null
    }

    private var session: Session? = null

    fun start(changed: () -> Unit) {
        if (session != null || !context.hasBluetoothIdentityAccess()) return
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            ?: return
        val current = Session(adapter, changed)
        session = current
        observeBroadcasts(current)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                main.post {
                    if (session !== current || !context.hasBluetoothIdentityAccess()) {
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                    } else {
                        current.proxies.put(profile, proxy)?.takeIf { it !== proxy }?.let {
                            runCatching { adapter.closeProfileProxy(profile, it) }
                        }
                        current.statuses[profile] = "available"
                        changed()
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                main.post {
                    if (session === current) {
                        // Retain the proxy only for eventual close; a disconnected service is not a snapshot.
                        current.statuses[profile] = "service-disconnected"
                        changed()
                    }
                }
            }
        }
        profiles().keys.forEach { profile ->
            current.statuses[profile] = if (runCatching {
                    adapter.getProfileProxy(context, listener, profile)
                }.getOrDefault(false)
            ) {
                "pending"
            } else {
                "unavailable"
            }
        }
    }

    fun stop() {
        val retired = session ?: return
        session = null
        retired.receiver?.let { runCatching { context.unregisterReceiver(it) } }
        retired.proxies.forEach { (profile, proxy) ->
            runCatching { retired.adapter.closeProfileProxy(profile, proxy) }
        }
        retired.proxies.clear()
    }

    fun snapshot(): List<Snapshot> {
        if (!context.hasBluetoothIdentityAccess()) {
            stop()
            return listOf(Snapshot("all", "permission-missing"))
        }
        val current = session ?: return listOf(Snapshot("all", "unavailable"))
        return profiles().map { (profile, name) ->
            val proxy = current.proxies[profile]?.takeIf { current.statuses[profile] == "available" }
            if (proxy == null) {
                Snapshot(name, current.statuses[profile] ?: "unavailable")
            } else {
                runCatching {
                    val connected = proxy.connectedDevices
                    val peers = connected.take(MAX_PEERS).map { device ->
                        Peer(
                            registry.bluetoothIdentity(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, device.address),
                            (proxy as? BluetoothA2dp)?.isA2dpPlaying(device),
                        )
                    }
                    Snapshot(name, if (connected.size > MAX_PEERS) "truncated" else "observed", peers)
                }.getOrElse { Snapshot(name, "read-failed") }
            }
        }
    }

    private fun observeBroadcasts(current: Session) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Broadcast is a wake-up only: ignore all extras and re-read the public profile proxy.
                if (session === current) current.changed()
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            }
        }
        runCatching {
            // Bluetooth is a privileged sender outside the system UID. All actions are protected.
            ContextCompat.registerReceiver(context, receiver, filter, null, main, ContextCompat.RECEIVER_EXPORTED)
        }.onSuccess { current.receiver = receiver }
    }

    private fun profiles(): Map<Int, String> = buildMap {
        put(BluetoothProfile.A2DP, "a2dp")
        put(BluetoothProfile.HEADSET, "hfp")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) put(BluetoothProfile.LE_AUDIO, "le-audio")
    }

    private companion object {
        const val MAX_PEERS = 32
    }
}
