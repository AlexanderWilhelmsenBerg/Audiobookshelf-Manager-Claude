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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FinishedThreshold
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import kotlin.time.Duration

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

    finishedSection(settings.finishedThreshold, libraries, actions.onFinishedThresholdChanged)
    carSection(settings.autoPlayOnCarConnect, actions.onAutoPlayChanged)
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) }
}

/**
 * PRODUCT_SPEC PLAY-004 / ADR-0013 — when a book counts as finished.
 *
 * ### Why every library is listed
 *
 * The value here is a **fallback**: where a library on the server sets `markAsFinishedTimeRemaining`, that is
 * the number used for its books. A setting that is silently overruled is a setting that lies, so each library
 * says what it actually uses — the inherited seconds, or that it has none and follows the chips above.
 *
 * Every library rather than only the overriding ones. An earlier build of this listed only the libraries that
 * differed from the chosen value, which reads as "nothing to see" on exactly the server where the chips are
 * doing nothing at all.
 */
private fun LazyListScope.finishedSection(
    threshold: Duration,
    libraries: List<Library>,
    onChanged: (Duration) -> Unit,
) {
    item { SectionHeader(text = stringResource(R.string.settings_section_finished)) }
    item { Hint(text = stringResource(R.string.settings_finished_hint)) }
    item {
        ChipRow(
            labelRes = R.string.settings_finished_threshold,
            options = FinishedThreshold.Presets,
            selected = threshold,
            label = { seconds -> stringResource(R.string.settings_seconds, seconds.inWholeSeconds.toInt()) },
            onSelect = onChanged,
            // The only test tag in the app, and it earns its place: three chip rows on this tab offer the
            // *same* eight labels — "5 s" through "120 s" — because the skip intervals and this threshold
            // share a range on purpose. A test asserting that a listener can press this row's 90 therefore
            // cannot name it by its text, and PR 1 of this closeout is the record of what happens when a
            // control goes untested for pressability.
            modifier = Modifier.testTag(FINISHED_THRESHOLD_CHIPS),
        )
    }
    libraries.forEach { library ->
        item(key = "finished-library-${library.id.value}") {
            val inherited = library.finishedWhenRemaining
            Hint(
                text = if (inherited == null) {
                    stringResource(R.string.settings_finished_inherited_none, library.name)
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

/** See [finishedSection]. Named here so the test and the tab cannot drift apart on a string literal. */
internal const val FINISHED_THRESHOLD_CHIPS = "finished-threshold-chips"

/**
 * PRODUCT_SPEC ROUTE-001 / ROUTE-002 — what happens when the phone meets a car.
 *
 * One switch, and it is the only one in this app that can make audio begin with nobody pressing anything.
 * The hint says what the car will show either way, because "ShelfPlayer appears in Android Auto" is a fact a
 * user cannot discover from the phone.
 */
private fun LazyListScope.carSection(autoPlay: Boolean, onAutoPlayChanged: (Boolean) -> Unit) {
    item { SectionHeader(text = stringResource(R.string.settings_section_car)) }
    item { Hint(text = stringResource(R.string.settings_car_hint)) }
    item {
        SwitchRow(
            label = stringResource(R.string.settings_car_autoplay),
            checked = autoPlay,
            onCheckedChange = onAutoPlayChanged,
        )
    }
}

/** A labelled switch. The playback tab's only boolean until the car section arrived; now it has two. */
@Composable
private fun SwitchRow(
    label: String,
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
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
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
    /** PRODUCT_SPEC PLAY-004 — how close to the end counts as finished. */
    val onFinishedThresholdChanged: (Duration) -> Unit,
    /** PRODUCT_SPEC ROUTE-001 / ROUTE-002 — auto-play when a car connects. */
    val onAutoPlayChanged: (Boolean) -> Unit,
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
