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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

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
 * ### The custom length, added in wave 4
 *
 * PLAY-008's third option, and the last part of the requirement to land. A slider rather than a number
 * picker: somebody setting a timer at midnight wants "about an hour", not sixty-one exactly, and the range
 * runs to eight hours so it also serves the listener who wants the book to run all night.
 *
 * It is revealed by a chip rather than always visible. Seven presets, end-of-chapter and off already fill the
 * row, and a slider under them would be the tallest thing in the sheet for the option used least.
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
        var isCustom by remember { mutableStateOf(state.isCustomLength()) }
        var customMinutes by remember {
            mutableIntStateOf(state.customMinutes() ?: DEFAULT_CUSTOM_MINUTES)
        }
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
                // PRODUCT_SPEC PLAY-008 — "custom". Selected when a running fixed timer is not one of the
                // presets, so reopening the sheet shows the custom length as the chosen option rather than
                // showing nothing selected.
                FilterChip(
                    selected = isCustom || state.isCustomLength(),
                    onClick = { isCustom = !isCustom },
                    label = { Text(text = stringResource(R.string.sleep_timer_custom)) },
                )
            }
            if (isCustom) {
                CustomLength(
                    minutes = customMinutes,
                    onMinutesChanged = { customMinutes = it },
                    onStart = { onSelect(SleepTimerMode.Fixed(customMinutes.minutes)) },
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

/**
 * PRODUCT_SPEC PLAY-008 — the custom length: a slider and a button that starts it.
 *
 * The start is explicit, unlike every preset chip. A slider that armed the timer on every value change would
 * start and cancel a timer forty times while a thumb moved across it — and each of those is a row in the
 * history the timer writes (ADR-0014).
 */
@Composable
private fun CustomLength(
    minutes: Int,
    onMinutesChanged: (Int) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.sleep_timer_minutes, minutes),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = minutes.toFloat(),
            onValueChange = { onMinutesChanged(it.roundToInt()) },
            valueRange = CUSTOM_RANGE,
        )
        TextButton(onClick = onStart) {
            Text(text = stringResource(R.string.sleep_timer_start_custom))
        }
    }
}

/** Whether a running fixed timer was set to something outside the preset list. */
private fun SleepTimerState.isCustomLength(): Boolean {
    val mode = this.mode
    return mode is SleepTimerMode.Fixed && mode.length !in SleepTimerSettings.Presets
}

private fun SleepTimerState.customMinutes(): Int? =
    (mode as? SleepTimerMode.Fixed)?.length?.inWholeMinutes?.toInt()?.takeIf { isCustomLength() }

/** Between the longest preset and the range's ceiling, which is where "all night" starts. */
private const val DEFAULT_CUSTOM_MINUTES = 120

/**
 * PLAY-008's custom range, in minutes, taken from the model rather than written twice.
 *
 * `LengthRange` is one to four hundred and eighty minutes, and the slider works in `Float` — so the
 * conversion happens once, here, instead of inside a composable that recomposes on every drag.
 */
private val CUSTOM_RANGE: ClosedFloatingPointRange<Float> = run {
    val range = SleepTimerSettings.LengthRange
    range.start.inWholeMinutes.toFloat()..range.endInclusive.inWholeMinutes.toFloat()
}
