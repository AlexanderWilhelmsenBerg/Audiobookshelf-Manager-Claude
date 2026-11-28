package com.example.shelfplayer.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.playback.CarLabHeard
import com.example.shelfplayer.playback.CarLabPhase
import com.example.shelfplayer.playback.HeadsetRouteTest
import dagger.hilt.android.EntryPointAccessors

private fun routeTest(context: Context) =
    EntryPointAccessors.fromApplication(context, PlaybackDiagnosticsEntryPoint::class.java).headsetRouteTest()

internal fun carLabReport(context: Context, report: String) = routeTest(context).report(report)

internal fun endCarLab(context: Context, clear: Boolean) {
    val test = routeTest(context)
    test.finish(if (clear) "capture-cleared" else "capture-stopped")
    if (clear) test.journal.clear()
}

@Composable
internal fun CarLabControls(enabled: Boolean, initiallyExpanded: Boolean) {
    val context = LocalContext.current.applicationContext
    val test = remember(context) { routeTest(context) }
    val status by test.journal.status.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var phase by rememberSaveable(status.run) { mutableStateOf(CarLabPhase.Connect) }
    var feedback by rememberSaveable(status.run) { mutableStateOf("") }
    TextButton(enabled = enabled, onClick = { expanded = !expanded }) { Text("Car playback test suite") }
    if (!expanded) return
    val outputCase by test.outputCase.collectAsStateWithLifecycle()
    Column {
        HeadsetOutputControls(outputCase, enabled && !status.running, test::applyOutputCase)
        Text("Play your book in the headset, start a run, then start and stop the parked car as usual.")
        TextButton(enabled = enabled, onClick = test::begin) {
            Text(if (status.running) "Start another headset run" else "Start headset route test")
        }
        if (status.run > 0) {
            val message = if (status.running) "Recording headset route" else status.message.replace('-', ' ')
            Text("Run #" + status.run + " · " + message, style = MaterialTheme.typography.titleMedium)
            if (status.running) {
                TextButton(enabled = enabled, onClick = { test.finish() }) { Text("Finish run") }
            }
            TextButton(onClick = {
                phase = if (phase == CarLabPhase.Connect) CarLabPhase.Disconnect else CarLabPhase.Connect
                feedback = ""
            }) { Text("Observation · " + phase.name) }
            CarLabHeard.entries.forEach { heard ->
                TextButton(onClick = {
                    test.journal.heard(status.run, phase, heard)
                    feedback = phase.name + " · " + heard.name + " saved"
                }) { Text(if (heard == CarLabHeard.WrongOutput) "Wrong output" else heard.name) }
            }
            if (feedback.isNotEmpty()) Text(feedback)
            Text("After the last run, finish and tap Save JSON.")
        }
    }
}
