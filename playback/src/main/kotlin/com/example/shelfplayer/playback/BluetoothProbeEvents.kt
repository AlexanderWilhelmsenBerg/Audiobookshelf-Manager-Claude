package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.core.content.IntentCompat
import com.example.shelfplayer.core.common.time.AppClock

/** Bounded, opt-in broadcast observations. Never interpreted as routing or physical-button authority. */
internal class BluetoothProbeEvents(private val registry: LiveOutputEndpointRegistry, private val clock: AppClock) {
    private data class Event(
        val sequence: Long,
        val kind: String,
        val identity: String?,
        val previous: Int?,
        val state: Int?,
        val at: Long,
    )
    private val pending = ArrayDeque<Event>()
    private var sequence = 0L
    private var dropped = 0L

    @SuppressLint("MissingPermission") // Owning receiver checks BLUETOOTH_CONNECT before reading any extras.
    fun record(intent: Intent?) {
        if (intent == null) return
        val kind = actions()[intent.action] ?: return
        val adapter = intent.action == BluetoothAdapter.ACTION_STATE_CHANGED
        val identity = runCatching {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?.let { registry.bluetoothIdentity(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, it.address) }
        }.getOrNull()
        val previous =
            state(intent, if (adapter) BluetoothAdapter.EXTRA_PREVIOUS_STATE else BluetoothProfile.EXTRA_PREVIOUS_STATE)
        val current = state(intent, if (adapter) BluetoothAdapter.EXTRA_STATE else BluetoothProfile.EXTRA_STATE)
        if (pending.size == MAX_EVENTS) {
            pending.removeFirst()
            dropped++
        }
        pending.addLast(Event(++sequence, kind, identity, previous, current, clock.elapsed().inWholeMilliseconds))
    }

    fun drain(alias: (String) -> String): List<String> = buildList {
        if (dropped > 0) add("bluetooth-event-overflow dropped=$dropped")
        dropped = 0
        while (pending.isNotEmpty()) {
            val event = pending.removeFirst()
            val peer = event.identity?.let(alias) ?: "unavailable"
            add(
                "bluetooth-event event=${event.kind} eventSequence=${event.sequence} peer=$peer " +
                    "previous=${event.previous ?: "unknown"} state=${event.state ?: "unknown"} " +
                    "observedElapsedMs=${event.at} evidence=broadcast-only",
            )
        }
    }

    fun clear() {
        pending.clear()
        dropped = 0
        sequence = 0
    }

    private fun state(intent: Intent, key: String): Int? = runCatching {
        if (intent.hasExtra(key)) intent.getIntExtra(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE } else null
    }.getOrNull()

    companion object {
        private const val MAX_EVENTS = 64

        fun actions(): Map<String, String> = buildMap {
            put(BluetoothAdapter.ACTION_STATE_CHANGED, "adapter-state")
            put(BluetoothDevice.ACTION_ACL_CONNECTED, "acl-connected")
            put(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED, "acl-disconnect-requested")
            put(BluetoothDevice.ACTION_ACL_DISCONNECTED, "acl-disconnected")
            put(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, "a2dp-connection")
            put(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED, "a2dp-streaming")
            put(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, "hfp-connection")
            put(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED, "hfp-audio")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                put(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED, "le-connection")
            }
        }
    }
}
