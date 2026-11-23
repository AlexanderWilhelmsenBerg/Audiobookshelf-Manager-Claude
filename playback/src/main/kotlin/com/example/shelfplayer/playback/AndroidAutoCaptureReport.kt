package com.example.shelfplayer.playback

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/** The compact indexes let readers select relevant attempts before opening their event lists. */
internal object AndroidAutoCaptureReport {
    fun format(build: String, androidApi: Int, at: Instant, attempts: List<JSONObject>): String {
        val exportedAttempts = attempts.map(::correctEarlyCutoffs)
        val currentEvidence = exportedAttempts.filter(::usesCurrentRecoveryEvidence)
        val reconnects = currentEvidence.flatMap(::reconnects)
        return JSONObject()
            .put("schema", "bookwave.android-auto-diagnostics")
            .put("schemaVersion", 1)
            .put("build", build)
            .put("androidApi", androidApi)
            .put("generatedAtUtc", at.toString())
            .put("attemptIndex", JSONArray(exportedAttempts.map(::index)))
            .put("legacyAttemptsExcludedFromRecoveryStats", attempts.size - currentEvidence.size)
            .put("failedHeadsetReconnects", JSONArray(reconnects.filter(::failedAutomaticRecovery)))
            .put(
                "headsetRecoveryMs",
                JSONObject()
                    .put("automatic", stats(reconnects, "recovered-automatically"))
                    .put("afterPlay", stats(reconnects, "recovered-after-play")),
            )
            .put("attempts", JSONArray(exportedAttempts))
            .toString()
    }

    /** Older v2 rows used the attempt's closing timestamp as a failure even before the deadline. */
    private fun correctEarlyCutoffs(attempt: JSONObject): JSONObject {
        if (!usesCurrentRecoveryEvidence(attempt) || attempt.isNull("endedAtUtc")) return attempt
        val endedAt = attempt.getString("endedAtUtc")
        val reason = attempt.optString("endReason")
        val rows = attempt.getJSONArray("headsetReconnects")
        val corrected = (0 until rows.length()).filter { index ->
            val row = rows.getJSONObject(index)
            val observedMs = row.optLong("elapsedMs", -1L)
            row.optString("outcome") == "not-recovered" && row.optString("endedAtUtc") == endedAt &&
                observedMs >= 0 && AndroidAutoCaptureAttempt.recoveryOutcomeAtEnd(reason, observedMs.milliseconds) ==
                "unverified"
        }
        if (corrected.isEmpty()) return attempt
        val exported = JSONObject(attempt.toString())
        val exportedRows = exported.getJSONArray("headsetReconnects")
        corrected.forEach { index -> exportedRows.getJSONObject(index).put("outcome", "unverified") }
        return exported
    }

    private fun index(attempt: JSONObject): JSONObject = JSONObject().apply {
        for (field in INDEX_FIELDS) put(field, attempt.opt(field) ?: JSONObject.NULL)
        val reconnects = reconnects(attempt)
        put("recoveryStatsVersion", attempt.optInt("recoveryStatsVersion", 1))
        put("headsetReconnectCount", reconnects.size)
        put(
            "failedHeadsetReconnectCount",
            if (usesCurrentRecoveryEvidence(attempt)) reconnects.count(::failedAutomaticRecovery) else JSONObject.NULL,
        )
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

    private fun usesCurrentRecoveryEvidence(attempt: JSONObject): Boolean =
        attempt.optInt("recoveryStatsVersion") == AndroidAutoCaptureAttempt.RECOVERY_STATS_VERSION

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
