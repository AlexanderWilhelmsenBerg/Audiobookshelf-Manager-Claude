package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FinishedThreshold
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.StartupMode

/**
 * Compact playback settings: cards establish the groups and each multi-value setting expands in place.
 * There are deliberately no explanatory paragraphs; the setting name, current value and choices are the UI.
 */
internal fun LazyListScope.playbackTab(
    settings: PlaybackSettings,
    libraries: List<Library>,
    actions: PlaybackSettingsActions,
    networkPolicy: NetworkPolicy = NetworkPolicy.Default,
    housekeeping: DownloadHousekeeping = DownloadHousekeeping.Default,
) {
    listeningCard(settings, actions)
    behaviourCard(settings, actions)
    downloadsAndDataCard(housekeeping, networkPolicy, actions)
    finishedCard(libraries)
}

private fun LazyListScope.listeningCard(settings: PlaybackSettings, actions: PlaybackSettingsActions) {
    item { SectionHeader(text = stringResource(R.string.settings_group_listening)) }
    item {
        SettingsCard {
            InlineChoiceRow(
                label = stringResource(R.string.settings_speed_default),
                options = PlaybackSpeed.Presets,
                selected = settings.defaultSpeed,
                labelOf = { speed -> stringResource(R.string.player_speed_value, speed.label()) },
                onSelected = actions.onSpeedChanged,
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_skip_back),
                options = SkipIntervals.Presets,
                selected = settings.skips.back,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> actions.onSkipsChanged(settings.skips.copy(back = chosen)) },
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_skip_forward),
                options = SkipIntervals.Presets,
                selected = settings.skips.forward,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> actions.onSkipsChanged(settings.skips.copy(forward = chosen)) },
            )
            autoRewindRow(settings.autoRewind, actions.onAutoRewindChanged)
            InlineChoiceRow(
                label = stringResource(R.string.settings_section_buffer),
                options = BufferPreset.entries,
                selected = settings.buffer,
                labelOf = { preset -> stringResource(preset.labelRes()) },
                onSelected = actions.onBufferChanged,
            )
        }
    }
}

@Composable
private fun autoRewindRow(rewind: AutoRewind, onChanged: (AutoRewind) -> Unit) {
    ExpandableSettingsRow(
        label = stringResource(R.string.settings_section_rewind),
        valueLabel = onOffLabel(rewind.isEnabled),
    ) {
        SwitchRow(
            label = stringResource(R.string.settings_rewind_enabled),
            checked = rewind.isEnabled,
            onCheckedChange = { enabled -> onChanged(rewind.copy(isEnabled = enabled)) },
        )
        if (rewind.isEnabled) {
            InlineChoiceRow(
                label = stringResource(R.string.settings_rewind_short),
                options = AutoRewind.Presets,
                selected = rewind.afterShortPause,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> onChanged(rewind.copy(afterShortPause = chosen)) },
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_rewind_medium),
                options = AutoRewind.Presets,
                selected = rewind.afterMediumPause,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> onChanged(rewind.copy(afterMediumPause = chosen)) },
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_rewind_long),
                options = AutoRewind.Presets,
                selected = rewind.afterLongPause,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> onChanged(rewind.copy(afterLongPause = chosen)) },
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_rewind_very_long),
                options = AutoRewind.Presets,
                selected = rewind.afterVeryLongPause,
                labelOf = { seconds -> secondsLabel(seconds.inWholeSeconds.toInt()) },
                onSelected = { chosen -> onChanged(rewind.copy(afterVeryLongPause = chosen)) },
            )
        }
    }
}

private fun LazyListScope.behaviourCard(settings: PlaybackSettings, actions: PlaybackSettingsActions) {
    item { SectionHeader(text = stringResource(R.string.settings_group_playback_behavior)) }
    item {
        SettingsCard {
            SwitchRow(
                label = stringResource(R.string.settings_series_auto_advance),
                checked = settings.autoAdvanceSeries,
                onCheckedChange = actions.onAutoAdvanceSeriesChanged,
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_section_interruptions),
                options = FocusBehaviour.entries,
                selected = settings.focusBehaviour,
                labelOf = { option ->
                    stringResource(
                        when (option) {
                            FocusBehaviour.Pause -> R.string.settings_focus_pause
                            FocusBehaviour.Duck -> R.string.settings_focus_duck
                        },
                    )
                },
                onSelected = actions.onFocusBehaviourChanged,
            )
            InlineChoiceRow(
                label = stringResource(R.string.settings_section_startup),
                options = StartupMode.entries,
                selected = settings.startupMode,
                labelOf = { option ->
                    stringResource(
                        when (option) {
                            StartupMode.OnMediaCommand -> R.string.settings_startup_nothing
                            StartupMode.RestorePaused -> R.string.settings_startup_restore
                            StartupMode.ResumeOnOpen -> R.string.settings_startup_resume
                        },
                    )
                },
                onSelected = actions.onStartupModeChanged,
            )
        }
    }
}

private fun LazyListScope.downloadsAndDataCard(
    housekeeping: DownloadHousekeeping,
    networkPolicy: NetworkPolicy,
    actions: PlaybackSettingsActions,
) {
    item { SectionHeader(text = stringResource(R.string.settings_group_downloads_data)) }
    item {
        SettingsCard {
            ActionRow(
                label = stringResource(R.string.settings_downloads_manage),
                onClick = actions.onManageDownloads,
            )
            SwitchRow(
                label = stringResource(R.string.settings_downloads_smart),
                checked = housekeeping.smartDownload,
                onCheckedChange = { enabled ->
                    actions.onHousekeepingChanged(housekeeping.copy(smartDownload = enabled))
                },
            )
            if (housekeeping.smartDownload) {
                SwitchRow(
                    label = stringResource(R.string.settings_downloads_delete_previous),
                    checked = housekeeping.deletePreviousOnSmartDownload,
                    onCheckedChange = { enabled ->
                        actions.onHousekeepingChanged(
                            housekeeping.copy(deletePreviousOnSmartDownload = enabled),
                        )
                    },
                )
            }
            InlineChoiceRow(
                label = stringResource(R.string.settings_downloads_retention),
                options = DownloadHousekeeping.RetentionDays,
                selected = housekeeping.deleteFinishedAfterDays,
                labelOf = { days -> retentionLabel(days) },
                onSelected = { days ->
                    actions.onHousekeepingChanged(housekeeping.copy(deleteFinishedAfterDays = days))
                },
            )
            SwitchRow(
                label = stringResource(R.string.settings_network_streaming),
                checked = networkPolicy.streamingOnCellular,
                onCheckedChange = { enabled ->
                    actions.onNetworkPolicyChanged(networkPolicy.copy(streamingOnCellular = enabled))
                },
            )
            SwitchRow(
                label = stringResource(R.string.settings_network_downloads),
                checked = networkPolicy.downloadsOnCellular,
                onCheckedChange = { enabled ->
                    actions.onNetworkPolicyChanged(networkPolicy.copy(downloadsOnCellular = enabled))
                },
            )
            SwitchRow(
                label = stringResource(R.string.settings_network_smart_downloads),
                checked = networkPolicy.smartDownloadsOnCellular,
                onCheckedChange = { enabled ->
                    actions.onNetworkPolicyChanged(networkPolicy.copy(smartDownloadsOnCellular = enabled))
                },
            )
        }
    }
}

/** Server-owned reading kept compact rather than explained as if it were a local preference. */
private fun LazyListScope.finishedCard(libraries: List<Library>) {
    if (libraries.isEmpty()) return
    item { SectionHeader(text = stringResource(R.string.settings_section_finished)) }
    item {
        SettingsCard {
            libraries.forEach { library ->
                val seconds = (library.finishedWhenRemaining ?: FinishedThreshold.Default).inWholeSeconds.toInt()
                ReadOnlyValueRow(
                    label = library.name,
                    value = secondsLabel(seconds),
                )
            }
        }
    }
}

@Composable
private fun retentionLabel(days: Int): String = if (days == 0) {
    stringResource(R.string.settings_downloads_retention_never)
} else {
    pluralStringResource(R.plurals.settings_downloads_retention_days, days, days)
}

@Composable
private fun secondsLabel(seconds: Int): String = stringResource(R.string.settings_seconds, seconds)

@Composable
private fun onOffLabel(enabled: Boolean): String = stringResource(
    if (enabled) R.string.settings_value_on else R.string.settings_value_off,
)

private fun BufferPreset.labelRes(): Int = when (this) {
    BufferPreset.Automatic -> R.string.settings_buffer_automatic
    BufferPreset.Low -> R.string.settings_buffer_low
    BufferPreset.Standard -> R.string.settings_buffer_standard
    BufferPreset.High -> R.string.settings_buffer_high
    BufferPreset.VeryHigh -> R.string.settings_buffer_very_high
}

@Immutable
data class PlaybackSettingsActions(
    val onSpeedChanged: (PlaybackSpeed) -> Unit,
    val onSkipsChanged: (SkipIntervals) -> Unit,
    val onAutoRewindChanged: (AutoRewind) -> Unit,
    val onBufferChanged: (BufferPreset) -> Unit,
    val onAutoAdvanceSeriesChanged: (Boolean) -> Unit = {},
    val onFocusBehaviourChanged: (FocusBehaviour) -> Unit = {},
    val onStartupModeChanged: (StartupMode) -> Unit = {},
    val onNetworkPolicyChanged: (NetworkPolicy) -> Unit = {},
    val onHousekeepingChanged: (DownloadHousekeeping) -> Unit = {},
    val onManageDownloads: () -> Unit = {},
)
