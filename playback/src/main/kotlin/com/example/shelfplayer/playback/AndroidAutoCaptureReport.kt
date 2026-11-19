package com.example.shelfplayer.playback

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The compact indexes let readers select relevant attempts before opening their event lists. */
internal object AndroidAutoCaptureReport {
    fun format(build: String, androidApi: Int, at: Instant, attempts: List<JSONObject>): String {
        val reconnects = attempts.flatMap(::reconnects)
        return JSONObject()
            .put("schema", "bookwave.android-auto-diagnostics")
            .put("schemaVersion", 1)
            .put("build", build)
            .put("androidApi", androidApi)
            .put("generatedAtUtc", at.toString())
            .put("attemptIndex", JSONArray(attempts.map(::index)))
            .put("failedHeadsetReconnects", JSONArray(reconnects.filter(::failedAutomaticRecovery)))
            .put(
                "headsetRecoveryMs",
                JSONObject()
                    .put("automatic", stats(reconnects, "recovered-automatically"))
                    .put("afterPlay", stats(reconnects, "recovered-after-play")),
            )
            .put("attempts", JSONArray(attempts))
            .toString()
    }

    private fun index(attempt: JSONObject): JSONObject = JSONObject().apply {
        for (field in INDEX_FIELDS) put(field, attempt.opt(field) ?: JSONObject.NULL)
        val reconnects = reconnects(attempt)
        put("headsetReconnectCount", reconnects.size)
        put("failedHeadsetReconnectCount", reconnects.count(::failedAutomaticRecovery))
    }

    private fun reconnects(attempt: JSONObject): List<JSONObject> {
        val rows = attempt.getJSONArray("headsetReconnects")
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            JSONObject()
                .put("attempt", attempt.getLong("attempt"))
                .put("reconnect", row.getInt("reconnect"))
                .put("startedAtUtc", row.getString("startedAtUtc"))
                .put("cause", row.getString("cause"))
                .put("outcome", row.getString("outcome"))
                .put("elapsedMs", row.opt("elapsedMs") ?: JSONObject.NULL)
        }
    }

    private fun failedAutomaticRecovery(row: JSONObject): Boolean = row.getString("outcome") in FAILED_AUTOMATIC

    private fun stats(reconnects: List<JSONObject>, outcome: String): JSONObject {
        val times = reconnects.filter { it.getString("outcome") == outcome }.map { it.getLong("elapsedMs") }
        return JSONObject().put("count", times.size)
            .put("avgMs", if (times.isEmpty()) JSONObject.NULL else times.average())
            .put("minMs", times.minOrNull() ?: JSONObject.NULL)
            .put("maxMs", times.maxOrNull() ?: JSONObject.NULL)
    }

    private val FAILED_AUTOMATIC = setOf("not-recovered", "recovered-after-play")
    private val INDEX_FIELDS = listOf(
        "attempt",
        "startedAtUtc",
        "connectedAtUtc",
        "endedAtUtc",
        "endReason",
        "headsetPresentAtConnect",
        "headsetPlayingAtStart",
        "droppedEvents",
    )
}
