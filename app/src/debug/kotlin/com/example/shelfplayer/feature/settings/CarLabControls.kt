package com.example.shelfplayer.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.CarLabHeard
import com.example.shelfplayer.playback.CarLabPhase
import com.example.shelfplayer.playback.HeadsetOutputCase
import com.example.shelfplayer.playback.HeadsetRouteTest
import com.example.shelfplayer.playback.HeadsetTestResult
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
    Column {
        HeadsetOutputControls(test, enabled && !status.running)
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

@Composable
private fun HeadsetOutputControls(test: HeadsetRouteTest, enabled: Boolean) {
    val active by test.outputCase.collectAsStateWithLifecycle()
    var blockCapture by rememberSaveable(active) { mutableStateOf(active.blockCapture) }
    var shortBuffer by rememberSaveable(active) { mutableStateOf(active.shortBuffer) }
    var result by rememberSaveable { mutableStateOf<HeadsetTestResult?>(null) }
    Text(stringResource(R.string.headset_output_test_hint))
    FilterChip(
        selected = blockCapture,
        onClick = {
            blockCapture = !blockCapture
            result = null
        },
        enabled = enabled,
        label = { Text(stringResource(R.string.headset_output_block_capture)) },
    )
    FilterChip(
        selected = shortBuffer,
        onClick = {
            shortBuffer = !shortBuffer
            result = null
        },
        enabled = enabled,
        label = { Text(stringResource(R.string.headset_output_short_buffer)) },
    )
    val selected = HeadsetOutputCase.entries.single { it.blockCapture == blockCapture && it.shortBuffer == shortBuffer }
    TextButton(enabled = enabled && selected != active, onClick = { result = test.applyOutputCase(selected) }) {
        Text(stringResource(R.string.headset_output_apply))
    }
    val message = when (result) {
        HeadsetTestResult.PauseFirst -> R.string.headset_output_pause_first
        HeadsetTestResult.PlayHeadsetFirst -> R.string.headset_output_verify_first
        HeadsetTestResult.CaptureUnsupported -> R.string.headset_output_unsupported
        HeadsetTestResult.Applied, null -> null
    }
    message?.let { Text(stringResource(it)) }
    val label = when (active) {
        HeadsetOutputCase.Normal -> R.string.headset_output_normal
        HeadsetOutputCase.CaptureBlocked -> R.string.headset_output_block_capture
        HeadsetOutputCase.ShortBuffer -> R.string.headset_output_short_buffer
        HeadsetOutputCase.Combined -> R.string.headset_output_combined
    }
    Text(stringResource(R.string.headset_output_active, stringResource(label)))
}
