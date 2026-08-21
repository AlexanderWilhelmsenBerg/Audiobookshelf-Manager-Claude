package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    actions: SleepTimerSettingsActions,
) {
    item { SectionHeader(text = stringResource(R.string.sleep_timer_settings)) }
    item {
        DurationChoice(
            label = stringResource(R.string.sleep_timer_default_length),
            options = SleepTimerSettings.Presets,
            selected = settings.defaultLength,
            onSelected = actions.onDefaultChanged,
            format = { it.inWholeMinutes.toInt() to R.string.sleep_timer_minutes },
        )
    }
    item {
        DurationChoice(
            label = stringResource(R.string.sleep_timer_fade),
            options = FADE_OPTIONS,
            selected = settings.fadeLength,
            onSelected = actions.onFadeChanged,
            format = { it.inWholeSeconds.toInt() to R.string.sleep_timer_seconds },
        )
    }
    // PRODUCT_SPEC PLAY-008 / PLAY-009 — the owner asked for this by example: *"if I set on a sleep timer I
    // can set rewind time for five minutes and it will rewind five minutes"*. Under the fade, because both
    // are about what happens as the timer ends.
    item {
        DurationChoice(
            label = stringResource(R.string.sleep_timer_rewind),
            options = SleepTimerSettings.RewindOnStopPresets,
            selected = settings.rewindOnStop,
            onSelected = actions.onRewindOnStopChanged,
            format = { it.inWholeMinutes.toInt() to R.string.sleep_timer_minutes },
        )
    }
    item { Hint(text = stringResource(R.string.sleep_timer_rewind_hint)) }
    item {
        SwitchRow(
            label = stringResource(R.string.sleep_timer_shake),
            checked = settings.shakeToRestart,
            onCheckedChange = actions.onShakeChanged,
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
 * PLAY-008: "**optional** fade-out occurs over 5–30 seconds".
 *
 * Five discrete choices rather than a slider. A slider over a 25-second range gives a listener the
 * ability to pick 17 seconds, which is not a decision anyone has an opinion about, and makes the value
 * impossible to hit twice.
 *
 * `Duration.ZERO` leads, and it is the word *optional* in the requirement. The first build had no way to
 * decline the fade at all — the chips started at five seconds, so a timer always faded — which a device run
 * noticed as a missing setting rather than as a missing option.
 */
private val FADE_OPTIONS: List<Duration> = listOf(Duration.ZERO) + listOf(5, 10, 15, 20, 30).map { it.seconds }

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
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = {
                        // Zero is not "0 min", it is "Off". Every chooser here that offers zero means the
                        // same thing by it, so the word lives in one place rather than in each caller.
                        if (option <= Duration.ZERO) {
                            Text(text = stringResource(R.string.sleep_timer_off))
                        } else {
                            val (amount, res) = format(option)
                            Text(text = stringResource(res, amount))
                        }
                    },
                )
            }
        }
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

/**
 * PRODUCT_SPEC SET-002 — what the Sleep tab can change, as one parameter.
 *
 * The same shape `PlaybackSettingsActions` uses, and it arrived for the same reason: the tab grew a fourth
 * callback and pushed `SettingsScreen`'s signature past detekt's limit. Bundling them also means the screen
 * passes one object rather than four lambdas it does nothing with but forward.
 *
 * Public because `SettingsScreen` is.
 */
@Immutable
data class SleepTimerSettingsActions(
    val onDefaultChanged: (Duration) -> Unit,
    val onFadeChanged: (Duration) -> Unit,
    val onShakeChanged: (Boolean) -> Unit,
    /** PRODUCT_SPEC PLAY-008 / PLAY-009 — how far to rewind when the timer stops the book. Zero is off. */
    val onRewindOnStopChanged: (Duration) -> Unit,
)
