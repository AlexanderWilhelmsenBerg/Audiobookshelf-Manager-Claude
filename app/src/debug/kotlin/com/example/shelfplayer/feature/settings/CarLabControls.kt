package com.example.shelfplayer.feature.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.playback.CarLabCase
import com.example.shelfplayer.playback.CarLabHeard
import com.example.shelfplayer.playback.CarLabJournal
import com.example.shelfplayer.playback.CarLabPhase
import com.example.shelfplayer.playback.CarLabService

internal fun carLabReport(context: Context, report: String): String = CarLabJournal.get(context).attach(report)

internal fun endCarLab(context: Context, clear: Boolean) {
    CarLabService.end(context, if (clear) "capture-cleared" else "capture-stopped")
    if (clear) CarLabJournal.get(context).clear()
}

@Composable
internal fun CarLabControls(enabled: Boolean, initiallyExpanded: Boolean) {
    val context = LocalContext.current
    val journal = CarLabJournal.get(context)
    val state by journal.status.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var phase by rememberSaveable(state.run) { mutableStateOf(CarLabPhase.Connect) }
    var feedback by rememberSaveable(state.run) { mutableStateOf("") }
    TextButton(enabled = enabled, onClick = { expanded = !expanded }) { Text("Car playback test suite") }
    if (!expanded) return
    Column {
        Text(
            "With the car off, tap A and play your book in the headset. Start and stop the car. " +
                "Finish the run, then repeat with B using the same book, headset and focus settings.",
        )
        var samples by rememberSaveable { mutableStateOf(false) }
        CarLabCase.entries.forEach { case ->
            if (case == CarLabCase.Exo) {
                TextButton(onClick = { samples = !samples }) { Text("Other player samples") }
                if (samples) Text("Pause your book before starting a sample.")
            }
            if (!case.usesBook && !samples) return@forEach
            val supported = case != CarLabCase.Preferred || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            TextButton(enabled = enabled && supported, onClick = { CarLabService.begin(context, case) }) {
                Text(if (supported) case.label else case.label + " (Android 9+)")
            }
        }
        if (state.run > 0) {
            Text(
                "Run #" + state.run + " · " + (state.case?.label ?: state.message.replace('-', ' ')),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.case != null) {
                if (state.case == CarLabCase.BookHold) Text(headsetStatus(state.headset))
                if (state.message != "running") {
                    Text("Sample stopped · " + state.message.replace('-', ' ') + ". Still recording the car cycle.")
                }
                TextButton(enabled = enabled, onClick = { CarLabService.end(context) }) { Text("Finish run") }
            }
            Text("What did you hear? Record each observation for this run.")
            TextButton(onClick = {
                phase = if (phase == CarLabPhase.Connect) CarLabPhase.Disconnect else CarLabPhase.Connect
            }) { Text("Phase · " + phase.name + " (change)") }
            CarLabHeard.entries.forEach { heard ->
                TextButton(enabled = enabled, onClick = {
                    journal.heard(state.run, phase, heard)
                    feedback = "Recorded " + phase.name + " · " + heard.name
                }) { Text(if (heard == CarLabHeard.WrongOutput) "Wrong output" else heard.name) }
            }
            if (feedback.isNotEmpty()) Text(feedback)
        }
        Text("Tap the same case again to repeat it. After the last run, finish and use Save JSON.")
    }
}

private fun headsetStatus(event: String): String = when (event) {
    "headset-armed" -> "Headset verified. Start the car when ready."
    "hold-started" -> "Waiting for the original headset. Playback is muted."
    "hold-Verified" -> "Original headset verified again."
    "hold-Refused", "hold-unavailable" -> "Headset hold failed. Check where you hear audio."
    "hold-Superseded" -> "Headset hold ended after playback changed."
    else -> "Play in your headset with “Lock headset while playing” enabled and Nearby devices access allowed."
}
