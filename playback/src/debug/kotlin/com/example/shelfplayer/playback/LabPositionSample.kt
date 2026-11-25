package com.example.shelfplayer.playback

import org.json.JSONObject
import kotlin.math.abs

internal data class LabPositionSample(val at: Long, val position: Long?, val playing: Boolean, val route: String) {
    fun changedSince(previous: LabPositionSample?): Boolean {
        if (previous == null || route != previous.route || playing != previous.playing) return true
        if (!playing || previous.position == null || position == null) return false
        val expected = at - previous.at
        return abs(position - previous.position - expected) > CLOCK_TOLERANCE_MS
    }

    fun json(): JSONObject = JSONObject().put("playing", playing).put("route", route)
        .put("elapsedRealtimeMs", at).put("positionMs", position ?: JSONObject.NULL)

    companion object {
        private const val CLOCK_TOLERANCE_MS = 180L
    }
}
