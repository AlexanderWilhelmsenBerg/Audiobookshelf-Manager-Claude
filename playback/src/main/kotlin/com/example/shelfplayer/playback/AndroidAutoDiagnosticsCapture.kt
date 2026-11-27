package com.example.shelfplayer.playback

import android.content.Context
import android.os.Build
import androidx.core.content.edit
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Independent, opt-in history of observed car boundaries. Never issues player or routing commands. */
@Singleton
class AndroidAutoDiagnosticsCapture @Inject internal constructor(
    @ApplicationContext context: Context,
    private val clock: AppClock,
    events: EventLog,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
    private val monitor: AudioTrackRouteMonitor? = null,
) {
    data class Status(val enabled: Boolean, val recordingAttempt: Long? = null)

    private val prefs = context.getSharedPreferences("android-auto-diagnostics", Context.MODE_PRIVATE)
    private val storage = AndroidAutoCaptureStore(File(context.noBackupFilesDir, "android-auto-diagnostics"))
    private val stateMutable = MutableStateFlow(Status(prefs.getBoolean("enabled", false)))
    val state = stateMutable.asStateFlow()
    private var sequence = prefs.getLong("attempt", 0L)
    private var connected = false
    private var active: AndroidAutoCaptureAttempt? = null
    private var awaitingConnection: Job? = null
    private var departureWindow: Job? = null
    private var departed: AndroidAutoCaptureAttempt? = null
    private var lastTrack: String? = null
    private val finished = linkedMapOf<Long, AndroidAutoCaptureAttempt>()
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)
    private val storageMutex = Mutex()
    private val historyRevision = MutableStateFlow(0L)
    val drives = historyRevision.map { readRows(null, AndroidAutoDrive::from).asReversed() }.flowOn(io)

    init {
        monitor?.driveFlush?.setEnabled(state.value.enabled)
        events.observeWrites(::readLog)
        scope.launch(io) {
            writeRequests.consumeEach {
                delay(WRITE_INTERVAL)
                persist()
            }
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (state.value.enabled == enabled) return
        prefs.edit { putBoolean("enabled", enabled) }
        stateMutable.value = Status(enabled)
        monitor?.driveFlush?.setEnabled(enabled)
        if (!enabled) {
            finish("capture-disabled")
            closeDepartureWindow()
        }
        if (enabled && connected) confirmed("enabled-during-connection", null, null)
    }

    @Synchronized
    internal fun carBluetoothLink(headsetPresent: Boolean?, playingHeadset: String?) {
        if (!state.value.enabled || connected || active != null) return
        val number = open("car-bluetooth", headsetPresent, playingHeadset).number
        awaitingConnection = scope.launch {
            delay(CONNECTION_WINDOW)
            expireUnconfirmed(number)
        }
    }

    @Synchronized
    private fun expireUnconfirmed(number: Long) {
        if (active?.number == number && !connected) finish("connection-unconfirmed")
    }

    @Synchronized
    internal fun confirmed(evidence: String, headsetPresent: Boolean?, playingHeadset: String?) {
        connected = true
        if (!state.value.enabled) return
        awaitingConnection?.cancel()
        awaitingConnection = null
        val attempt = active ?: open(evidence, headsetPresent, playingHeadset)
        if (attempt.confirm(clock.now(), evidence, headsetPresent)) {
            attempt.nativeTrace?.boundary("car-confirmed", DriveRouteTrace.ARRIVAL_MS)
            historyRevision.value++
        }
        enqueueWrite()
    }

    @Synchronized
    internal fun disconnected(reason: String) {
        connected = false
        finish(reason)
        if (reason == "service-stopped") closeDepartureWindow("service-stopped")
    }

    @Synchronized
    internal fun positionDiscontinuity(reason: Int, fromMs: Long, toMs: Long, sameItem: Boolean) {
        val attempt = active ?: departed ?: return
        attempt.event(
            clock.now(),
            "player-discontinuity",
            "reasonCode=$reason previousMs=$fromMs nextMs=$toMs sameItem=$sameItem " +
                "monotonicNs=${clock.elapsed().inWholeNanoseconds}",
        )
        enqueueWrite()
    }

    @Synchronized
    internal fun interrupted(reason: String) {
        active?.let { attempt ->
            attempt.interrupted(clock.now(), clock.elapsed(), reason)
            enqueueWrite()
        }
    }

    @Synchronized
    internal fun transport(play: Boolean) {
        active?.let { attempt ->
            attempt.transport(play, clock.now(), clock.elapsed())
            enqueueWrite()
        }
    }

    @Synchronized
    internal fun verifiedHeadset(id: String) {
        active?.let { attempt ->
            if (attempt.verified(clock.now(), clock.elapsed(), id)) enqueueWrite()
        }
    }

    internal fun trackRoute(read: () -> String) {
        if (state.value.recordingAttempt == null) return
        val route = read()
        synchronized(this) {
            val attempt = active ?: return
            if (lastTrack == route) return
            lastTrack = route
            attempt.event(clock.now(), "actual-track", route)
            enqueueWrite()
        }
    }

    /** Preserve the intent reset even if a disconnection or book change happens while already paused. */
    @Synchronized
    internal fun intentReset() {
        active?.let { attempt ->
            attempt.pause(clock.now(), clock.elapsed(), outcome = "intent-changed")
            enqueueWrite()
        }
    }

    private fun open(source: String, headsetPresent: Boolean?, playingHeadset: String?): AndroidAutoCaptureAttempt {
        closeDepartureWindow()
        val number = ++sequence
        lastTrack = null
        prefs.edit { putLong("attempt", number) }
        val at = clock.now()
        return AndroidAutoCaptureAttempt(number, at, clock.elapsed(), source, headsetPresent, playingHeadset).also {
            active = it
            it.event(at, "started", source)
            startTrace(it)
            stateMutable.value = Status(true, number)
            historyRevision.value++
            enqueueWrite()
        }
    }

    private fun finish(reason: String) {
        val attempt = active ?: return
        awaitingConnection?.cancel()
        awaitingConnection = null
        active = null
        attempt.end(clock.now(), clock.elapsed(), reason)
        if (reason == "projection-disconnect" || reason == "final-car-disconnect") {
            departed = attempt
            attempt.departureObservation = "recording"
            attempt.nativeTrace?.departure()
            departureWindow = scope.launch {
                delay(DriveRouteTrace.DEPARTURE_MS)
                synchronized(this@AndroidAutoDiagnosticsCapture) {
                    if (departed === attempt) closeDepartureWindow("complete")
                }
            }
        } else {
            archive(attempt)
        }
        stateMutable.value = Status(state.value.enabled)
        historyRevision.value++
        enqueueWrite()
    }

    private fun startTrace(attempt: AndroidAutoCaptureAttempt) {
        attempt.nativeTrace = monitor?.let { DriveRouteTrace(it, scope, clock, ::enqueueWrite) }
        attempt.nativeTrace?.boundary("drive-start", DriveRouteTrace.ARRIVAL_MS)
    }

    private fun closeDepartureWindow(outcome: String = "ended-early") {
        departureWindow?.cancel()
        departureWindow = null
        departed?.let {
            it.departureObservation = outcome
            archive(it)
            historyRevision.value++
        }
        departed = null
    }

    private fun archive(attempt: AndroidAutoCaptureAttempt) {
        attempt.nativeTrace?.close()
        finished[attempt.number] = attempt
        if (finished.size > MAX_ATTEMPTS) finished.remove(finished.keys.first())
        enqueueWrite()
    }

    @Synchronized
    private fun readLog(event: LoggedEvent) {
        val attempt = active ?: departed ?: return
        val row = AudioRouteLabEvidence.event(event) ?: return
        val kind = row.substringBefore(' ')
        if (kind !in CAPTURE_EVENTS) return
        if (active == null && kind !in DEPARTURE_EVENTS) return
        attempt.event(event.at, kind, row.substringAfter(' ', ""))
        enqueueWrite()
    }

    private fun enqueueWrite() {
        writeRequests.trySend(Unit)
    }

    private suspend fun persist() = storageMutex.withLock {
        val (completed, current) = synchronized(this) {
            finished.toMap() to (active ?: departed)?.snapshot(clock.elapsed())
        }
        for ((number, attempt) in completed) {
            if (storage.write(attempt.snapshot(clock.elapsed()))) {
                synchronized(this) { finished.remove(number, attempt) }
            }
        }
        current?.let(storage::write)
        if (completed.isNotEmpty()) storage.prune(cutoff())
    }

    suspend fun clearAndAwait() = withContext(io) {
        storageMutex.withLock {
            synchronized(this@AndroidAutoDiagnosticsCapture) {
                closeDepartureWindow()
                finished.clear()
                lastTrack = null
                monitor?.driveFlush?.setEnabled(state.value.enabled)
                active?.let {
                    it.nativeTrace?.close()
                    it.clear(clock.now())
                    startTrace(it)
                }
                historyRevision.value++
            }
            storage.clear()
        }
        enqueueWrite()
    }

    suspend fun report(build: String, selected: Set<Long>? = null): String = withContext(io) {
        val rows = readRows(selected) { it }
        if (selected != null && rows.size != selected.size) throw IOException("Selected drive is no longer retained")
        AndroidAutoCaptureReport.format(build, Build.VERSION.SDK_INT, clock.now(), rows)
    }

    private suspend fun <T> readRows(selected: Set<Long>?, transform: (JSONObject) -> T): List<T> =
        storageMutex.withLock {
            val cutoff = cutoff()
            storage.prune(cutoff)
            val memory = synchronized(this@AndroidAutoDiagnosticsCapture) {
                val current = (active ?: departed)?.takeIf { selected == null || it.number in selected }
                finished.filterKeys { selected == null || it in selected }.values.map { it.snapshot(clock.elapsed()) } +
                    listOfNotNull(current?.snapshot(clock.elapsed()))
            }
            fun entry(row: JSONObject) = row.getLong("attempt") to transform(row)
            val old = storage.read(cutoff, selected) { entry(closeInterruptedCapture(it)) }
            (old + memory.map(::entry)).toMap().toSortedMap().values.toList().takeLast(MAX_ATTEMPTS)
        }

    private fun cutoff() = clock.now().minusSeconds(RETENTION.inWholeSeconds)

    private fun closeInterruptedCapture(row: JSONObject): JSONObject {
        if (row.optString("departureObservation") ==
            "recording"
        ) {
            row.put("departureObservation", "recording-interrupted")
        }
        if (!row.isNull("endReason")) return row
        row.put("endReason", "recording-interrupted")
        val reconnects = row.optJSONArray("headsetReconnects") ?: JSONArray()
        for (i in 0 until reconnects.length()) {
            val reconnect = reconnects.getJSONObject(i)
            if (reconnect.optString("outcome") == "pending") reconnect.put("outcome", "capture-ended")
        }
        return row
    }

    internal companion object {
        const val MAX_ATTEMPTS = 100
        private val WRITE_INTERVAL = 500.milliseconds
        private val CONNECTION_WINDOW = 30.seconds
        private val RETENTION = 30.days
        private val DEPARTURE_EVENTS = setOf(
            "playback-intent",
            "media3-playback",
            "preferred-output-request",
            "playback-error",
            "recovery-step",
            "position-compensation",
            "position-refusal",
            "car-output-loss",
        )
        private val CAPTURE_EVENTS = setOf(
            "arrival-settle", "car-bluetooth-inference", "car-bluetooth-window", "car-controller-bound",
            "car-controller-unbound", "car-recovery-outcome", "car-route-intent", "connection-entry",
            "continuity-decision", "focus-observation", "hardware-button", "headset-take-back", "media3-playback",
            "output-proof", "playback-error", "playback-intent", "playback-parameters", "preferred-output-request",
            "projection-change", "recovery-step", "route-policy-updated", "transport-intent", "car-output-loss",
            "position-compensation", "position-refusal", "displacement-kept", "transport-batch",
        )
    }
}
