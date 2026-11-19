package com.example.shelfplayer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.PlaybackEqualizer
import kotlin.math.roundToInt

@Composable
internal fun PlaybackEqualizerRow(equalizer: PlaybackEqualizer, onChanged: (PlaybackEqualizer) -> Unit) {
    ExpandableSettingsRow(
        label = stringResource(R.string.settings_equalizer),
        valueLabel = equalizerLabel(equalizer),
    ) {
        var draft by remember(equalizer) { mutableStateOf(equalizer) }
        val save = { onChanged(draft) }
        InlineChoiceRow(
            label = stringResource(R.string.settings_equalizer_preset),
            options = listOf(PlaybackEqualizer.Original, PlaybackEqualizer.Warm, PlaybackEqualizer.Clear),
            selected = draft,
            labelOf = { equalizerLabel(it) },
            onSelected = {
                draft = it
                save()
            },
        )
        EqualizerBand(stringResource(R.string.settings_equalizer_bass), draft.bass, save) {
            draft = draft.copy(bass = it)
        }
        EqualizerBand(stringResource(R.string.settings_equalizer_presence), draft.presence, save) {
            draft = draft.copy(presence = it)
        }
        EqualizerBand(stringResource(R.string.settings_equalizer_treble), draft.treble, save) {
            draft = draft.copy(treble = it)
        }
    }
}

@Composable
private fun EqualizerBand(label: String, gain: Int, onSave: () -> Unit, onMove: (Int) -> Unit) {
    val range = PlaybackEqualizer.GainRange
    SliderRow(
        label = label,
        valueLabel = stringResource(R.string.settings_equalizer_gain, gain),
        value = gain.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        onValueChange = { onMove(it.roundToInt()) },
        onValueChangeFinished = onSave,
    )
}

@Composable
private fun equalizerLabel(equalizer: PlaybackEqualizer): String = stringResource(
    when (equalizer) {
        PlaybackEqualizer.Original -> R.string.settings_equalizer_original
        PlaybackEqualizer.Warm -> R.string.settings_equalizer_warm
        PlaybackEqualizer.Clear -> R.string.settings_equalizer_clear
        else -> R.string.settings_equalizer_custom
    },
)
