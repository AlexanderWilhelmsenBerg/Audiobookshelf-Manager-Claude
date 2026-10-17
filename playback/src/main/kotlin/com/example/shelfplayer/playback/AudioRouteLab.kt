package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioManager
import android.util.AtomicFile
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only Audio Route Lab. This class never calls Player methods, requests focus,
 * sets an audio route or changes a MediaSession command.
 *
 * A private, bounded incident file survives process death ONLY after explicit opt-in.
 * It contains strictly allow-listed, already redacted event attributes and classified
 * output roles. It never contains media titles, server URLs, device names or MACs.
 */
data class AudioRouteLabState(
    val recording: Boolean = false,
    val entries: List<String> = emptyList(),
    val route: String = "Not measured",
    val reportStatus: String = "Track-level routing unavailable (observer not installed)",
)

@Singleton
class AudioRouteLab @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val eventLog: EventLog,
    private val outputs: AudioOutputRouter,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val incidentFile = File(context.noBackupFilesDir, "audio-route-lab.txt")
    private val atomicIncident = AtomicFile(incidentFile)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AudioRouteLabState())
    val state: StateFlow<AudioRouteLabState> = _state.asStateFlow()
    private var eventJob: Job? = null
    private var outputJob: Job? = null

    init {
        if (incidentFile.exists() && System.currentTimeMillis() - incidentFile.lastModified() > RETENTION_MS) {
            incidentFile.delete()
        }
        // Read a bounded on-disk tail: the previous process may have crashed during playback.
        val recovered = runCatching {
            AudioRouteLabEvidence.recent(
                atomicIncident.openRead().bufferedReader().use { it.readLines() },
                Instant.now(),
                MAX_ROWS,
            )
        }.getOrDefault(emptyList())
        _state.value = _state.value.copy(entries = recovered)
        if (preferences.getBoolean(ENABLED, false)) start()
    }

    fun serviceStarted() {
        if (_state.value.recording) record("playback-service-started")
    }

    fun serviceStopped() {
        if (_state.value.recording) record("playback-service-stopped")
    }

    fun start() {
        if (_state.value.recording) return
        preferences.edit().putBoolean(ENABLED, true).apply()
        _state.value = _state.value.copy(recording = true)
        record("diagnostic-start")
        eventJob = scope.launch(io) {
            var previous: Any? = null
            eventLog.events.collect { snapshot ->
                val last = snapshot.lastOrNull() ?: return@collect
                if (last === previous) return@collect
                previous = last
                val line = AudioRouteLabEvidence.event(last) ?: return@collect
                record(line)
            }
        }
        outputJob = scope.launch(io) {
            combine(outputs.outputs, outputs.selectedId) { connected, selected -> connected to selected }
                .collect { (connected, selected) ->
                    val summary = AudioRouteLabEvidence.route(connected, selected) +
                        " idCollisions=${collisionCount()}"
                    if (_state.value.route != summary) {
                        _state.value = _state.value.copy(route = summary)
                        record("route-policy $summary")
                    }
                }
        }
    }

    fun stop() {
        preferences.edit().putBoolean(ENABLED, false).apply()
        _state.value = _state.value.copy(recording = false)
        eventJob?.cancel()
        outputJob?.cancel()
        eventJob = null
        outputJob = null
    }

    /** User-observed destination, not a measured audio-track output. */
    fun heardAt(destination: String) {
        if (!_state.value.recording) return
        if (destination !in OBSERVATIONS) return
        record("user-heard=$destination")
    }

    fun mark(action: String) {
        if (!_state.value.recording) return
        if (action !in MARKERS) return
        record("test-step=$action")
    }

    fun clear() {
        stop()
        _state.value = AudioRouteLabState()
        scope.launch(io) {
            mutex.withLock {
                atomicIncident.delete()
            }
        }
    }

    fun report(version: String): String = buildString {
        appendLine("BookWave Audio Route Lab")
        appendLine("app: $version")
        appendLine("recording: ${state.value.recording}")
        appendLine("routePolicy: ${state.value.route}")
        appendLine("actualTrack: unavailable; not installed in this passive instrumentation")
        appendLine("evidence: router-selected and platform policy routes are NOT the AudioTrack sink")
        appendLine("retention: private no-backup file, at most 24h; opt-in recording")
        appendLine("[incident]")
        state.value.entries.forEach { appendLine(it) }
    }

    /** Counts collisions without recording or exporting product names or platform IDs. */
    private fun collisionCount(): Int {
        val manager = context.getSystemService(AudioManager::class.java) ?: return 0
        val ids = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { OutputDevices.outputOf(it.type, it.productName)?.id }
        return ids.size - ids.distinct().size
    }

    private fun record(value: String) {
        if (!_state.value.recording) return
        // The only dynamic values admitted here are generated by the allow-listed
        // classifier, safe route-role summary and fixed UI enum choices.
        val line = "${Instant.now()} $value"
        val updated = AudioRouteLabEvidence.recent(_state.value.entries + line, Instant.now(), MAX_ROWS)
        _state.value = _state.value.copy(entries = updated)
        scope.launch(io) {
            mutex.withLock {
                runCatching {
                    incidentFile.parentFile?.mkdirs()
                    // AtomicFile leaves the previous completed capture available if this process
                    // crashes while replacing the latest bounded incident snapshot.
                    val bytes = _state.value.entries.joinToString("\n", postfix = "\n").toByteArray()
                    val stream = atomicIncident.startWrite()
                    try {
                        stream.write(bytes)
                        atomicIncident.finishWrite(stream)
                    } catch (failure: Exception) {
                        atomicIncident.failWrite(stream)
                    }
                }
            }
        }
    }

    private companion object {
        const val PREFS = "audio-route-lab"
        const val ENABLED = "enabled"
        const val MAX_ROWS = 350
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        val OBSERVATIONS = setOf("HeadsetA", "HeadsetB", "Car", "Phone", "Silence")
        val MARKERS = setOf("Baseline", "CarBluetooth", "AndroidAuto", "ButtonA", "ButtonB", "CarPlay")
    }
}
