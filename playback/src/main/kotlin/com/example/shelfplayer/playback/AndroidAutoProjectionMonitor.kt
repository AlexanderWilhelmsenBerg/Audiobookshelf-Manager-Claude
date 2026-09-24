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
 */
@Singleton
internal class AndroidAutoProjectionMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
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

    private var scope: CoroutineScope? = null
    private var callback: ((Update) -> Unit)? = null
    private var refreshJob: Job? = null
    private var lastState: State? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAR_CONNECTION_UPDATED) refresh()
        }
    }

    /** Starts one process-local observer. The service scope owns every asynchronous provider read. */
    fun start(scope: CoroutineScope, onUpdate: (Update) -> Unit) {
        if (registered) return
        this.scope = scope
        callback = onUpdate
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
        refresh()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
        callback = null
        scope = null
        lastState = null
    }

    private fun refresh() {
        val owner = scope ?: return
        refreshJob?.cancel()
        refreshJob = owner.launch {
            val next = withContext(ioDispatcher) { readState() }
            val previous = lastState
            if (next != previous) {
                lastState = next
                callback?.invoke(Update(previous = previous, current = next))
            }
        }
    }

    private fun readState(): State {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return State.Native
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
                if (column < 0 || !cursor.moveToFirst()) State.Unknown else stateOf(cursor.getInt(column))
            } ?: State.Unknown
        } catch (_: SecurityException) {
            State.Unknown
        } catch (_: IllegalArgumentException) {
            State.Unknown
        }
    }

    internal companion object {
        // Public CarConnection contract constants. The provider authority is the projection host endpoint
        // used by that contract; keep it isolated here so no playback policy depends on provider details.
        const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        const val CAR_CONNECTION_STATE = "CarConnectionState"
        private const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"

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
