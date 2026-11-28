package com.example.shelfplayer.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.HeadsetOutputCase
import com.example.shelfplayer.playback.HeadsetTestResult

@Composable
internal fun HeadsetOutputControls(
    active: HeadsetOutputCase,
    enabled: Boolean,
    apply: (HeadsetOutputCase) -> HeadsetTestResult,
) {
    var selected by rememberSaveable(active) { mutableStateOf(active) }
    var result by rememberSaveable(active) { mutableStateOf<HeadsetTestResult?>(null) }
    val select: (HeadsetOutputCase) -> Unit = {
        selected = it
        result = null
    }
    Text(stringResource(R.string.headset_output_test_hint))
    CaseGroup(
        R.string.headset_output_disconnect,
        R.string.headset_output_disconnect_settings,
        listOf(HeadsetOutputCase.Disconnect500, HeadsetOutputCase.Disconnect1000),
        selected,
        enabled,
        select,
    )
    CaseGroup(
        R.string.headset_output_connection,
        R.string.headset_output_connection_settings,
        listOf(HeadsetOutputCase.ConnectTakeBackOff, HeadsetOutputCase.ConnectTakeBackOn),
        selected,
        enabled,
        select,
    )
    var showOther by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showOther = !showOther }) { Text(stringResource(R.string.headset_output_earlier_cases)) }
    if (showOther) {
        Text(stringResource(R.string.headset_output_legacy_hint))
        CaseChoices(
            listOf(
                HeadsetOutputCase.Normal,
                HeadsetOutputCase.CaptureBlocked,
                HeadsetOutputCase.ShortBuffer,
                HeadsetOutputCase.Combined,
            ),
            selected,
            enabled,
            select,
        )
    }
    TextButton(enabled = enabled && selected != active, onClick = { result = apply(selected) }) {
        Text(stringResource(R.string.headset_output_apply))
    }
    val message = when (result) {
        HeadsetTestResult.PauseFirst -> R.string.headset_output_pause_first
        HeadsetTestResult.PlayHeadsetFirst -> R.string.headset_output_verify_first
        HeadsetTestResult.CaptureUnsupported -> R.string.headset_output_unsupported
        HeadsetTestResult.TakeBackUnavailable -> R.string.headset_output_take_back_unavailable
        HeadsetTestResult.Applied, null -> null
    }
    message?.let { Text(stringResource(it)) }
    Text(stringResource(R.string.headset_output_active, stringResource(active.label())))
}

@Composable
private fun CaseGroup(
    @StringRes title: Int,
    @StringRes settings: Int,
    cases: List<HeadsetOutputCase>,
    selected: HeadsetOutputCase,
    enabled: Boolean,
    select: (HeadsetOutputCase) -> Unit,
) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(settings))
    CaseChoices(cases, selected, enabled, select)
}

@Composable
private fun CaseChoices(
    cases: List<HeadsetOutputCase>,
    selected: HeadsetOutputCase,
    enabled: Boolean,
    select: (HeadsetOutputCase) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cases.forEach { case ->
            FilterChip(
                selected = selected == case,
                onClick = { select(case) },
                enabled = enabled,
                label = { Text(stringResource(case.label())) },
            )
        }
    }
}

@StringRes
private fun HeadsetOutputCase.label() = when (this) {
    HeadsetOutputCase.Normal -> R.string.headset_output_normal
    HeadsetOutputCase.CaptureBlocked -> R.string.headset_output_block_capture
    HeadsetOutputCase.ShortBuffer -> R.string.headset_output_short_buffer
    HeadsetOutputCase.Combined -> R.string.headset_output_combined
    HeadsetOutputCase.Disconnect500 -> R.string.headset_output_disconnect_500
    HeadsetOutputCase.Disconnect1000 -> R.string.headset_output_disconnect_1000
    HeadsetOutputCase.ConnectTakeBackOff -> R.string.headset_output_connection_off
    HeadsetOutputCase.ConnectTakeBackOn -> R.string.headset_output_connection_on
}
