package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
 * PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — the playback controls, as a settings tab.
 *
 * ### Why a tab of its own rather than rows under Server
 *
 * These are the settings somebody changes more than once, and they are about how *they* listen rather than
 * about what the app is connected to. The Server tab answers "which library"; this one answers "how fast,
 * how far, and what happens when I come back".
 *
 * ### Chips rather than sliders throughout
 *
 * Every value here has a small set of sensible answers and a wide legal range. A slider would let a user
 * choose 37 seconds for a skip button, which nobody wants and which then renders with no number on the icon.
 * The player's speed sheet has the slider, because that is where a fine adjustment is actually made against
 * audio you can hear.
 */
internal fun LazyListScope.playbackTab(
    settings: PlaybackSettings,
    libraries: List<Library>,
    actions: PlaybackSettingsActions,
    networkPolicy: NetworkPolicy = NetworkPolicy.Default,
    housekeeping: DownloadHousekeeping = DownloadHousekeeping.Default,
) {
    val onSpeedChanged = actions.onSpeedChanged
    val onSkipsChanged = actions.onSkipsChanged
    val onAutoRewindChanged = actions.onAutoRewindChanged
    val onBufferChanged = actions.onBufferChanged
    item { SectionHeader(text = stringResource(R.string.settings_section_speed)) }
    item { Hint(text = stringResource(R.string.settings_speed_hint)) }
    item {
        ChipRow(
            labelRes = R.string.settings_speed_default,
            options = PlaybackSpeed.Presets,
            selected = settings.defaultSpeed,
            label = { speed -> stringResource(R.string.player_speed_value, speed.label()) },
            onSelect = onSpeedChanged,
        )
    }

    item { SectionHeader(text = stringResource(R.string.settings_section_skip)) }
    item { Hint(text = stringResource(R.string.settings_skip_hint)) }
    item {
        ChipRow(
            labelRes = R.string.settings_skip_back,
            options = SkipIntervals.Presets,
            selected = settings.skips.back,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onSkipsChanged(settings.skips.copy(back = chosen)) },
        )
    }
    item {
        ChipRow(
            labelRes = R.string.settings_skip_forward,
            options = SkipIntervals.Presets,
            selected = settings.skips.forward,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onSkipsChanged(settings.skips.copy(forward = chosen)) },
        )
    }

    item { SectionHeader(text = stringResource(R.string.settings_section_rewind)) }
    item { Hint(text = stringResource(R.string.settings_rewind_hint)) }
    item {
        SwitchRow(
            labelRes = R.string.settings_rewind_enabled,
            checked = settings.autoRewind.isEnabled,
            onCheckedChange = { enabled -> onAutoRewindChanged(settings.autoRewind.copy(isEnabled = enabled)) },
        )
    }
    // The four bands are only shown when the feature is on. A disabled control that still accepts taps is
    // confusing, and four of them under an off switch is a wall of settings that does nothing.
    if (settings.autoRewind.isEnabled) {
        rewindBands(settings.autoRewind, onAutoRewindChanged)
    }

    item { SectionHeader(text = stringResource(R.string.settings_section_buffer)) }
    item { Hint(text = stringResource(R.string.settings_buffer_hint)) }
    items(BufferPreset.entries, settings.buffer, onBufferChanged)

    finishedSection(libraries)
    downloadsSection(housekeeping, actions.onHousekeepingChanged, actions.onManageDownloads)
    networkSection(networkPolicy, actions.onNetworkPolicyChanged)
    behaviourSections(settings, actions)
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) }
}

/**
 * PRODUCT_SPEC PLAY-002 — *"A transient focus loss pauses or ducks according to setting; default is pause."*
 *
 * The two options are a genuine personal trade, so the hint states it rather than naming the setting twice:
 * pausing means nothing is missed, ducking means nothing has to be rewound. A phone call pauses either way,
 * because that is the platform's decision and not this app's — and saying so here is what stops somebody
 * choosing *Duck* and then wondering why their call still stopped the book.
 */
private fun LazyListScope.interruptionSection(behaviour: FocusBehaviour, onChanged: (FocusBehaviour) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_interruptions)) }
    item {
        ChoiceRow(
            options = FocusBehaviour.entries,
            selected = behaviour,
            label = { option ->
                stringResource(
                    when (option) {
                        FocusBehaviour.Pause -> R.string.settings_focus_pause
                        FocusBehaviour.Duck -> R.string.settings_focus_duck
                    },
                )
            },
            onSelected = onChanged,
        )
    }
    item { Hint(text = stringResource(R.string.settings_focus_hint)) }
}

/**
 * PRODUCT_SPEC ROUTE-003 — what opening the app does.
 *
 * Three options and only one of them makes a sound. The default is the first, which does nothing at all —
 * ROUTE-003's *"app launch alone never starts playback by default"* is a property of this list's ordering
 * as much as of the code behind it.
 */
private fun LazyListScope.startupSection(mode: StartupMode, onChanged: (StartupMode) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_startup)) }
    item {
        ChoiceRow(
            options = StartupMode.entries,
            selected = mode,
            label = { option ->
                stringResource(
                    when (option) {
                        StartupMode.OnMediaCommand -> R.string.settings_startup_nothing
                        StartupMode.RestorePaused -> R.string.settings_startup_restore
                        StartupMode.ResumeOnOpen -> R.string.settings_startup_resume
                    },
                )
            },
            onSelected = onChanged,
        )
    }
    item { Hint(text = stringResource(R.string.settings_startup_hint)) }
}

/**
 * PRODUCT_SPEC PLAY-004 / ADR-0013 — when a book counts as finished. **A reading, not a control.**
 *
 * ### Why there is nothing to press here
 *
 * The rule is the library's `markAsFinishedTimeRemaining`, read from the server, and this app keeps no
 * competing number. So this section reports what each library actually uses and says where to change it —
 * the Audiobookshelf web interface, per library.
 *
 * The owner asked for the app's value and the server's to match. There is nothing on the server to
 * synchronise a per-listener value *with*: the user object has no settings field at all, and the only
 * writable copy is the library's own configuration, which belongs to the administrator and applies to every
 * account that can see the library. So the two can only match by the app following the server, which is what
 * this is. ADR-0013 records the reasoning, including why the app does not write the library's settings back.
 *
 * ### Why every library is listed, including the ones with no rule
 *
 * Because the alternative is a screen that cannot explain the app's own behaviour. A listener who watches a
 * book finish with a minute left needs to be able to find out that their library asked for a minute. An
 * earlier build listed only libraries that differed from a chosen value, which showed nothing on exactly the
 * server where the explanation was most needed.
 */
private fun LazyListScope.finishedSection(libraries: List<Library>) {
    item { SectionHeader(text = stringResource(R.string.settings_section_finished)) }
    item { Hint(text = stringResource(R.string.settings_finished_hint)) }
    if (libraries.isEmpty()) {
        item { Hint(text = stringResource(R.string.settings_finished_no_libraries)) }
        return
    }
    libraries.forEach { library ->
        item(key = "finished-library-${library.id.value}") {
            val inherited = library.finishedWhenRemaining
            Hint(
                text = if (inherited == null) {
                    stringResource(
                        R.string.settings_finished_inherited_none,
                        library.name,
                        FinishedThreshold.Default.inWholeSeconds.toInt(),
                    )
                } else {
                    stringResource(
                        R.string.settings_finished_inherited,
                        library.name,
                        inherited.inWholeSeconds.toInt(),
                    )
                },
            )
        }
    }
}

/**
 * PRODUCT_SPEC ROUTE-001 / ROUTE-002 — what happens when the phone meets a car.
 *
 * One switch, and it is the only one in this app that can make audio begin with nobody pressing anything.
 * The hint says what the car will show either way, because "ShelfPlayer appears in Android Auto" is a fact a
 * user cannot discover from the phone.
 */
/**
 * PRODUCT_SPEC DL-005 / DL-006 / ADR-0018 decisions 1 and 7 — the two things the app may do unasked.
 *
 * Both off by default, and the hint says so in as many words. One spends storage and possibly data on a
 * book nobody asked for; the other deletes a book somebody did. Each is a reasonable thing to want and a
 * rude thing to assume, and a settings screen that did not say which was which would be inviting a user to
 * turn on the second by accident.
 *
 * The retention row is a chip row rather than a free number: *"there should be a when settings, so delete
 * after x days after finished"* has four sensible answers and a text field would be four taps and a
 * keyboard for the same result.
 *
 * *Remove the previous book* is shown only when smart download is on, because it is defined in terms of it
 * — a switch whose description begins "when the next book arrives" is meaningless when nothing arrives.
 */
private fun LazyListScope.downloadsSection(
    housekeeping: DownloadHousekeeping,
    onChanged: (DownloadHousekeeping) -> Unit,
    onManage: () -> Unit,
) {
    item { SectionHeader(text = stringResource(R.string.settings_section_downloads)) }
    item { Hint(text = stringResource(R.string.settings_downloads_hint)) }
    item {
        TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = stringResource(R.string.settings_downloads_manage))
        }
    }
    item {
        SwitchRow(
            label = stringResource(R.string.settings_downloads_smart),
            checked = housekeeping.smartDownload,
            onCheckedChange = { onChanged(housekeeping.copy(smartDownload = it)) },
        )
    }
    item { Hint(text = stringResource(R.string.settings_downloads_smart_hint)) }
    if (housekeeping.smartDownload) {
        item {
            SwitchRow(
                label = stringResource(R.string.settings_downloads_delete_previous),
                checked = housekeeping.deletePreviousOnSmartDownload,
                onCheckedChange = { onChanged(housekeeping.copy(deletePreviousOnSmartDownload = it)) },
            )
        }
        item { Hint(text = stringResource(R.string.settings_downloads_delete_previous_hint)) }
    }
    item { Hint(text = stringResource(R.string.settings_downloads_retention_hint)) }
    item {
        ChipRow(
            labelRes = R.string.settings_downloads_retention,
            options = DownloadHousekeeping.RetentionDays,
            selected = housekeeping.deleteFinishedAfterDays,
            label = { days -> retentionLabel(days) },
            onSelect = { days -> onChanged(housekeeping.copy(deleteFinishedAfterDays = days)) },
        )
    }
}

@Composable
private fun retentionLabel(days: Int): String = if (days == 0) {
    stringResource(R.string.settings_downloads_retention_never)
} else {
    pluralStringResource(R.plurals.settings_downloads_retention_days, days, days)
}

/**
 * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — what each kind of traffic may spend cellular data on.
 *
 * ### Three switches, not three pickers
 *
 * The owner's words: *"you should be able to turn cellular on for downloads and smart download, but you
 * can't turn off wifi."* Wi-Fi is therefore not on this screen at all — a category with no allowed network
 * is not a preference anybody holds — and each row is the single question that remains.
 *
 * The hint says so out loud, because a screen with three switches and no mention of Wi-Fi invites the
 * reading that Wi-Fi is off.
 *
 * ### Streaming is the one that is on
 *
 * A chapter costs a few megabytes; a book costs hundreds. Somebody pressing play on a train wants it to
 * work, and somebody who taps *Download* on a train usually did not mean to spend a gigabyte. The defaults
 * carry that difference so most people never open this screen.
 */
private fun LazyListScope.networkSection(policy: NetworkPolicy, onChanged: (NetworkPolicy) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_network)) }
    item { Hint(text = stringResource(R.string.settings_network_hint)) }
    item {
        SwitchRow(
            label = stringResource(R.string.settings_network_streaming),
            checked = policy.streamingOnCellular,
            onCheckedChange = { onChanged(policy.copy(streamingOnCellular = it)) },
        )
    }
    item {
        SwitchRow(
            label = stringResource(R.string.settings_network_downloads),
            checked = policy.downloadsOnCellular,
            onCheckedChange = { onChanged(policy.copy(downloadsOnCellular = it)) },
        )
    }
    item {
        SwitchRow(
            label = stringResource(R.string.settings_network_smart_downloads),
            checked = policy.smartDownloadsOnCellular,
            onCheckedChange = { onChanged(policy.copy(smartDownloadsOnCellular = it)) },
        )
    }
}

/**
 * PRODUCT_SPEC ROUTE-002 — what the car does, and where that is now decided.
 *
 * No control of its own any more. This section used to carry a global "start playing when a car connects"
 * switch that bypassed the per-device policy's warning and its `Arm only` default, so a listener who had set
 * their car to *Never react* in the device list could still be auto-played from here. One decision, one
 * place: the car appears in *When a device connects* like every headset and speaker, and the hint says so.
 *
 * The section stays because the rest of it is still true and still worth reading — what the tabs are, and
 * that the car opens on the last book.
 */
/**
 * PRODUCT_SPEC PLAY-002 — *Keep sound in the headset*, immediately above what the car does.
 *
 * The two sections are one story read downwards: this is what happens to the headset when the car in the
 * next section arrives, and putting them apart would leave a listener setting the second and never finding
 * the first.
 *
 * Off by default, so the hint describes what turning it **on** does rather than what the app already does.
 * It also states the limit plainly: the app can express a preference and the platform can decline it, which
 * is the same honesty `AudioOutputRouter` shows the chooser and the reason a car with no headset in it is
 * not a bug report.
 */
private fun LazyListScope.headsetSection(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_headset)) }
    item { Hint(text = stringResource(R.string.settings_headset_hint)) }
    item {
        SwitchRow(
            labelRes = R.string.settings_headset_keep_sound,
            checked = enabled,
            onCheckedChange = onChanged,
        )
    }
}

private fun LazyListScope.carSection() {
    item { SectionHeader(text = stringResource(R.string.settings_section_car)) }
    item { Hint(text = stringResource(R.string.settings_car_hint)) }
}

/**
 * PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — what the tab can change, as one bundle.
 *
 * Four callbacks passed individually push `SettingsScreen` past detekt's parameter limit, and the limit is
 * right: a screen with eleven parameters has an argument order somebody will get wrong. `@Immutable` also
 * lets Compose skip the tab when only the readings moved.
 */
@Immutable
data class PlaybackSettingsActions(
    val onSpeedChanged: (PlaybackSpeed) -> Unit,
    val onSkipsChanged: (SkipIntervals) -> Unit,
    val onAutoRewindChanged: (AutoRewind) -> Unit,
    val onBufferChanged: (BufferPreset) -> Unit,
    /** PRODUCT_SPEC 6.4 step 6 — whether finishing a book starts the next one in its series. */
    val onAutoAdvanceSeriesChanged: (Boolean) -> Unit = {},
    /** PRODUCT_SPEC PLAY-002 — whether a car connecting leaves the book in the headset. */
    val onKeepSoundInHeadsetChanged: (Boolean) -> Unit = {},
    val onFocusBehaviourChanged: (FocusBehaviour) -> Unit = {},
    val onStartupModeChanged: (StartupMode) -> Unit = {},
    /** PRODUCT_SPEC DL-004 — which categories may spend cellular data. */
    val onNetworkPolicyChanged: (NetworkPolicy) -> Unit = {},
    /** PRODUCT_SPEC DL-005 / DL-006 — smart download and the automatic cleanup. */
    val onHousekeepingChanged: (DownloadHousekeeping) -> Unit = {},
    /** PRODUCT_SPEC DL-003 — opens the list of everything downloaded on this device. */
    val onManageDownloads: () -> Unit = {},
)

/** PRODUCT_SPEC PLAY-009 — the four bands, with the requirement's own boundaries as their labels. */
private fun LazyListScope.rewindBands(rewind: AutoRewind, onChanged: (AutoRewind) -> Unit) {
    item {
        ChipRow(
            labelRes = R.string.settings_rewind_short,
            options = AutoRewind.Presets,
            selected = rewind.afterShortPause,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onChanged(rewind.copy(afterShortPause = chosen)) },
        )
    }
    item {
        ChipRow(
            labelRes = R.string.settings_rewind_medium,
            options = AutoRewind.Presets,
            selected = rewind.afterMediumPause,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onChanged(rewind.copy(afterMediumPause = chosen)) },
        )
    }
    item {
        ChipRow(
            labelRes = R.string.settings_rewind_long,
            options = AutoRewind.Presets,
            selected = rewind.afterLongPause,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onChanged(rewind.copy(afterLongPause = chosen)) },
        )
    }
    item {
        ChipRow(
            labelRes = R.string.settings_rewind_very_long,
            options = AutoRewind.Presets,
            selected = rewind.afterVeryLongPause,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = { chosen -> onChanged(rewind.copy(afterVeryLongPause = chosen)) },
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-006 — the presets, each showing what it actually buffers.
 *
 * The numbers are on screen rather than in a help page, because "High" means nothing on its own and the
 * requirement asks the app to say that a larger buffer costs memory and data. Automatic says it has no
 * numbers rather than showing the ones the enum happens to carry, which are Media3's and not ours to claim.
 */
private fun LazyListScope.items(
    presets: List<BufferPreset>,
    selected: BufferPreset,
    onSelect: (BufferPreset) -> Unit,
) {
    presets.forEach { preset ->
        item(key = preset.name) {
            BufferRow(preset = preset, isSelected = preset == selected, onSelect = { onSelect(preset) })
        }
    }
}

@Composable
private fun BufferRow(preset: BufferPreset, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = isSelected, role = Role.RadioButton, onValueChange = { onSelect() })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(WEIGHT_FILL), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(preset.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (preset == BufferPreset.Automatic) {
                    stringResource(R.string.settings_buffer_automatic_range)
                } else {
                    pluralStringResource(
                        R.plurals.settings_buffer_range,
                        preset.maximumBuffer.inWholeSeconds.toInt(),
                        preset.minimumBuffer.inWholeSeconds.toInt(),
                        preset.maximumBuffer.inWholeSeconds.toInt(),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The selection is a word, not only a colour or a filled dot (PRODUCT_SPEC 3.2). `Role.RadioButton`
        // on the row is what announces the state; this is what shows it.
        if (isSelected) {
            Text(
                text = stringResource(R.string.settings_notification_yes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A label with a row of chips under it — the shape every value on this tab uses. */
@Composable
private fun <T> ChipRow(
    labelRes: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text = label(option)) },
                )
            }
        }
    }
}

/**
 * The four sections about *how playback behaves* rather than how it sounds.
 *
 * Grouped so the tab's own body stays readable — and so the reading order is fixed in one place: a car
 * connecting, a book ending, something interrupting, the app opening. Four separate calls in the tab put
 * that order at the mercy of the next person to insert a section in the middle of a sixty-line function.
 */
private fun LazyListScope.behaviourSections(settings: PlaybackSettings, actions: PlaybackSettingsActions) {
    headsetSection(settings.keepSoundInHeadset, actions.onKeepSoundInHeadsetChanged)
    carSection()
    seriesSection(settings.autoAdvanceSeries, actions.onAutoAdvanceSeriesChanged)
    interruptionSection(settings.focusBehaviour, actions.onFocusBehaviourChanged)
    startupSection(settings.startupMode, actions.onStartupModeChanged)
}

/**
 * PRODUCT_SPEC 6.4 step 6 — what happens when a book runs out.
 *
 * **On by default**, which is why the hint says what it does rather than what it would do. This is the
 * second switch in the app that can make audio play without a press, and the difference from the first is
 * the whole reason the defaults differ: a car connecting can start a book in a silent room, while this one
 * only continues audio for somebody already listening. The hint names the case where that is wrong anyway —
 * falling asleep — and points at the control that answers it.
 */
private fun LazyListScope.seriesSection(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_series)) }
    item { Hint(text = stringResource(R.string.settings_series_hint)) }
    item {
        SwitchRow(
            labelRes = R.string.settings_series_auto_advance,
            checked = enabled,
            onCheckedChange = onChanged,
        )
    }
}

@Composable
private fun SwitchRow(
    labelRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        // `null` because the row owns the toggle: a switch with its own handler inside a toggleable row is
        // two targets for one setting, and a screen reader announces it twice.
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun BufferPreset.labelRes(): Int = when (this) {
    BufferPreset.Automatic -> R.string.settings_buffer_automatic
    BufferPreset.Low -> R.string.settings_buffer_low
    BufferPreset.Standard -> R.string.settings_buffer_standard
    BufferPreset.High -> R.string.settings_buffer_high
    BufferPreset.VeryHigh -> R.string.settings_buffer_very_high
}

/** Kotlin cannot see `Modifier.weight` outside a row scope, so the value is named once. */
private const val WEIGHT_FILL = 1f
