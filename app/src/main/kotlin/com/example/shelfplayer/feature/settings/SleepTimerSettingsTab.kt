package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.feature.player.label
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — the sleep timer's defaults, and what it has actually done.
 *
 * ### Why the history is on the settings screen
 *
 * The question a sleep-timer history answers is "did it work?", and that question is asked in the
 * morning, on the settings screen, next to the switch that might be the reason it did not. Putting it
 * behind its own destination would separate the evidence from the control it is evidence about.
 *
 * ### What it says about a book
 *
 * Nothing. A row names when a timer ran, how it was set, and how it ended. Not the book. That is
 * PRODUCT_SPEC 14.5 applied to the app's own screens rather than only to its logs: a screenshot of this
 * tab, which is exactly what somebody would send with a bug report, reveals nothing about what they
 * were listening to.
 */
internal fun LazyListScope.sleepTimerTab(
    settings: SleepTimerSettings,
    history: List<SleepTimerSession>,
    onDefaultChanged: (Duration) -> Unit,
    onFadeChanged: (Duration) -> Unit,
    onShakeChanged: (Boolean) -> Unit,
) {
    item { SectionHeader(text = stringResource(R.string.sleep_timer_settings)) }
    item {
        DurationChoice(
            label = stringResource(R.string.sleep_timer_default_length),
            options = SleepTimerSettings.Presets,
            selected = settings.defaultLength,
            onSelected = onDefaultChanged,
            format = { it.inWholeMinutes.toInt() to R.string.sleep_timer_minutes },
        )
    }
    item {
        DurationChoice(
            label = stringResource(R.string.sleep_timer_fade),
            options = FADE_OPTIONS,
            selected = settings.fadeLength,
            onSelected = onFadeChanged,
            format = { it.inWholeSeconds.toInt() to R.string.sleep_timer_seconds },
        )
    }
    item {
        SwitchRow(
            label = stringResource(R.string.sleep_timer_shake),
            checked = settings.shakeToRestart,
            onCheckedChange = onShakeChanged,
        )
    }
    item { Hint(text = stringResource(R.string.sleep_timer_shake_hint)) }

    item { SectionHeader(text = stringResource(R.string.sleep_timer_history)) }
    item { Hint(text = stringResource(R.string.sleep_timer_history_hint)) }
    if (history.isEmpty()) {
        item { Hint(text = stringResource(R.string.sleep_timer_history_empty)) }
    } else {
        items(history, key = { it.id }) { session -> SleepTimerHistoryRow(session) }
    }
}

/**
 * PLAY-008: "optional fade-out occurs over 5–30 seconds".
 *
 * Five discrete choices rather than a slider. A slider over a 25-second range gives a listener the
 * ability to pick 17 seconds, which is not a decision anyone has an opinion about, and makes the value
 * impossible to hit twice.
 */
private val FADE_OPTIONS: List<Duration> = listOf(5, 10, 15, 20, 30).map { it.seconds }

@Composable
private fun DurationChoice(
    label: String,
    options: List<Duration>,
    selected: Duration,
    onSelected: (Duration) -> Unit,
    format: (Duration) -> Pair<Int, Int>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val (amount, res) = format(option)
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(text = stringResource(res, amount)) },
                )
            }
        }
    }
}

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
        // `null` rather than a second handler: the whole row is the toggle, and a switch with its own
        // click target inside a toggleable row is two controls a screen reader has to describe.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SleepTimerHistoryRow(session: SleepTimerSession, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(
                R.string.sleep_timer_history_started,
                session.startedAt.asDateTime(),
                session.mode.label(),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = session.outcome.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        session.endedAt?.let { endedAt ->
            Text(
                text = stringResource(
                    R.string.sleep_timer_history_lasted,
                    JavaDuration.between(session.startedAt, endedAt).toKotlinDuration().asHistoryLabel(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.restarts > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.sleep_timer_history_restarts,
                    session.restarts,
                    session.restarts,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Minutes, or seconds for a timer that barely ran — a cancelled one, or a very short chapter. */
@Composable
private fun Duration.asHistoryLabel(): String {
    val seconds = inWholeSeconds.coerceAtLeast(0)
    return if (seconds < SECONDS_PER_MINUTE) {
        stringResource(R.string.sleep_timer_seconds, seconds.toInt())
    } else {
        stringResource(R.string.sleep_timer_minutes, (seconds / SECONDS_PER_MINUTE).toInt())
    }
}

private const val SECONDS_PER_MINUTE = 60L

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

private fun Instant.asDateTime(): String = DATE_TIME_FORMAT.format(this)
