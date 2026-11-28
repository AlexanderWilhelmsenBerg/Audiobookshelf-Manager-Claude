package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock
import kotlin.time.Duration

/**
 * Debug-only system media transfer. The car window owns eligibility and the one-transfer allowance;
 * this adapter only waits for a transferable remembered route or observes its requested transfer.
 * Actual-track verification and playback/focus remain with the existing service owners.
 */
internal class SystemRouteHeadsetTakeBack(
    private val port: SystemRoutePort,
    private val clock: AppClock,
    private val enabled: () -> Boolean = { true },
    private val earlyEnabled: () -> Boolean = { false },
    private val log: (String, List<LogField>) -> Unit,
) : HeadsetTakeBack {
    override val automaticTransferEnabled: Boolean get() = enabled()
    override val availableForTrial: Boolean get() = port.availability() == SystemRouteStatus.Observing
    private data class Headset(val endpoint: String, val route: String)
    private data class Verified(val endpoint: String, val mayRemember: () -> Boolean)
    private class Attempt(
        val route: String,
        val scope: CarBluetoothArrivalWindow.TakeBackScope,
        private val contextCurrent: () -> Boolean,
    ) {
        var requestedAt: Duration? = null
        var outcome: String? = null

        fun isCurrent(): Boolean = scope.isCurrent() && contextCurrent()
    }

    private var opened = false
    private var verified: Verified? = null
    private var headset: Headset? = null
    private var attempt: Attempt? = null
    private var early: EarlyHeadsetTransfer? = null
    private val aliases = mutableMapOf<String, Int>()

    override fun onHeadsetVerified(endpointId: String, mayRemember: () -> Boolean) {
        if (headset?.endpoint != endpointId) {
            headset = null
            attempt = null
        }
        verified = Verified(endpointId, mayRemember)
        // Keep an issued request's correlation if playback returns before the selection callback.
        if (attempt?.requestedAt == null) attempt = null
        if (open()) remember()
        early?.trackVerified(endpointId)
    }

    override fun armEarly(arrival: EarlyHeadsetArrival) {
        early?.cancel()
        early = null
        if (!earlyEnabled() || !arrival.isCurrent() || !arrival.scope.isCurrent()) return
        val started = clock.elapsed()
        val emit: (String) -> Unit = { outcome ->
            log(
                "Headset take-back experiment",
                listOf(
                    LogField.Public("stage", "Early"),
                    LogField.Public("outcome", outcome),
                    LogField.Millis("elapsed", (clock.elapsed() - started).inWholeMilliseconds),
                ),
            )
        }
        val target = headset?.takeIf { it.endpoint == arrival.endpoint }
        if (target == null || !open()) {
            emit("HeadsetRouteUnknown")
            return
        }
        early = EarlyHeadsetTransfer(arrival, target.route, port, emit)
        emit("Armed")
        early?.update()
    }

    override fun onHeadsetStream(playing: Boolean) {
        early?.stream(playing)
    }

    override fun earlyTrackLost() {
        early?.trackLost()
    }

    override fun onEarlyTrackRouteChanged() {
        early?.update()
    }

    override fun onHeadsetDisplaced(
        endpointId: String,
        scope: CarBluetoothArrivalWindow.TakeBackScope,
        isCurrent: () -> Boolean,
    ) {
        verified = null
        if (earlyEnabled()) {
            early?.trackLost()
            early?.update()
            return
        }
        if (!scope.isCurrent() || !isCurrent()) return
        if (!open()) {
            val status = port.availability()
            report(if (status == SystemRouteStatus.Observing) "Unavailable" else status.name)
            return
        }
        val target = headset?.takeIf { it.endpoint == endpointId } ?: return report("HeadsetRouteUnknown")
        attempt = attempt?.takeIf(Attempt::isCurrent) ?: Attempt(target.route, scope, isCurrent)
        advance()
    }

    override fun cancelPending() {
        early?.cancel()
        early = null
        attempt = null
        verified = null
    }

    override fun close() {
        cancelPending()
        if (opened) port.close()
        opened = false
        headset = null
        aliases.clear()
    }

    private fun open(): Boolean {
        if (opened) return true
        if (port.availability() != SystemRouteStatus.Observing) return false
        opened = try {
            port.open(changed = ::onRoutesChanged, result = ::onResult)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        return opened
    }

    private fun onRoutesChanged() {
        remember()
        early?.update()
        advance()
    }

    private fun remember() {
        val measured = verified ?: return
        // A per-track preference can coexist with a system-selected car. Never relearn the same
        // endpoint from that selection, or learn a new association during car arrival.
        if (headset != null || !measured.mayRemember()) return
        val selected = bluetoothRoutes().singleOrNull { it.selected } ?: return
        headset = Headset(measured.endpoint, selected.id)
    }

    private fun advance() {
        val active = attempt ?: return
        when {
            !enabled() -> finish(active, "Disabled")

            !active.isCurrent() -> finish(active, "Cancelled")

            port.availability() != SystemRouteStatus.Observing -> finish(active, "PermissionRequired")

            port.audioMode() != AudioManager.MODE_NORMAL -> finish(active, "CallBlocked")

            active.requestedAt != null -> Unit

            else -> {
                val target = bluetoothRoutes().firstOrNull { it.id == active.route }
                when {
                    target == null -> report("HeadsetRouteAbsent", active)
                    target.selected -> report("AlreadySelected", active)
                    else -> transfer(active)
                }
            }
        }
    }

    private fun transfer(active: Attempt) {
        // Reading platform routes can invoke callbacks. Recheck ownership before claiming.
        if (!owns(active) || !active.scope.claim()) {
            finish(active, "Cancelled")
            return
        }
        active.requestedAt = clock.elapsed()
        report("Requested", active)
        if (!owns(active)) return
        try {
            port.transfer(active.route)
        } catch (_: SecurityException) {
            finish(active, "PermissionRequired")
        } catch (_: IllegalStateException) {
            finish(active, "Unavailable")
        } catch (_: IllegalArgumentException) {
            finish(active, "Unavailable")
        }
    }

    private fun onResult(id: String, status: SystemRouteStatus) {
        early?.result(id, status)
        val active = attempt?.takeIf { it.route == id && it.requestedAt != null } ?: return
        finish(active, if (active.isCurrent()) status.name else "Cancelled")
    }

    private fun owns(active: Attempt): Boolean = attempt === active && active.isCurrent() && enabled()

    private fun finish(active: Attempt, outcome: String) {
        if (attempt !== active) return
        report(outcome, active)
        if (attempt === active) attempt = null
    }

    private fun bluetoothRoutes() = port.routes().filter { it.system && it.bluetooth }

    /** Bounded process-local aliases, separate from the manual lab's aliases. */
    private fun alias(id: String): Int = aliases[id] ?: if (aliases.size < MAX_ROUTE_ALIASES) {
        (aliases.size + 1).also { aliases[id] = it }
    } else {
        0
    }

    private fun report(outcome: String, active: Attempt? = null) {
        if (active != null && active.outcome == outcome) return
        active?.outcome = outcome
        val routes = if (opened) bluetoothRoutes() else emptyList()
        val selected = routes.filter { it.selected }
        val state = when {
            active == null -> "Idle"
            active.requestedAt == null -> "Waiting"
            else -> "Requested"
        }
        log(
            "Headset take-back experiment",
            listOf(
                LogField.Public("outcome", outcome),
                LogField.Public("state", state),
                LogField.Count("targetRouteAlias", (active?.route ?: headset?.route)?.let(::alias) ?: 0),
                LogField.Public(
                    "selectedRouteAliases",
                    selected.take(MAX_SELECTED_ALIASES).joinToString("_") {
                        alias(it.id).toString()
                    }.ifEmpty { "none" },
                ),
                LogField.Count("selectedRoutes", selected.size),
                LogField.Count("bluetoothRoutes", routes.size),
                LogField.Millis(
                    "elapsed",
                    active?.requestedAt?.let { (clock.elapsed() - it).inWholeMilliseconds.coerceAtLeast(0) } ?: 0,
                ),
            ),
        )
    }

    private companion object {
        const val MAX_ROUTE_ALIASES = 32
        const val MAX_SELECTED_ALIASES = 4
    }
}
