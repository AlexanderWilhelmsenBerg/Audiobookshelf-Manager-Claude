package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.view.KeyEvent
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.common.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    val inputStatus: String = "Not recording",
    val inputDevices: List<String> = emptyList(),
    val stallIncident: List<String> = emptyList(),
)

@Singleton
class AudioRouteLab @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val eventLog: EventLog,
    private val clock: AppClock,
    private val outputs: AudioOutputRouter,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) {
    private val incidentFile = File(context.noBackupFilesDir, "audio-route-lab.txt")
    private val atomicIncident = AtomicFile(incidentFile)
    private val mutex = Mutex()
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)
    private var writerStarted = false
    private val _state = MutableStateFlow(AudioRouteLabState())
    val state: StateFlow<AudioRouteLabState> = _state.asStateFlow()
    private var eventJob: Job? = null
    private var outputJob: Job? = null
    private var eventChannel: Channel<LoggedEvent>? = null
    private var unsubscribe: (() -> Unit)? = null
    private val recordingGeneration = AtomicLong(0)
    private val frameProgress = AudioTrackProgressEvidence()
    private data class TimingRow(val capture: Long, val track: Long, val atNs: Long)
    private var lastTimingRow: TimingRow? = null

    @Volatile internal var systemIncidentContext: (Instant) -> List<String> = { emptyList() }
    internal var onCaptureStarted: () -> Unit = {}
    private val deviceTimeline = AudioRouteDeviceTimeline()
    private var correlator = AudioRouteTestCorrelator()
    private var deviceCallback: AudioDeviceCallback? = null
    private val mediaInput = MediaInputDiagnostics(
        PlatformMediaInputSource(context),
        emit = { record(it) },
        publish = { status, devices ->
            _state.update { it.copy(inputStatus = status, inputDevices = devices) }
        },
    )

    init {
        if (incidentFile.exists() && clock.now().toEpochMilli() - incidentFile.lastModified() > RETENTION_MS) {
            atomicIncident.delete()
        }
        // Read a bounded on-disk tail: the previous process may have crashed during playback.
        val recovered = runCatching {
            AudioRouteLabEvidence.recent(
                atomicIncident.openRead().bufferedReader().use { it.readLines() },
                clock.now(),
                MAX_ROWS + AudioStallIncidentEvidence.MAX_ROWS,
            )
        }.getOrDefault(emptyList())
        val (pinned, rolling) = recovered.partition(AudioStallIncidentEvidence::isPinned)
        _state.value = _state.value.copy(
            entries = rolling.takeLast(MAX_ROWS),
            stallIncident = pinned.takeLast(AudioStallIncidentEvidence.MAX_ROWS),
        )
    }

    internal fun trackLifecycle(event: String) {
        if (!_state.value.recording || event !in TRACK_LIFECYCLE_EVENTS) return
        record("observer-$event")
    }

    internal fun trackTiming(sample: AudioTrackTimingSample, playback: AudioTrackPlaybackSnapshot? = null) {
        if (!_state.value.recording) return
        val generation = recordingGeneration.get()
        val observation = frameProgress.observe(generation, sample, playback)
        val withinSampleWindow = lastTimingRow?.let {
            it.capture == generation && it.track == sample.observerEpoch &&
                sample.monotonicNs - it.atNs in 0 until TIMING_ROW_INTERVAL_NS
        } ?: false
        if (observation != null || !withinSampleWindow) {
            lastTimingRow = TimingRow(generation, sample.observerEpoch, sample.monotonicNs)
            record(sample.evidence(), generation)
        }
        observation?.let { record(it.evidence(), generation) }
    }

    internal fun deviceAlias(platformId: Int): String = deviceTimeline.aliasFor(platformId)

    /** Read the existing owners; diagnostic capture never retains another copy of playback intent. */
    internal fun systemProbeTarget(): String? = outputs.selectedId.value ?: outputs.lastHeardRouteId()

    internal fun mediaButtonInput(event: KeyEvent?, controller: MediaInputController, entry: MediaInputEntry) {
        mediaInput.mediaKey(event, controller, entry)
    }

    internal fun controllerTransport(play: Boolean, controller: MediaInputController) {
        mediaInput.transport(play, controller)
    }

    internal fun trackObserverStatus(description: String) {
        _state.update { it.copy(reportStatus = description) }
    }

    internal fun actualTrackEvidence(evidence: String) {
        if (!_state.value.recording || !isSafeTrackEvidence(evidence)) return
        _state.update { it.copy(reportStatus = evidence) }
        record("actual-track $evidence")
    }

    private fun isSafeTrackEvidence(evidence: String): Boolean =
        evidence in TRACK_STATES || TRACK_ROUTE_PATTERN.matches(evidence)

    private fun observeDeviceHandles() {
        if (!_state.value.recording) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
                val out = OutputDevices.outputOf(device.type, device.productName)
                AudioRouteDeviceTimeline.Handle(
                    device.id,
                    out?.id ?: "platform:${device.id}:${device.type}",
                    out?.role?.name ?: AudioTrackRouteTypes.label(device.type),
                )
            }
        }.getOrElse {
            record("device-enumeration-unavailable")
            return
        }
        deviceTimeline.observe(devices, outputs.selectedId.value).forEach(::record)
    }

    private fun startDeviceObserver() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = observeDeviceHandles()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = observeDeviceHandles()
        }
        runCatching { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
            .onSuccess { deviceCallback = callback }
            .onFailure { record("device-callback-unavailable") }
        observeDeviceHandles()
    }

    private fun stopDeviceObserver() {
        val manager = context.getSystemService(AudioManager::class.java)
        deviceCallback?.let { callback -> manager?.unregisterAudioDeviceCallback(callback) }
        deviceCallback = null
    }

    fun serviceStarted() {
        if (_state.value.recording) record("playback-service-started")
    }

    fun serviceStopped() {
        if (_state.value.recording) record("playback-service-stopped")
    }

    fun start() {
        if (_state.value.recording) return
        val currentGeneration = recordingGeneration.incrementAndGet()
        _state.update { it.copy(recording = true) }
        record("diagnostic-start", currentGeneration)
        startDeviceObserver()
        mediaInput.start()
        startEventObserver(currentGeneration)
        observeOutputPolicy()
        onCaptureStarted()
    }

    private fun startEventObserver(currentGeneration: Long) {
        val channel = Channel<LoggedEvent>(EVENT_BUFFER_CAPACITY)
        eventChannel = channel
        val droppedEvents = AtomicInteger(0)
        val correlation = AudioRouteTestCorrelator().also { correlator = it }
        // This receiver is allowed to run behind the producer: every event is delivered,
        // or an explicit loss count records when the bounded buffer fills.
        eventJob = scope.launch(io) {
            for (event in channel) {
                val lost = droppedEvents.getAndSet(0)
                if (lost > 0) record("events-dropped count=$lost", currentGeneration, acceptedBeforeStop = true)
                AudioRouteLabEvidence.event(event)?.let { line ->
                    record(line, currentGeneration, acceptedBeforeStop = true, at = event.at)
                    correlation.observe(line, event.at)?.let { correlated ->
                        record(correlated, currentGeneration, acceptedBeforeStop = true, at = event.at)
                    }
                }
            }
            val trailingLoss = droppedEvents.getAndSet(0)
            if (trailingLoss > 0) {
                record("events-dropped count=$trailingLoss", currentGeneration, acceptedBeforeStop = true)
            }
            // Closing the channel drains all accepted events before this marker.
            record("diagnostic-stop", currentGeneration, acceptedBeforeStop = true)
        }
        // The callback only enqueues already-redacted data. No disk writes or Player reads
        // may execute on the audio/logging thread.
        unsubscribe = eventLog.observeWrites { event ->
            if (!channel.trySend(event).isSuccess) droppedEvents.incrementAndGet()
        }
    }

    private fun observeOutputPolicy() {
        outputJob = scope.launch(io) {
            combine(outputs.outputs, outputs.selectedId) { connected, selected -> connected to selected }
                .collect { (connected, selected) ->
                    val summary = AudioRouteLabEvidence.route(connected, selected) +
                        " idCollisions=${collisionCount()}"
                    if (_state.value.route != summary) {
                        _state.update { it.copy(route = summary) }
                        record("route-policy $summary")
                    }
                }
        }
    }

    fun stop() {
        if (!_state.value.recording) return
        closeEventObserver()
        stopDeviceObserver()
        mediaInput.stop()
        _state.update { it.copy(recording = false) }
        // The receiver drains already accepted events before completing; do not cancel
        // its job and silently drop the last hardware-button / route transition.
        outputJob?.cancel()
        outputJob = null
    }

    private fun closeEventObserver(discard: Boolean = false) {
        unsubscribe?.invoke()
        unsubscribe = null
        if (discard) eventJob?.cancel()
        eventChannel?.close()
        eventChannel = null
        eventJob = null
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
        if (action == "ButtonA" || action == "ButtonB") {
            correlator.arm(action, clock.now())
            record("test-armed step=$action attribution=temporal-only")
        } else {
            record("test-step=$action")
        }
    }

    /** Drop queued old rows and delete the stored trace while keeping an active capture and its probes attached. */
    suspend fun clearAndAwait() {
        recordingGeneration.incrementAndGet()
        closeEventObserver(discard = true)
        _state.update { it.copy(entries = emptyList(), stallIncident = emptyList()) }
        if (_state.value.recording) startEventObserver(recordingGeneration.get())
        withContext(io) {
            mutex.withLock { atomicIncident.delete() }
        }
        // Fresh rows may have arrived while the old file was being deleted.
        requestWrite()
        if (_state.value.recording) onCaptureStarted()
    }

    fun report(version: String): String = buildString {
        appendLine("BookWave Audio Route Lab")
        appendLine("app: $version")
        appendLine("recording: ${state.value.recording}")
        appendLine("routePolicy: ${state.value.route}")
        appendLine("actualTrack: ${state.value.reportStatus}")
        appendLine("devices: ${deviceTimeline.snapshot()} (aliases are process-scoped)")
        appendLine("inputStatus: ${state.value.inputStatus}")
        appendLine("inputDevices: ${state.value.inputDevices.joinToString("; ").ifEmpty { "none" }}")
        appendLine("evidence: track-route when available is platform-reported; not human-heard sound")
        appendLine("retention: private no-backup file, at most 24h; opt-in recording")
        appendLine("[incident]")
        state.value.entries.forEach { appendLine(it) }
        appendLine("[first-suspected-output-stall]")
        state.value.stallIncident.forEach { appendLine(it) }
    }

    /** Counts collisions without recording or exporting product names or platform IDs. */
    private fun collisionCount(): Int {
        val manager = context.getSystemService(AudioManager::class.java) ?: return 0
        val ids = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { OutputDevices.outputOf(it.type, it.productName)?.id }
        return ids.size - ids.distinct().size
    }

    private fun record(
        value: String,
        generation: Long = recordingGeneration.get(),
        acceptedBeforeStop: Boolean = false,
        at: Instant = clock.now(),
    ) {
        if (generation != recordingGeneration.get()) return
        if (!_state.value.recording && !acceptedBeforeStop) return
        // The only dynamic values admitted here are generated by the allow-listed
        // classifier, safe route-role summary and fixed UI enum choices.
        val line = "$at $value"
        val trigger = AudioStallIncidentEvidence.isTrigger(value)
        val system = stallContext(trigger, at)
        _state.update { current ->
            if (generation != recordingGeneration.get() || (!current.recording && !acceptedBeforeStop)) {
                current
            } else {
                current.copy(
                    entries = AudioRouteLabEvidence.recent(current.entries + line, clock.now(), MAX_ROWS),
                    stallIncident = AudioStallIncidentEvidence.retain(current, system, line, trigger, clock.now()),
                )
            }
        }
        requestWrite()
    }

    private fun stallContext(trigger: Boolean, at: Instant): List<String> = if (trigger &&
        AudioRouteLabEvidence.recent(
            _state.value.stallIncident,
            clock.now(),
            AudioStallIncidentEvidence.MAX_ROWS,
        ).isEmpty()
    ) {
        systemIncidentContext(at)
    } else {
        emptyList()
    }

    /** One conflated writer bounds queued IO work even during input/device callback storms. */
    @Synchronized
    private fun requestWrite() {
        if (!writerStarted) {
            writerStarted = true
            scope.launch(io) {
                writeRequests.consumeEach { persist() }
            }
        }
        writeRequests.trySend(Unit)
    }

    private suspend fun persist() {
        mutex.withLock {
            // Signals always flush the latest snapshot. An old producer cannot replace a newer
            // generation token in the conflated queue or recreate a file after Clear.
            val snapshot = _state.value
            if (snapshot.entries.isEmpty() && snapshot.stallIncident.isEmpty()) return@withLock
            runCatching {
                incidentFile.parentFile?.mkdirs()
                // Coalescing disk snapshots never coalesces the bounded in-memory event sequence.
                val bytes = (snapshot.stallIncident + snapshot.entries).joinToString("\n", postfix = "\n").toByteArray()
                val stream = atomicIncident.startWrite()
                try {
                    stream.write(bytes)
                    atomicIncident.finishWrite(stream)
                } catch (expected: IOException) {
                    atomicIncident.failWrite(stream)
                }
            }
        }
    }

    private companion object {
        // Only known diagnostic state transitions may reach durable storage, never arbitrary text.
        val TRACK_LIFECYCLE_EVENTS = setOf(
            "player-create recording=true", "provider-installed", "provider-fallback",
            "provider-output-unobserved", "provider-released", "audio-track-attached",
            "audio-track-attach-failed", "audio-track-playing", "audio-track-route-changed",
            "audio-track-released-or-replaced",
        )
        val TRACK_STATES = setOf(
            "released-or-uninitialized-no-route-measurement",
            "paused-no-route-measurement",
            "stopped-no-route-measurement",
            "playstate-unknown-no-route-measurement",
            "playing-route-unknown",
            "route-query-unavailable",
        )
        val TRACK_ROUTE_PATTERN = Regex(
            """playing-to=(?:d[0-9]+|unmapped):[A-Za-z]+(?:\+(?:d[0-9]+|unmapped):[A-Za-z]+)* measured=AudioTrack""",
        )
        const val MAX_ROWS = 350
        const val EVENT_BUFFER_CAPACITY = 1024
        const val TIMING_ROW_INTERVAL_NS = 1_000_000_000L
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        val OBSERVATIONS = setOf("Headset", "HeadsetA", "HeadsetB", "Car", "Phone", "Silence", "Skipping")
        val MARKERS = setOf("Baseline", "CarBluetooth", "AndroidAuto", "ButtonA", "ButtonB", "CarPlay")
    }
}
