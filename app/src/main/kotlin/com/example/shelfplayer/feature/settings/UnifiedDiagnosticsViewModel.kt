package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.playback.AudioRouteLab
import com.example.shelfplayer.playback.OutputClassifier
import com.example.shelfplayer.playback.UnifiedDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** UI adapter. The three read-only diagnostic sources retain independent ownership. */
@HiltViewModel
class UnifiedDiagnosticsViewModel @Inject constructor(
    private val capture: UnifiedDiagnosticsCapture,
    private val lab: AudioRouteLab,
    private val classifier: OutputClassifier,
    private val logs: EventLog,
) : ViewModel() {
    val active: StateFlow<Boolean> = capture.active
    val labState = lab.state
    val classifierState = classifier.state
    val raceState = logs.raceCapture

    fun start() = capture.start()
    fun stop() = capture.stop()
    fun clear() = capture.clear()

    fun jsonReport(build: String, debugConsoleReport: String): String = UnifiedDiagnosticsJson.build(
        build = build,
        active = capture.active.value,
        lab = lab.state.value,
        classifier = classifier.state.value,
        eventLog = logs.events.value,
        race = logs.raceCapture.value,
        debugConsole = debugConsoleReport,
    )
}
