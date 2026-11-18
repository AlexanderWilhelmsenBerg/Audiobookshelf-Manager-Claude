package com.example.shelfplayer.playback

import android.content.Context
import androidx.core.content.edit
import com.example.shelfplayer.core.common.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single opt-in entry point for the existing independent diagnostic recorders.
 * Owns no player, changes no routing, and never silently starts recording.
 * Opt-in survives process recreation. The current player already has its route probe.
 */
@Singleton
class UnifiedDiagnosticsCapture @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val audioRouteLab: AudioRouteLab,
    private val classifier: OutputClassifier,
    private val eventLog: EventLog,
    private val metrics: PlaybackMetricsRecorder,
) {
    private val preferences = context.getSharedPreferences("unified-diagnostics", Context.MODE_PRIVATE)
    private val activeState = MutableStateFlow(false)
    private val clearingState = MutableStateFlow(false)
    val active: StateFlow<Boolean> = activeState.asStateFlow()
    val clearing: StateFlow<Boolean> = clearingState.asStateFlow()

    fun restoreOptInAfterProcessRestart() {
        if (preferences.getBoolean("active", false)) activate()
    }

    fun start() {
        if (clearingState.value) return
        preferences.edit { putBoolean("active", true) }
        activate()
    }

    fun stop() {
        if (clearingState.value) return
        stopRecorders()
    }

    private fun stopRecorders() {
        preferences.edit { putBoolean("active", false) }
        classifier.stop()
        audioRouteLab.stop()
        activeState.value = false
    }

    /** Reserve the device-wide clear operation so other screens cannot restart capture mid-delete. */
    fun beginClear(): Boolean = clearingState.compareAndSet(expect = false, update = true)

    /** Release the busy state after all app- and playback-owned diagnostics have been cleared. */
    fun finishClear() {
        clearingState.value = false
    }

    /** Unified Clear all waits until the only durable route trace has actually been removed. */
    suspend fun clearAndAwait() {
        withContext(NonCancellable) {
            classifier.clear()
            eventLog.clear()
            metrics.clear()
            audioRouteLab.clearAndAwait()
        }
    }

    private fun activate() {
        audioRouteLab.start()
        classifier.start()
        activeState.value = true
    }
}
