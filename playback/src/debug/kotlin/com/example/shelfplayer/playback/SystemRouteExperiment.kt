package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlin.time.Duration

enum class SystemRouteStatus {
    Closed, Unsupported, PermissionRequired, Observing, Requested, Selected, Failed, TimedOut,
    Unavailable, CallBlocked,
}

data class SystemRouteRow(val alias: String, val displayName: String, val selected: Boolean)

data class SystemRouteExperimentState(
    val status: SystemRouteStatus = SystemRouteStatus.Closed,
    val routes: List<SystemRouteRow> = emptyList(),
    val attempted: Boolean = false,
)

internal data class SystemRouteCandidate(
    val id: String,
    val name: String,
    val bluetooth: Boolean,
    val system: Boolean,
    val selected: Boolean,
)

internal interface SystemRoutePort {
    fun availability(): SystemRouteStatus
    fun audioMode(): Int
    fun routes(): List<SystemRouteCandidate>
    fun open(changed: () -> Unit, result: (String, SystemRouteStatus) -> Unit)
    fun transfer(id: String)
    fun close()
}

/** PLAY-002 / ROUTE-001: debug-only manual system transfer, never a playback or focus owner.
 * All calls and callbacks run on the main thread. IDs/names remain in memory and never enter logs.
 */
class SystemRouteExperiment internal constructor(
    private val port: SystemRoutePort?,
    private val clock: AppClock,
    private val emit: (SystemRouteStatus, Int, Long) -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        clock: AppClock,
        logger: Logger,
    ) : this(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) PlatformSystemRoutePort(context) else null,
        clock,
        { status, alias, elapsed ->
            logger.info(
                LogCategory.Playback,
                "Manual system route experiment",
                LogField.Public("status", status.name),
                LogField.Count("routeAlias", alias),
                LogField.Millis("elapsedMs", elapsed),
            )
        },
    )

    private val mutableState = MutableStateFlow(
        SystemRouteExperimentState(
            status = if (port == null) SystemRouteStatus.Unsupported else SystemRouteStatus.Closed,
        ),
    )
    val state = mutableState.asStateFlow()
    private val aliases = linkedMapOf<String, Int>()
    private var generation = 0
    private var observing = false
    private var pending: Pair<String, Duration>? = null

    fun open() {
        if (observing) return
        val available = port?.availability() ?: SystemRouteStatus.Unsupported
        publish(available)
        if (available != SystemRouteStatus.Observing) return
        val session = ++generation
        observing = true
        try {
            requireNotNull(port).open(
                changed = { if (generation == session && observing) refresh() },
                result = { id, status -> if (generation == session && observing) complete(id, status) },
            )
            refresh()
        } catch (_: SecurityException) {
            close()
            publish(SystemRouteStatus.PermissionRequired)
        } catch (_: IllegalStateException) {
            close()
            publish(SystemRouteStatus.Unavailable)
        }
    }

    fun transfer(alias: String) {
        if (!observing || state.value.attempted) return
        val available = requireNotNull(port).availability()
        if (available != SystemRouteStatus.Observing) {
            close()
            publish(available)
            return
        }
        try {
            if (port.audioMode() != AudioManager.MODE_NORMAL) {
                publish(SystemRouteStatus.CallBlocked)
                return
            }
            // Re-read inventory at the click. Never resolve a stale row by a similar name.
            val target = port.routes().firstOrNull {
                "r${aliases[it.id]}" == alias && it.system && it.bluetooth && !it.selected
            } ?: return
            pending = target.id to clock.elapsed()
            mutableState.value = state.value.copy(status = SystemRouteStatus.Requested, attempted = true)
            emit(SystemRouteStatus.Requested, aliases.getValue(target.id), 0)
            port.transfer(target.id)
        } catch (_: SecurityException) {
            fail(SystemRouteStatus.PermissionRequired)
        } catch (_: IllegalStateException) {
            fail(SystemRouteStatus.Unavailable)
        } catch (_: IllegalArgumentException) {
            fail(SystemRouteStatus.Unavailable)
        }
    }

    fun close() {
        generation++
        observing = false
        pending = null
        port?.close()
        aliases.clear()
        mutableState.value = SystemRouteExperimentState()
    }

    private fun refresh() {
        try {
            val rows = requireNotNull(port).routes().filter { it.system && it.bluetooth }.map {
                val alias = aliases.getOrPut(it.id) { aliases.size + 1 }
                SystemRouteRow("r$alias", it.name, it.selected)
            }
            if (rows != state.value.routes) {
                rows.forEach { emit(SystemRouteStatus.Observing, it.alias.drop(1).toInt(), 0) }
            }
            mutableState.value = state.value.copy(routes = rows)
        } catch (_: SecurityException) {
            close()
            publish(SystemRouteStatus.PermissionRequired)
        } catch (_: IllegalStateException) {
            close()
            publish(SystemRouteStatus.Unavailable)
        }
    }

    private fun complete(id: String, status: SystemRouteStatus) {
        val request = pending ?: return
        if (request.first != id) return
        pending = null
        emit(status, aliases.getValue(id), (clock.elapsed() - request.second).inWholeMilliseconds.coerceAtLeast(0))
        publish(status)
    }

    private fun fail(status: SystemRouteStatus) {
        pending?.let { complete(it.first, status) } ?: publish(status)
    }

    private fun publish(status: SystemRouteStatus) {
        mutableState.value = state.value.copy(status = status)
        if (status != SystemRouteStatus.Selected && status != SystemRouteStatus.TimedOut &&
            status != SystemRouteStatus.Failed
        ) emit(status, 0, 0)
    }
}
