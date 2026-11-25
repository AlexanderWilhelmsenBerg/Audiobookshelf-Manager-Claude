package com.example.shelfplayer.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class CarLabCase(
    val label: String,
    internal val engine: String,
    internal val focus: String,
    internal val pin: Boolean = false,
) {
    Book("Current BookWave player", "BookWave", "user-settings"),
    Exo("ExoPlayer", "ExoPlayer", "observe"),
    Platform("Android MediaPlayer", "MediaPlayer", "observe"),
    Vlc("LibVLC", "LibVLC", "observe"),
    Pcm("MediaCodec + AudioTrack", "MediaCodec-AudioTrack", "observe"),
    Preferred("MediaPlayer · prefer starting headset", "MediaPlayer", "observe", true),
    Cooperate("MediaPlayer · pause for focus loss", "MediaPlayer", "pause"),
}

data class CarLabStatus(val run: Int = 0, val case: CarLabCase? = null, val message: String = "")

/** Bounded, app-private run history. UTC intervals also identify late-arriving recorder events. */
class CarLabJournal internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences("car-playback-tests", Context.MODE_PRIVATE)
    private var next = prefs.getInt("next", 1)
    private val runs = JSONArray(
        ((next - MAX_RUNS).coerceAtLeast(1) until next).mapNotNull { id ->
            prefs.getString("run-$id", null)?.let(::JSONObject)
        },
    )
    private val mutable = MutableStateFlow(CarLabStatus())
    val status = mutable.asStateFlow()
    private val last get() = runs.optJSONObject(runs.length() - 1)
    private val active get() = last?.takeUnless { it.has("endUtc") }

    init {
        active?.let { finish("process-interrupted", Instant.parse(it.getString("lastObservedUtc")).plusNanos(1)) }
        publish()
    }

    @Synchronized
    internal fun begin(case: CarLabCase, now: Instant = Instant.now()): Int {
        finish("next-test", now)
        if (runs.length() >= MAX_RUNS) runs.remove(0)
        val id = next++
        runs.put(
            JSONObject().put("runId", id).put("caseId", case.name).put("label", case.label)
                .put("engine", case.engine).put("focusPolicy", case.focus).put("preferStartingHeadset", case.pin)
                .put("startUtc", now.toString()).put("lastObservedUtc", now.toString()).put("events", JSONArray()),
        )
        persist()
        publish()
        return id
    }

    @Synchronized
    internal fun record(kind: String, details: JSONObject = JSONObject(), now: Instant = Instant.now()) {
        val run = active ?: return
        val events = run.getJSONArray("events")
        if (events.length() >= MAX_EVENTS) {
            events.remove(0)
            run.put("discardedEvents", run.optInt("discardedEvents") + 1)
        }
        events.put(JSONObject().put("atUtc", now.toString()).put("event", kind).put("details", details))
        run.put("lastObservedUtc", now.toString())
        persist()
    }

    @Synchronized
    internal fun sampleStopped(reason: String) {
        active?.put("sampleStopReason", reason) ?: return
        record("sample-stopped", JSONObject().put("reason", reason))
        publish()
    }

    @Synchronized
    internal fun finish(reason: String, now: Instant = Instant.now()) {
        val run = active ?: return
        run.put("endUtc", now.toString()).put("endReason", reason)
        persist()
        publish()
    }

    /** A safety stop can end a sample before the listener has time to describe what they heard. */
    @Synchronized
    fun heard(runId: Int, phase: CarLabPhase, result: CarLabHeard) {
        val run = last?.takeIf { it.getInt("runId") == runId } ?: return
        val rows = run.optJSONArray("observations") ?: JSONArray().also { run.put("observations", it) }
        if (rows.length() >= MAX_EVENTS) rows.remove(0)
        rows.put(JSONObject().put("atUtc", Instant.now().toString()).put("phase", phase.name).put("heard", result.name))
        persist()
    }

    @Synchronized
    fun clear() {
        while (runs.length() > 0) runs.remove(runs.length() - 1)
        prefs.edit { clear().putInt("next", next) }
        publish()
    }

    @Synchronized
    fun attach(report: String): String {
        if (runs.length() == 0) return report
        val root = JSONObject(report)
        val intervals = (0 until runs.length()).map { index ->
            val row = runs.getJSONObject(index)
            Triple(
                Instant.parse(row.getString("startUtc")),
                row.optString("endUtc").takeIf(String::isNotEmpty)
                    ?.let(Instant::parse),
                row,
            )
        }.asReversed()
        for (key in listOf("timeline", "firstIncident", "problems")) {
            val rows = root.optJSONArray(key) ?: continue
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val at = Instant.parse(row.getString("atUtc"))
                val owner = intervals.firstOrNull { (start, end, _) -> at >= start && (end == null || at < end) }?.third
                if (owner != null) row.put("runId", owner.getInt("runId")).put("caseId", owner.getString("caseId"))
            }
        }
        root.put(
            "playbackTestSuite",
            JSONObject().put("version", 1).put("runs", runs)
                .put("activeRunId", active?.getInt("runId") ?: JSONObject.NULL),
        )
        return root.toString(2)
    }

    private fun publish() {
        val row = last
        mutable.value = if (row == null) {
            CarLabStatus()
        } else {
            CarLabStatus(
                row.getInt("runId"),
                active?.let { CarLabCase.valueOf(it.getString("caseId")) },
                row.optString("endReason", row.optString("sampleStopReason", "running")),
            )
        }
    }

    private fun persist() {
        val row = last ?: return
        val id = row.getInt("runId")
        prefs.edit {
            putString("run-$id", row.toString())
            putInt("next", next)
            remove("run-" + (id - MAX_RUNS))
        }
    }

    companion object {
        private const val MAX_RUNS = 48
        private const val MAX_EVENTS = 160
        private var instance: CarLabJournal? = null

        @Synchronized
        fun get(context: Context): CarLabJournal = instance ?: CarLabJournal(context.applicationContext)
            .also { instance = it }
    }
}

enum class CarLabPhase { Connect, Disconnect }
enum class CarLabHeard { Continuous, Pause, Skip, Silence, WrongOutput }
