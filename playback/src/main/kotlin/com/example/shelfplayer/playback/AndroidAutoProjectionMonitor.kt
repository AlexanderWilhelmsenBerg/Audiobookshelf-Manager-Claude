package com.example.shelfplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #36 — observes the physical Android Auto/AAOS connection independently of MediaSession controllers.
 *
 * A legacy MediaSession controller can remain connected long after projection has ended, so controller
 * lifetime is not a physical car lifecycle boundary. AndroidX Car App's public connection contract uses the
 * update action/state column below and queries the Android Auto projection provider after each update. BookWave
 * needs only that narrow signal, not the Car App templating/runtime stack, so this adapter performs the same
 * read without introducing another UI framework into the playback module.
 *
 * Unknown is intentionally distinct from NotConnected. A missing/inaccessible provider must never manufacture
 * a departure boundary that could authorize Play.
 *
 * Physical acceptance found that an Unknown result was otherwise completely silent. Every registration,
 * broadcast and provider read is therefore recorded in the AndroidAuto event-log category. Failures remain
 * conservative Unknown states, but now say why.
 */
@Singleton
internal class AndroidAutoProjectionMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
    private val clock: AppClock,
) {
    internal enum class State(val carConnected: Boolean) {
        Unknown(false),
        NotConnected(false),
        Native(true),
        Projection(true),
    }

    internal data class Update(val previous: State?, val current: State) {
        val initial: Boolean get() = previous == null
    }

    internal data class ReadResult(
        val state: State,
        val raw: Int?,
        val reason: String,
        val providerVisible: Boolean,
        val failureClass: String? = null,
    )

    private var scope: CoroutineScope? = null
    private var callback: ((Update) -> Unit)? = null
    private var refreshJob: Job? = null
    private var lastState: State? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CAR_CONNECTION_UPDATED) return
            logger.debug(
                LogCategory.AndroidAuto,
                "Android Auto projection update broadcast received",
                LogField.Public("action", "car-connection-updated"),
                LogField.Public("receiverRegistered", registered),
            )
            refresh(trigger = "broadcast")
        }
    }

    /** Starts one process-local observer. The service scope owns every asynchronous provider read. */
    fun start(scope: CoroutineScope, onUpdate: (Update) -> Unit) {
        if (registered) {
            logger.debug(LogCategory.AndroidAuto, "Android Auto projection monitor start was already active")
            return
        }
        this.scope = scope
        callback = onUpdate

        val receiverFailure: Throwable? = try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            null
        } catch (error: SecurityException) {
            error
        } catch (error: IllegalArgumentException) {
            error
        }

        logger.info(
            LogCategory.AndroidAuto,
            "Android Auto projection monitor started",
            LogField.Public("api", android.os.Build.VERSION.SDK_INT),
            LogField.Public(
                "automotive",
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            ),
            LogField.Public("providerVisible", providerVisible()),
            LogField.Public("receiverRegistered", registered),
        )
        if (receiverFailure != null) {
            logger.warn(
                LogCategory.AndroidAuto,
                "Android Auto projection receiver registration failed",
                LogField.Public("failure", receiverFailure.javaClass.simpleName),
            )
        }
        refresh(trigger = "initial")
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        logger.info(
            LogCategory.AndroidAuto,
            "Android Auto projection monitor stopped",
            LogField.Public("lastState", lastState?.name ?: "none"),
            LogField.Public("receiverRegistered", registered),
        )
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (error: IllegalArgumentException) {
                logger.warn(
                    LogCategory.AndroidAuto,
                    "Android Auto projection receiver unregister failed",
                    LogField.Public("failure", error.javaClass.simpleName),
                )
            }
            registered = false
        }
        callback = null
        scope = null
        lastState = null
    }

    private fun refresh(trigger: String) {
        val owner = scope ?: return
        if (refreshJob?.isActive == true) {
            logger.debug(
                LogCategory.AndroidAuto,
                "Android Auto projection read was superseded",
                LogField.Public("trigger", trigger),
            )
        }
        refreshJob?.cancel()
        refreshJob = owner.launch {
            val startedAt = clock.elapsed()
            val result = withContext(ioDispatcher) { readState() }
            val elapsed = (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L)
            logger.info(
                LogCategory.AndroidAuto,
                "Android Auto projection state read",
                *buildList {
                    add(LogField.Public("trigger", trigger))
                    add(LogField.Public("state", result.state.name))
                    result.raw?.let { add(LogField.Public("raw", it)) }
                    add(LogField.Public("reason", result.reason))
                    add(LogField.Public("providerVisible", result.providerVisible))
                    result.failureClass?.let { add(LogField.Public("failure", it)) }
                    add(LogField.Millis("elapsed", elapsed))
                }.toTypedArray(),
            )

            val previous = lastState
            if (result.state != previous) {
                lastState = result.state
                callback?.invoke(Update(previous = previous, current = result.state))
            }
        }
    }

    private fun readState(): ReadResult {
        val visible = providerVisible()
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return ReadResult(
                state = State.Native,
                raw = null,
                reason = "automotive-feature",
                providerVisible = visible,
            )
        }

        return try {
            context.contentResolver.query(
                projectionHostUri(),
                arrayOf(CAR_CONNECTION_STATE),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(CAR_CONNECTION_STATE)
                when {
                    column < 0 -> ReadResult(State.Unknown, null, "missing-column", visible)
                    !cursor.moveToFirst() -> ReadResult(State.Unknown, null, "empty-cursor", visible)
                    else -> {
                        val raw = cursor.getInt(column)
                        ReadResult(
                            state = stateOf(raw),
                            raw = raw,
                            reason = if (raw in 0..2) "provider-value" else "invalid-value",
                            providerVisible = visible,
                        )
                    }
                }
            } ?: ReadResult(State.Unknown, null, "null-cursor", visible)
        } catch (error: SecurityException) {
            ReadResult(State.Unknown, null, "security-exception", visible, error.javaClass.simpleName)
        } catch (error: IllegalArgumentException) {
            ReadResult(State.Unknown, null, "illegal-argument", visible, error.javaClass.simpleName)
        }
    }

    private fun providerVisible(): Boolean =
        context.packageManager.resolveContentProvider(CAR_CONNECTION_AUTHORITY, PackageManager.MATCH_ALL) != null

    internal companion object {
        // Public CarConnection contract constants. The provider authority is the projection host endpoint
        // used by that contract; keep it isolated here so no playback policy depends on provider details.
        const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        const val CAR_CONNECTION_STATE = "CarConnectionState"
        internal const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"

        private fun projectionHostUri(): Uri =
            Uri.Builder().scheme("content").authority(CAR_CONNECTION_AUTHORITY).build()

        fun stateOf(raw: Int): State = when (raw) {
            0 -> State.NotConnected
            1 -> State.Native
            2 -> State.Projection
            else -> State.Unknown
        }
    }
}
