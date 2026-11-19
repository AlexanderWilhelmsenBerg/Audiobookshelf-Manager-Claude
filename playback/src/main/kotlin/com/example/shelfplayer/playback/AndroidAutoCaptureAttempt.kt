package com.example.shelfplayer.playback

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Session-local observations. Endpoint identity stays in memory and is never serialized. */
internal class AndroidAutoCaptureAttempt(
    val number: Long,
    val started: Instant,
    startedElapsed: Duration,
    private val source: String,
    private val headsetPresent: Boolean?,
    verifiedHeadset: String?,
) {
    private val startElapsed = startedElapsed
    private val headsetPlayingAtStart = verifiedHeadset != null
    var connected: Instant? = null
        private set
    private var headset = verifiedHeadset
    private var ended: Instant? = null
    private var ending: String? = null
    private val opening = mutableListOf<Event>()
    private val tail = ArrayDeque<Event>()
    private var dropped = 0
    private val reconnects = ArrayDeque<Reconnect>()
    private var reconnectNumber = 0
    private var pending: Reconnect? = null

    fun confirm(at: Instant, evidence: String) {
        if (connected != null) return
        connected = at
        event(at, "connected", evidence)
    }

    fun event(at: Instant, kind: String, details: String = "") {
        val row = Event(at.toString(), kind, details)
        if (opening.size < OPENING_ROWS) {
            opening.add(row)
        } else {
            if (tail.size == TAIL_ROWS) {
                tail.removeFirst()
                dropped++
            }
            tail.addLast(row)
        }
    }

    fun interrupted(at: Instant, elapsed: Duration, reason: String) {
        val expected = headset ?: return
        if (pending != null) return
        val reconnect = Reconnect(++reconnectNumber, at, elapsed, reason, expected)
        if (reconnects.size == MAX_RECONNECTS) reconnects.removeFirst()
        reconnects.addLast(reconnect)
        pending = reconnect
        event(at, "headset-interrupted", reason)
    }

    fun verified(at: Instant, elapsed: Duration, id: String): Boolean {
        val changed = headset != id || pending?.expected == id
        if (changed) event(at, "headset-playing")
        headset = id
        val reconnect = pending?.takeIf { it.expected == id } ?: return changed
        reconnect.finish(
            at,
            elapsed,
            if (reconnect.controllerPlay) "recovered-after-play" else "recovered-automatically",
        )
        pending = null
        return changed
    }

    fun transport(play: Boolean, at: Instant, elapsed: Duration) {
        if (!play) {
            pause(at, elapsed)
        } else {
            pending?.controllerPlay = true
        }
    }

    fun pause(at: Instant, elapsed: Duration, outcome: String = "listener-paused") {
        pending?.finish(at, elapsed, outcome)
        pending = null
        headset = null
    }

    fun end(at: Instant, elapsed: Duration, reason: String) {
        ended = at
        ending = reason
        val outcome = if (reason in CONNECTION_ENDS) "not-recovered" else "capture-ended"
        pending?.finish(at, elapsed, outcome)
        pending = null
        event(at, "ended", reason)
    }

    fun clear(at: Instant) {
        opening.clear()
        tail.clear()
        reconnects.clear()
        pending = null
        dropped = 0
        event(at, "cleared-during-connection")
    }

    fun snapshot(elapsed: Duration): JSONObject = JSONObject()
        .put("attempt", number)
        .put("startedAtUtc", started.toString())
        .put("startEvidence", source)
        .put("connectedAtUtc", connected?.toString() ?: JSONObject.NULL)
        .put("endedAtUtc", ended?.toString() ?: JSONObject.NULL)
        .put("endReason", ending ?: JSONObject.NULL)
        .put("headsetPresentAtConnect", headsetPresent ?: JSONObject.NULL)
        .put("headsetPlayingAtStart", headsetPlayingAtStart)
        .put("recoveryStatsVersion", RECOVERY_STATS_VERSION)
        .put("headsetReconnects", JSONArray(reconnects.map { it.json(elapsed, startElapsed) }))
        .put("droppedEvents", dropped)
        .put("events", JSONArray((opening + tail).map(Event::json)))

    private data class Event(val at: String, val kind: String, val details: String) {
        fun json(): JSONObject = JSONObject().put("atUtc", at).put("event", kind).put("details", details)
    }

    private class Reconnect(
        val number: Int,
        val at: Instant,
        val elapsed: Duration,
        val reason: String,
        val expected: String,
    ) {
        private var outcome: String? = null
        private var end: Instant? = null
        private var duration: Long? = null
        var controllerPlay = false

        fun finish(at: Instant, now: Duration, result: String) {
            outcome = result
            end = at
            duration = (now - elapsed).inWholeMilliseconds.coerceAtLeast(0)
        }

        fun json(now: Duration, start: Duration): JSONObject {
            val status = outcome ?: if (now - elapsed >= RECOVERY_OBSERVATION_LIMIT) "not-recovered" else "pending"
            return JSONObject().put("reconnect", number).put("startedAtUtc", at.toString())
                .put("startOffsetMs", (elapsed - start).inWholeMilliseconds.coerceAtLeast(0))
                .put("cause", reason).put("outcome", status)
                .put("endedAtUtc", end?.toString() ?: JSONObject.NULL)
                .put("elapsedMs", duration ?: JSONObject.NULL)
        }
    }

    companion object {
        const val RECOVERY_STATS_VERSION = 2
        private const val OPENING_ROWS = 96
        private const val TAIL_ROWS = 416
        private const val MAX_RECONNECTS = 64
        private val RECOVERY_OBSERVATION_LIMIT = 30.seconds
        private val CONNECTION_ENDS = setOf("projection-disconnect", "final-car-disconnect", "connection-unconfirmed")
    }
}
