package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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

/** Sleep timer defaults and history in one compact card, without explanatory copy. */
internal fun LazyListScope.sleepTimerTab(
    settings: SleepTimerSettings,
    history: List<SleepTimerSession>,
    actions: SleepTimerSettingsActions,
) {
    item { SectionHeader(text = stringResource(R.string.settings_group_sleep_timer)) }
    item {
        SettingsCard {
            InlineChoiceRow(
                label = stringResource(R.string.sleep_timer_default_length),
                options = SleepTimerSettings.Presets,
                selected = settings.defaultLength,
                labelOf = { duration -> minutesLabel(duration) },
                onSelected = actions.onDefaultChanged,
            )
            InlineChoiceRow(
                label = stringResource(R.string.sleep_timer_fade),
                options = FADE_OPTIONS,
                selected = settings.fadeLength,
                labelOf = { duration -> secondsOrOffLabel(duration) },
                onSelected = actions.onFadeChanged,
            )
            InlineChoiceRow(
                label = stringResource(R.string.sleep_timer_rewind),
                options = SleepTimerSettings.RewindOnStopPresets,
                selected = settings.rewindOnStop,
                labelOf = { duration -> minutesOrOffLabel(duration) },
                onSelected = actions.onRewindOnStopChanged,
            )
            SwitchRow(
                label = stringResource(R.string.sleep_timer_shake),
                checked = settings.shakeToRestart,
                onCheckedChange = actions.onShakeChanged,
            )
            if (history.isNotEmpty()) {
                ExpandableSettingsRow(
                    label = stringResource(R.string.sleep_timer_history),
                    valueLabel = historyCountLabel(history.size),
                ) {
                    history.forEach { session -> SleepTimerHistoryRow(session) }
                }
            }
        }
    }
}

private val FADE_OPTIONS: List<Duration> = listOf(Duration.ZERO) + listOf(5, 10, 15, 20, 30).map { it.seconds }

@Composable
private fun minutesLabel(duration: Duration): String = stringResource(
    R.string.sleep_timer_minutes,
    duration.inWholeMinutes.toInt(),
)

@Composable
private fun minutesOrOffLabel(duration: Duration): String = if (duration <= Duration.ZERO) {
    stringResource(R.string.sleep_timer_off)
} else {
    minutesLabel(duration)
}

@Composable
private fun secondsOrOffLabel(duration: Duration): String = if (duration <= Duration.ZERO) {
    stringResource(R.string.sleep_timer_off)
} else {
    stringResource(R.string.sleep_timer_seconds, duration.inWholeSeconds.toInt())
}

@Composable
private fun historyCountLabel(count: Int): String = pluralStringResource(
    R.plurals.settings_sleep_history_count,
    count,
    count,
)

@Composable
private fun SleepTimerHistoryRow(session: SleepTimerSession, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.material3.Text(
            text = stringResource(
                R.string.sleep_timer_history_started,
                session.startedAt.asDateTime(),
                session.mode.label(),
            ),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.Text(
            text = session.outcome.label(),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        session.endedAt?.let { endedAt ->
            androidx.compose.material3.Text(
                text = stringResource(
                    R.string.sleep_timer_history_lasted,
                    JavaDuration.between(session.startedAt, endedAt).toKotlinDuration().asHistoryLabel(),
                ),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.restarts > 0) {
            androidx.compose.material3.Text(
                text = pluralStringResource(
                    R.plurals.sleep_timer_history_restarts,
                    session.restarts,
                    session.restarts,
                ),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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

@Immutable
data class SleepTimerSettingsActions(
    val onDefaultChanged: (Duration) -> Unit,
    val onFadeChanged: (Duration) -> Unit,
    val onShakeChanged: (Boolean) -> Unit,
    val onRewindOnStopChanged: (Duration) -> Unit,
)
