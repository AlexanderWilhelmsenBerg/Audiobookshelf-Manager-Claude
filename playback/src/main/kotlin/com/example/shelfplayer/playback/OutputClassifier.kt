package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only, opt-in inventory of actual Android output handles.
 *
 * No Bluetooth address, advertised name, descriptor, media identity or persistent identifier
 * leaves the process. No playback, output preference, focus or connection policy is changed.
 * Platform IDs are used for snapshot comparison only; exported aliases are session-scoped.
 */
data class OutputClassifierState(
    val recording: Boolean = false,
    val current: List<String> = emptyList(),
    val entries: List<String> = emptyList(),
    val status: String = "Not recording",
)

internal object OutputClassifierFacts {
    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_FM -> "FM"
        else -> "UNRECOGNIZED"
    }

    fun role(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Ambiguous"
        AudioDeviceInfo.TYPE_BUS -> "Unknown(bus-not-proof-of-car)"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Unknown(remote-mix)"
        else -> OutputDevices.outputOf(type, null)?.role?.name ?: "Unclassified"
    }

    /** Never upgrade a transport name or predicted route to proof of the sink. */
    fun evidence(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "transport-only"
        AudioDeviceInfo.TYPE_BUS -> "generic-external-bus"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote-mix-not-endpoint"
        else -> "platform-type-only"
    }

    fun collisions(ids: List<String?>): Int {
        val mapped = ids.filterNotNull()
        return mapped.size - mapped.distinct().size
    }
}

@Singleton
class OutputClassifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val routeLab: AudioRouteLab,
) {
    private val stateMutable = MutableStateFlow(OutputClassifierState())
    val state: StateFlow<OutputClassifierState> = stateMutable.asStateFlow()
    private var callback: AudioDeviceCallback? = null
    private val previous = mutableMapOf<Int, String>()
    private val aliases = mutableMapOf<Int, String>()
    private var nextAlias = 0
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (stateMutable.value.recording) return
        stateMutable.value = stateMutable.value.copy(recording = true, status = "Watching Android output callbacks")
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager == null) {
            stateMutable.value = stateMutable.value.copy(recording = false, status = "AudioManager unavailable")
            return
        }
        val observer = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                sample("devices-added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                sample("devices-removed")
            }
        }
        runCatching { manager.registerAudioDeviceCallback(observer, main) }
            .onSuccess { callback = observer }
            .onFailure {
                stateMutable.value = stateMutable.value.copy(
                    recording = false,
                    status = "Device callback registration unavailable",
                )
                return
            }
        append("capture-start")
        sample("initial-snapshot")
    }

    fun stop() {
        if (!stateMutable.value.recording) return
        callback?.let { observer ->
            context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(observer)
        }
        callback = null
        append("capture-stop")
        stateMutable.value = stateMutable.value.copy(recording = false, status = "Stopped")
    }

    fun clear() {
        stop()
        previous.clear()
        aliases.clear()
        nextAlias = 0
        stateMutable.value = OutputClassifierState()
    }

    fun refresh() {
        if (stateMutable.value.recording) sample("manual-snapshot")
    }

    fun report(version: String): String = buildString {
        appendLine("BookWave Output Classifier")
        appendLine("app: $version")
        appendLine("recording: ${state.value.recording}")
        appendLine("notes: opt-in, session-only aliases, names/addresses never exported")
        appendLine("route: policy prediction is not the actual AudioTrack sink")
        appendLine("classification: observation only; no routing changes")
        appendLine("[current]")
        state.value.current.forEach(::appendLine)
        appendLine("[events]")
        state.value.entries.forEach(::appendLine)
    }

    private fun sample(reason: String) {
        if (!stateMutable.value.recording) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            .getOrElse {
                append("enumeration-unavailable")
                return
            }
        val predicted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                manager.getAudioDevicesForAttributes(attributes).map { it.id }.toSet()
            }.getOrNull()
        } else {
            null
        }
        val seen = linkedMapOf<Int, String>()
        val rows = outputs.map { info ->
            val alias = aliases.getOrPut(info.id) { "o${++nextAlias}" }
            val type = info.type
            val route = when {
                predicted == null -> "unknown"
                info.id in predicted -> "predicted"
                else -> "not-predicted"
            }
            val mapped = OutputDevices.outputOf(type, info.productName)
            val collisionKey = mapped?.id
            val facts = "alias=$alias rawType=$type type=${OutputClassifierFacts.typeName(type)} " +
                "bookWaveRole=${OutputClassifierFacts.role(type)} " +
                "bookWaveMapped=${mapped != null} route=$route " +
                "evidence=${OutputClassifierFacts.evidence(type)} " +
                "sink=${info.isSink} source=${info.isSource} " +
                "channels=${info.channelCounts.sorted().joinToString("+").ifEmpty { "unknown" }} " +
                "rates=${info.sampleRates.sorted().joinToString("+").ifEmpty { "unknown" }} " +
                "trackAlias=${routeLab.deviceAlias(info.id)}"
            seen[info.id] = facts
            collisionKey
        }
        previous.forEach { (id, old) ->
            if (id !in seen) append("output-disappeared ${old.substringBefore(" channels=")}")
        }
        seen.forEach { (id, facts) ->
            if (id !in previous) {
                append("output-appeared $facts")
            } else if (previous[id] != facts) {
                append("output-changed $facts")
            }
        }
        val collisions = OutputClassifierFacts.collisions(rows)
        append(
            "snapshot reason=$reason outputs=${outputs.size} mappedCollisions=$collisions " +
                "predictedCount=${predicted?.size ?: -1}",
        )
        previous.clear()
        previous.putAll(seen)
        stateMutable.value = stateMutable.value.copy(
            current = seen.values.toList(),
            status = "${outputs.size} outputs · $collisions BookWave identity collisions",
        )
    }

    private fun append(line: String) {
        val at = Instant.now().toString()
        stateMutable.value = stateMutable.value.copy(
            entries = (stateMutable.value.entries + "$at $line").takeLast(MAX_ROWS),
        )
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}
