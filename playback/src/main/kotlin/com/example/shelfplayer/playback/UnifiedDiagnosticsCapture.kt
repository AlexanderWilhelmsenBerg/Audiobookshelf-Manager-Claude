package com.example.shelfplayer.playback

import android.content.Context
import androidx.core.content.edit
import com.example.shelfplayer.core.common.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val preferences = context.getSharedPreferences("unified-diagnostics", Context.MODE_PRIVATE)
    private val activeState = MutableStateFlow(false)
    val active: StateFlow<Boolean> = activeState.asStateFlow()

    fun restoreOptInAfterProcessRestart() {
        if (preferences.getBoolean("active", false)) activate()
    }

    fun start() {
        // Synchronous persistence is required before a deliberate process restart.
        preferences.edit(commit = true) { putBoolean("active", true) }
        activate()
    }

    fun stop() {
        preferences.edit(commit = true) { putBoolean("active", false) }
        classifier.stop()
        audioRouteLab.stop()
        eventLog.stopRaceCapture()
        activeState.value = false
    }

    fun clear() {
        stop()
        classifier.clear()
        audioRouteLab.clear()
        eventLog.clearRaceCapture()
    }

    private fun activate() {
        eventLog.startRaceCapture()
        audioRouteLab.start()
        classifier.start()
        activeState.value = true
    }
}
