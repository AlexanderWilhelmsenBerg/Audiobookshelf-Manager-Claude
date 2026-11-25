package com.example.shelfplayer.playback

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class CarLabSystemTraceTest {
    @Test
    fun eachRunCanTriggerOneIncidentAndOrdinaryPlaybackKeepsTheRecorderArmed() {
        val start = Instant.EPOCH
        val incidents = mutableListOf<String>()
        repeat(2) { run ->
            val trace = CarLabSystemTrace(run, CarLabCase.Book, start, {}, incidents::add)
            trace.playback("playback-intent playWhenReady=true reason=userRequest", start)
            trace.playback("track-progress event=NoFrameProgress frames=0", start)
            trace.playback("route-boundary edge=RouteSafetyPause positionMs=4500", start)
        }
        assertEquals(listOf("NoFrameProgress", "NoFrameProgress"), incidents)
    }

    @Test
    fun delayedAndUnknownEventsAreExcludedAndDetailsStayInThePrivateJournal() {
        val start = Instant.parse("2026-11-25T18:54:00Z")
        val markers = mutableListOf<String>()
        val trace = CarLabSystemTrace(1, CarLabCase.BookHold, start, markers::add)
        trace.playback("playback-intent reason=old-run", start.minusNanos(1))
        trace.playback("unrecognized sensitive data", start)
        trace.playback("route-boundary edge=RouteSafetyPause trackRoute=private", start)
        trace.playback("actual-track playing-to=private", start)
        assertEquals(listOf("BookWave/1/BookHold/RouteSafetyPause", "BookWave/1/BookHold/actual-track"), markers)
    }
}
