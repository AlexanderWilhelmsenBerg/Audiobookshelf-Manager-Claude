package com.example.shelfplayer.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.playback.AudioRouteLab
import com.example.shelfplayer.playback.OutputClassifier
import com.example.shelfplayer.playback.UnifiedDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** UI adapter. The three read-only diagnostic sources retain independent ownership. */
@HiltViewModel
class UnifiedDiagnosticsViewModel @Inject constructor(
    private val capture: UnifiedDiagnosticsCapture,
    private val lab: AudioRouteLab,
    private val classifier: OutputClassifier,
    private val logs: EventLog,
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) : ViewModel() {
    val active: StateFlow<Boolean> = capture.active
    val labState = lab.state
    val classifierState = classifier.state
    val raceState = logs.raceCapture

    fun start() = capture.start()
    fun stop() = capture.stop()
    fun clear() = capture.clear()

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
