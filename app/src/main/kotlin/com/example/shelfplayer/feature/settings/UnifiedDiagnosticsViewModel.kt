package com.example.shelfplayer.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.diagnostics.CrashReportStore
import com.example.shelfplayer.playback.AudioRouteLab
import com.example.shelfplayer.playback.OutputClassifier
import com.example.shelfplayer.playback.UnifiedDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** UI adapter. Diagnostic sources keep their own state; this owns the clear operation's lifetime. */
@HiltViewModel
class UnifiedDiagnosticsViewModel @Inject constructor(
    private val capture: UnifiedDiagnosticsCapture,
    private val lab: AudioRouteLab,
    private val classifier: OutputClassifier,
    private val logs: EventLog,
    private val crashes: CrashReportStore,
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) : ViewModel() {
    val clearing: StateFlow<Boolean> = capture.clearing

    val active: StateFlow<Boolean> = capture.active
    val labState = lab.state
    val classifierState = classifier.state
    val raceState = logs.raceCapture

    fun start() {
        capture.start()
    }

    fun stop() {
        capture.stop()
    }

    fun clear() {
        if (!capture.beginClear()) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    clearAllDiagnosticSources(capture::clearAndAwait) {
                        withContext(io) { crashes.clear() }
                    }
                }
            } finally {
                capture.finishClear()
            }
        }
    }

    /** Write only into the user-approved document selected in Android's system picker. */
    suspend fun saveJson(uri: Uri, report: String): Boolean = withContext(io) {
        runCatching {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Document provider returned no writable stream")
            UnifiedDiagnosticsFileExport.write(output, report)
        }.isSuccess
    }

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

/** Keep the unified destructive action complete while leaving each recorder as owner of its own state. */
internal suspend fun clearAllDiagnosticSources(
    clearCaptures: suspend () -> Unit,
    clearCrashReport: suspend () -> Unit,
) {
    try {
        clearCaptures()
    } finally {
        clearCrashReport()
    }
}
