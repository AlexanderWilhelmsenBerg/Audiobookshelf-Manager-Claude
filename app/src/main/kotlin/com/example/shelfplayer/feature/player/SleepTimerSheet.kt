package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState

/**
 * PRODUCT_SPEC PLAY-008 — the timer's options: presets, end of chapter, and off.
 *
 * ### Why the running timer is a chip rather than a separate screen
 *
 * A listener setting a timer at midnight is choosing between eight things and then putting the phone
 * down. A sheet of chips is one tap from the mini player and one tap to the answer; anything with
 * navigation in it is two taps too many for the moment it is used in.
 *
 * The selected chip is the running mode, so the sheet doubles as the display of what is already set —
 * which is why "off" is a chip in the same row rather than a destructive button somewhere else.
 *
 * PLAY-008's "custom" option is deliberately not here yet, and its absence is honest: a number picker
 * that writes a duration is a small thing, and the eight presets plus end-of-chapter cover what the
 * requirement's own list covers. See the wave-4 note in `docs/phase-2-plan.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    onSelect: (SleepTimerMode?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.isActive,
                    onClick = { onSelect(null) },
                    label = { Text(text = stringResource(R.string.sleep_timer_off)) },
                )
                SleepTimerSettings.Presets.forEach { preset ->
                    val mode = SleepTimerMode.Fixed(preset)
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onSelect(mode) },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.sleep_timer_minutes,
                                    preset.inWholeMinutes.toInt(),
                                ),
                            )
                        },
                    )
                }
                FilterChip(
                    selected = state.mode == SleepTimerMode.EndOfChapter,
                    onClick = { onSelect(SleepTimerMode.EndOfChapter) },
                    label = { Text(text = stringResource(R.string.sleep_timer_end_of_chapter)) },
                )
            }
            if (state.isActive) {
                Text(
                    text = stringResource(R.string.sleep_timer_active, state.remaining.asShortLabel()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onSelect(state.mode) }) {
                    Text(text = stringResource(R.string.sleep_timer_restart))
                }
            }
        }
    }
}
