package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import kotlin.math.roundToInt

/**
 * PRODUCT_SPEC PLAY-007 — the speed chooser: presets, a slider, and two nudge buttons.
 *
 * ### Three controls for one number, on purpose
 *
 * They are used at different moments. The presets are what somebody picks once when they start a book. The
 * nudges are what they use when 1.5× is *almost* right — a slider cannot reliably hit one step out of
 * sixty-one with a thumb. The slider is for a large change, where tapping plus twenty times is absurd.
 *
 * ### Applied live, with no confirm
 *
 * Every change reaches the player immediately, because the only way to judge a listening speed is to hear
 * it. That is also why the sheet does not close on a selection: a listener trying 1.75× against 2× would
 * otherwise reopen it between each one.
 *
 * ### Reset is not "set to 1.0×"
 *
 * It clears the *override*, so the book follows the profile default again — which is a different thing the
 * moment that default changes. See `PlaybackSettingsRepository.setSpeedFor`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    speed: PlaybackSpeed,
    onSelect: (PlaybackSpeed) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.player_speed_title), style = MaterialTheme.typography.titleMedium)

            Text(
                text = stringResource(R.string.player_speed_value, speed.label()),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSelect(speed.decreased()) }) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.player_speed_slower),
                    )
                }
                Slider(
                    value = speed.value,
                    onValueChange = { onSelect(PlaybackSpeed.of(it)) },
                    valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                    // The slider's own step count, so dragging lands on the grid PLAY-007 defines rather
                    // than between two of its values. `steps` counts the gaps *between* the ends.
                    steps = SLIDER_STEPS,
                    modifier = Modifier.weight(WEIGHT_FILL),
                )
                IconButton(onClick = { onSelect(speed.increased()) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.player_speed_faster),
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackSpeed.Presets.forEach { preset ->
                    FilterChip(
                        selected = preset == speed,
                        onClick = { onSelect(preset) },
                        label = { Text(text = stringResource(R.string.player_speed_value, preset.label())) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.player_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = onReset) {
                Text(text = stringResource(R.string.player_speed_reset))
            }
        }
    }
}

/**
 * The number of intermediate stops on the slider.
 *
 * `(3.0 − 0.5) / 0.05` is fifty, and `steps` counts the values *between* the ends, so it is one fewer.
 * Computed rather than written as `49` so it cannot drift from the model's range.
 */
private val SLIDER_STEPS: Int =
    ((PlaybackSpeed.MAX - PlaybackSpeed.MIN) / PlaybackSpeed.STEP).roundToInt() - 1

private const val WEIGHT_FILL = 1f
