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
 * The opt-in survives a diagnostic restart, allowing the AudioTrack provider to
 * be installed BEFORE the next PlaybackService creates its ExoPlayer.
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
        // Synchronous persistence is required before a deliberate process restart.
        preferences.edit(commit = true) { putBoolean("active", true) }
        activate()
    }

    fun stop() {
        if (clearingState.value) return
        stopRecorders()
    }

    private fun stopRecorders() {
        preferences.edit(commit = true) { putBoolean("active", false) }
        classifier.stop()
        audioRouteLab.stop()
        eventLog.stopRaceCapture()
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
            stopRecorders()
            classifier.clear()
            audioRouteLab.clearAndAwait()
            eventLog.clearRaceCapture()
            eventLog.clear()
            metrics.clear()
        }
    }

    private fun activate() {
        eventLog.startRaceCapture()
        audioRouteLab.start()
        classifier.start()
        activeState.value = true
    }
}
