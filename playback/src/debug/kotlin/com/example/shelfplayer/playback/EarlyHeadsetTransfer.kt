package com.example.shelfplayer.playback

import android.media.AudioManager

/** Observes one car link. A router selection, a playing AudioTrack and an A2DP stream are separate evidence. */
internal class EarlyHeadsetTransfer(
    private val arrival: EarlyHeadsetArrival,
    private val route: String,
    private val port: SystemRoutePort,
    private val report: (String) -> Unit,
) {
    private var closed = false
    private var offered = false
    private var requested = false
    private var resultObserved = false
    private var trackLost = false
    private var streamStopped = false
    private var awaitingStreamReturn = false

    private fun current() = !closed && arrival.scope.isCurrent() && arrival.isCurrent()

    fun update() {
        if (!current()) return
        try {
            if (port.availability() != SystemRouteStatus.Observing || port.audioMode() != AudioManager.MODE_NORMAL) {
                closed = true
                report("Ineligible")
                return
            }
            offer(routeIsTransferable())
        } catch (_: SecurityException) {
            unavailable()
        } catch (_: IllegalStateException) {
            unavailable()
        } catch (_: IllegalArgumentException) {
            unavailable()
        }
    }

    private fun routeIsTransferable(): Boolean {
        val target = port.routes().firstOrNull { it.id == route && it.system && it.bluetooth }
        return target != null && !target.selected && route in port.transferableRouteIds()
    }

    private fun offer(transferable: Boolean) {
        if (transferable && !offered) report("Transferable")
        offered = transferable
        if (!transferable || requested) return
        if (arrival.trackMoved()) trackLost()
        val displaced = streamStopped || trackLost
        if (displaced && current() && arrival.scope.claim()) {
            requested = true
            report("Requested")
            port.transfer(route)
        }
    }

    fun trackLost() {
        trackLost = true
        awaitingStreamReturn = true
    }

    fun trackVerified(endpoint: String) {
        if (endpoint != arrival.endpoint || !trackLost || !current()) return
        trackLost = false
        report("TrackReturned")
    }

    fun stream(playing: Boolean) {
        if (!current()) return
        val changed = if (playing) awaitingStreamReturn else !streamStopped
        if (!changed) return
        streamStopped = !playing
        awaitingStreamReturn = !playing
        report(if (playing) "StreamReturned" else "StreamStopped")
        if (!playing) update()
    }

    fun result(id: String, status: SystemRouteStatus) {
        if (!requested || resultObserved) return
        if (id == route && current()) {
            resultObserved = true
            report(status.name)
        }
    }

    fun cancel() {
        closed = true
    }

    private fun unavailable() {
        closed = true
        report("Unavailable")
    }
}
