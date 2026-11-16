package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.time.AppClock
import kotlin.time.Duration

/**
 * Debug-only automatic take-back through the public system media router.
 *
 * The headset's route is identified by what Android had selected while BookWave measured its own track on
 * that headset, never by name. A request is made only for that remembered route, only while it is listed and
 * not selected, and only in normal audio mode. The transfer moves all media on the phone, not only
 * BookWave's. Route ids and names stay in memory; the log carries fixed outcomes, a count and a duration.
 */
internal class SystemRouteHeadsetTakeBack(
    private val port: SystemRoutePort,
    private val clock: AppClock,
    private val log: (String, List<LogField>) -> Unit,
) : HeadsetTakeBack {
    private var opened = false
    private var verified = false
    private var headsetRoute: String? = null
    private var pending: Pair<String, Duration>? = null

    override fun onHeadsetVerified() {
        verified = true
        if (open()) remember()
    }

    override fun onHeadsetDisplaced() {
        verified = false
        val outcome = when {
            !open() -> port.availability().name
            port.audioMode() != AudioManager.MODE_NORMAL -> "CallBlocked"
            else -> request()
        }
        report(outcome, elapsed = Duration.ZERO)
    }

    override fun close() {
        if (opened) port.close()
        opened = false
        verified = false
        headsetRoute = null
        pending = null
    }

    /** True once the router is being observed; system routes need both Nearby devices permissions. */
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

    /** Route discovery is asynchronous; the first list may arrive after playback was already verified. */
    private fun onRoutesChanged() {
        if (verified && headsetRoute == null) remember()
    }

    private fun remember() {
        val selected = bluetoothRoutes().filter { it.selected }
        if (selected.size == 1) headsetRoute = selected.single().id
    }

    private fun request(): String {
        val target = bluetoothRoutes().firstOrNull { it.id == headsetRoute }
        return when {
            headsetRoute == null -> "HeadsetRouteUnknown"

            target == null -> "HeadsetRouteAbsent"

            target.selected -> "AlreadySelected"

            else -> try {
                pending = target.id to clock.elapsed()
                port.transfer(target.id)
                "Requested"
            } catch (_: SecurityException) {
                pending = null
                "PermissionRequired"
            } catch (_: IllegalStateException) {
                pending = null
                "Unavailable"
            } catch (_: IllegalArgumentException) {
                pending = null
                "Unavailable"
            }
        }
    }

    /** The port reports every selection change; only the answer to this request is an outcome. */
    private fun onResult(id: String, status: SystemRouteStatus) {
        val request = pending?.takeIf { it.first == id } ?: return
        pending = null
        report(status.name, clock.elapsed() - request.second)
    }

    private fun bluetoothRoutes() = port.routes().filter { it.system && it.bluetooth }

    private fun report(outcome: String, elapsed: Duration) = log(
        "Headset take-back experiment",
        listOf(
            LogField.Public("outcome", outcome),
            LogField.Count("bluetoothRoutes", if (opened) bluetoothRoutes().size else 0),
            LogField.Millis("elapsed", elapsed.inWholeMilliseconds.coerceAtLeast(0)),
        ),
    )
}
