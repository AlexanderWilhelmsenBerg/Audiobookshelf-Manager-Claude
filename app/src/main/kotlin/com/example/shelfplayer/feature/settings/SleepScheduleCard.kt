package com.example.shelfplayer.feature.settings

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import java.time.LocalTime
import java.util.Locale

/** BW-SLEEP-01 / SET-002: the stored enable switch owns inline visibility, never the countdown. */
@Composable
internal fun SleepScheduleCard(settings: SleepTimerScheduleSettings, actions: SleepScheduleSettingsActions) {
    SettingsCard {
        SwitchRow(stringResource(R.string.sleep_schedule_title), settings.enabled, actions.onEnabledChanged)
        AnimatedVisibility(settings.enabled) {
            Column {
                SleepScheduleTimeRow(
                    stringResource(R.string.sleep_schedule_start),
                    settings.start,
                    actions.onStartChanged,
                )
                SleepScheduleTimeRow(stringResource(R.string.sleep_schedule_end), settings.end, actions.onEndChanged)
                Text(
                    stringResource(R.string.sleep_schedule_summary),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.start ==
                    settings.end
                ) {
                    Text(stringResource(R.string.sleep_schedule_empty_window), Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepScheduleTimeRow(title: String, time: LocalTime, onChanged: (LocalTime) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    ActionRow("$title: ${String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)}", { editing = true })
    if (editing) {
        val picker = rememberTimePickerState(time.hour, time.minute, DateFormat.is24HourFormat(LocalContext.current))
        SettingsGlassDialog(title, {
            editing = false
        }, stringResource(android.R.string.cancel), stringResource(android.R.string.ok), confirm = {
            onChanged(LocalTime.of(picker.hour, picker.minute))
            editing = false
        }) { TimeInput(picker) }
    }
}
