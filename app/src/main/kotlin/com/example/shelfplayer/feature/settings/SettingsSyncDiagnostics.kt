package com.example.shelfplayer.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.CarReadiness
import com.example.shelfplayer.core.model.playback.ClockSkew
import com.example.shelfplayer.core.model.playback.NotificationAccess
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SyncTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 / SET-002 — wave 3's readings, under the *Testing* heading.
 *
 * ### Why these are on a screen at all
 *
 * Every acceptance criterion in PLAY-004 and PLAY-005 is about something that happened *between* the app and
 * the server, and none of it is visible from either side alone. "Progress reaches the server within thirty
 * seconds" and "the app never sent it" look identical on a phone; "the queue drained" and "the queue was
 * silently discarded" look identical too. The alternative to these rows is `adb logcat` and a database shell,
 * which needs a computer, a cable and a build nobody is testing.
 *
 * ### What is deliberately not here
 *
 * No book titles and no server address. The rows exist to show that listening is queued and accepted, and
 * naming the books would not make that more convincing — it would only make a screenshot in a bug report
 * carry someone's library (PRODUCT_SPEC 14.5).
 */
internal fun LazyListScope.sessionSyncRows(sync: SessionSyncDiagnostics) {
    item { SubHeader(text = stringResource(R.string.settings_section_sync)) }
    item { Hint(text = stringResource(R.string.settings_sync_body)) }
    item {
        CountRow(
            labelRes = R.string.settings_sync_sessions,
            value = sync.sessionsRecorded,
            hintRes = R.string.settings_sync_sessions_hint,
        )
    }
    item {
        CountRow(
            labelRes = R.string.settings_sync_pending,
            value = sync.sessionsPending,
            hintRes = R.string.settings_sync_pending_hint,
        )
    }
    item { CountRow(labelRes = R.string.settings_sync_open, value = sync.sessionsOpen) }
    item { CountRow(labelRes = R.string.settings_sync_uploaded, value = sync.sessionsSynced) }
    item {
        CountRow(
            labelRes = R.string.settings_sync_declined,
            value = sync.progressDeclined,
            hintRes = R.string.settings_sync_declined_hint,
        )
    }
    item {
        TextRow(
            labelRes = R.string.settings_sync_last,
            value = sync.lastSyncedAt?.asClockTime() ?: stringResource(R.string.settings_sync_last_never),
        )
    }
    sync.lastTrigger?.let { trigger ->
        item { TextRow(labelRes = R.string.settings_sync_trigger, value = stringResource(trigger.labelRes())) }
    }
    item {
        TextRow(
            labelRes = R.string.settings_sync_failure,
            value = sync.lastFailureCode ?: stringResource(R.string.settings_sync_failure_none),
        )
    }
    item { ClockSkewRow(skew = sync.clockSkew) }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/**
 * PRODUCT_SPEC PLAY-001 — whether the media notification can appear, and whether it has.
 *
 * Three rows because there are three causes with three different fixes, and from inside the app they are
 * indistinguishable without asking the platform: the runtime permission was declined, the media channel was
 * silenced, or the notification was never posted. A device run reported a playing book with no notification
 * and no way to tell which of those it was — that round trip is what these rows remove.
 *
 * The button appears only when something is actually blocking it. A permanent "open notification settings"
 * row on a working build is a control that invites the user to break something.
 */
internal fun LazyListScope.notificationRows(access: NotificationAccess) {
    item { SubHeader(text = stringResource(R.string.settings_section_notification)) }
    item { Hint(text = stringResource(R.string.settings_notification_body)) }
    item {
        YesNoRow(
            labelRes = R.string.settings_notification_allowed,
            value = access.isAllowed,
            hintRes = R.string.settings_notification_allowed_hint,
        )
    }
    item {
        TextRow(
            labelRes = R.string.settings_notification_channel,
            value = stringResource(
                when {
                    access.isChannelBlocked -> R.string.settings_notification_channel_off
                    access.isAllowed -> R.string.settings_notification_channel_on
                    else -> R.string.settings_notification_channel_absent
                },
            ),
        )
    }
    item {
        YesNoRow(
            labelRes = R.string.settings_notification_showing,
            value = access.isShowing,
            hintRes = R.string.settings_notification_showing_hint,
        )
    }
    if (access.isBlocked) {
        item { OpenNotificationSettingsButton() }
    }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — the checks wave 3 has to pass, with what to do for each.
 *
 * Three states rather than two. A check the app can see the evidence for is marked [SyncCheckState.Seen]; one
 * whose evidence has not appeared yet is [SyncCheckState.Waiting]; and one that no reading on this device can
 * ever settle — a second phone, a killed process, a server's own record — is [SyncCheckState.NeedsDevice] and
 * says so rather than sitting unticked forever. A checklist that cannot distinguish "not done" from "not
 * checkable here" is a checklist that reads as failing.
 */
internal fun LazyListScope.syncCheckRows(sync: SessionSyncDiagnostics, access: NotificationAccess, car: CarReadiness) {
    item { SubHeader(text = stringResource(R.string.settings_section_checks)) }
    item { Hint(text = stringResource(R.string.settings_checks_body)) }
    items(checksFor(sync) + checkFor(access) + checkFor(car), key = { it.labelRes }) { check ->
        SyncCheckRow(check)
    }
}

/**
 * @property state whether this device has already seen the evidence.
 */
internal data class SyncCheck(val labelRes: Int, val state: SyncCheckState)

internal enum class SyncCheckState { Seen, Waiting, NeedsDevice }

/**
 * The checklist, derived from the readings rather than stored.
 *
 * Each verdict names the *evidence*, and only evidence this process actually holds counts. "The queue drained"
 * is `Seen` when something has been accepted and nothing is waiting — which is exactly what a drained queue
 * looks like, and is false the moment either half stops being true.
 */
internal fun checksFor(sync: SessionSyncDiagnostics): List<SyncCheck> = listOf(
    SyncCheck(
        labelRes = R.string.settings_check_queue_drains,
        state = when {
            sync.sessionsSynced > 0 && sync.sessionsPending == 0 -> SyncCheckState.Seen
            else -> SyncCheckState.Waiting
        },
    ),
    SyncCheck(
        labelRes = R.string.settings_check_server_accepts,
        state = if (sync.lastSyncedAt != null) SyncCheckState.Seen else SyncCheckState.Waiting,
    ),
    SyncCheck(
        labelRes = R.string.settings_check_offline_queue,
        // A row waiting to upload while none has ever been accepted is what an offline session looks like. It
        // is also what a first play looks like before the first sync lands, so this is honest rather than
        // conclusive — hence the wording, which asks for the *rise* to be watched.
        state = if (sync.sessionsPending > 0) SyncCheckState.Seen else SyncCheckState.Waiting,
    ),
    SyncCheck(
        labelRes = R.string.settings_check_offline_drains,
        state = when {
            sync.sessionsSynced > 0 && sync.lastFailureCode == null -> SyncCheckState.Seen
            else -> SyncCheckState.Waiting
        },
    ),
    // The three below cannot be settled from inside this process, and saying so is the point.
    SyncCheck(labelRes = R.string.settings_check_kill, state = SyncCheckState.NeedsDevice),
    SyncCheck(labelRes = R.string.settings_check_rewind, state = SyncCheckState.NeedsDevice),
    SyncCheck(
        labelRes = R.string.settings_check_clock,
        state = if (sync.clockSkew?.isSignificant == true) SyncCheckState.Seen else SyncCheckState.NeedsDevice,
    ),
    SyncCheck(labelRes = R.string.settings_check_second_device, state = SyncCheckState.NeedsDevice),
)

/**
 * PRODUCT_SPEC PLAY-001 — the notification check, judged by whether one is posted.
 *
 * Separate from [checksFor] because its evidence is the device's notification state rather than the outbox,
 * and folding it in would make the wave-3 list depend on something wave 3 did not build. `Seen` means the app
 * observed its own notification, which is as close to the requirement as a reading can get; anything else
 * needs somebody to pull the shade down, because a notification that is posted and invisible is still a
 * failure.
 */
internal fun checkFor(access: NotificationAccess): SyncCheck = SyncCheck(
    labelRes = R.string.settings_check_notification,
    state = if (access.isShowing) SyncCheckState.Seen else SyncCheckState.NeedsDevice,
)

/**
 * PRODUCT_SPEC PLAY-001 / ROUTE-002 — the Android Auto check, judged by whether a car ever bound.
 *
 * `Seen` needs a real connection, not a correct manifest. Two device runs found that an app can declare
 * everything the platform asks for and still be missing from the dashboard, so a check that ticked on the
 * declaration would have reported success through both of them. A car reaching the session is the only
 * evidence that means anything here.
 */
internal fun checkFor(car: CarReadiness): SyncCheck = SyncCheck(
    labelRes = R.string.settings_check_car,
    state = if (car.lastConnectedAt != null) SyncCheckState.Seen else SyncCheckState.NeedsDevice,
)

/**
 * PRODUCT_SPEC PLAY-005 — the skew, and whether it is large enough to matter.
 *
 * The warning wording says what the consequence is rather than that a number is large: a listener does not
 * need to know what five minutes of skew means, they need to know their other device is about to be
 * overwritten.
 */
@Composable
private fun ClockSkewRow(skew: ClockSkew?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = stringResource(R.string.settings_sync_clock), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = when {
                skew == null -> stringResource(R.string.settings_sync_clock_unknown)
                skew.isSignificant -> stringResource(R.string.settings_sync_clock_warning, skew.offset.asOffset())
                else -> stringResource(R.string.settings_sync_clock_ok, skew.offset.asOffset())
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (skew?.isSignificant == true) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * One check.
 *
 * The icon and the colour both carry the state, and neither is the only carrier: the content description says
 * it in words, because a colour-only distinction fails the accessibility rule PRODUCT_SPEC 3.2 sets.
 */
@Composable
private fun SyncCheckRow(check: SyncCheck, modifier: Modifier = Modifier) {
    val stateRes = check.state.labelRes()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = check.state.icon(),
            contentDescription = stringResource(stateRes),
            tint = when (check.state) {
                SyncCheckState.Seen -> MaterialTheme.colorScheme.primary
                SyncCheckState.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
                SyncCheckState.NeedsDevice -> MaterialTheme.colorScheme.tertiary
            },
        )
        Column(modifier = Modifier.weight(WEIGHT_FILL), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(check.labelRes), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(stateRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * PRODUCT_SPEC 3.2 — yes/no as words, not as a colour or a tick alone.
 *
 * The value carries the meaning for a screen reader as well as for a glance, which a coloured icon does not.
 */
@Composable
internal fun YesNoRow(labelRes: Int, value: Boolean, modifier: Modifier = Modifier, hintRes: Int? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(WEIGHT_FILL), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            hintRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(if (value) R.string.settings_notification_yes else R.string.settings_notification_no),
            style = MaterialTheme.typography.titleMedium,
            color = if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The one action on this screen, and it leaves the app.
 *
 * The permission cannot be re-requested once declined — `launch` silently does nothing — so the system
 * settings page is the only route back. Deep-linked to *this app's* notification settings rather than to the
 * general list, because a user who has to find the app in a list of two hundred will not.
 */
@Composable
private fun OpenNotificationSettingsButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        modifier = modifier.padding(horizontal = 8.dp),
    ) {
        Text(text = stringResource(R.string.settings_notification_open))
    }
}

@Composable
private fun CountRow(labelRes: Int, value: Int, modifier: Modifier = Modifier, hintRes: Int? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(WEIGHT_FILL), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            hintRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun SyncCheckState.labelRes(): Int = when (this) {
    SyncCheckState.Seen -> R.string.settings_check_state_pass
    SyncCheckState.Waiting -> R.string.settings_check_state_waiting
    SyncCheckState.NeedsDevice -> R.string.settings_check_state_manual
}

private fun SyncCheckState.icon(): ImageVector = when (this) {
    SyncCheckState.Seen -> Icons.Filled.CheckCircle
    SyncCheckState.Waiting -> Icons.Outlined.RadioButtonUnchecked
    SyncCheckState.NeedsDevice -> Icons.Outlined.PhoneAndroid
}

private fun SyncTrigger.labelRes(): Int = when (this) {
    SyncTrigger.Interval -> R.string.settings_sync_trigger_interval
    SyncTrigger.Paused -> R.string.settings_sync_trigger_paused
    SyncTrigger.SeekCompleted -> R.string.settings_sync_trigger_seek
    SyncTrigger.ChapterChanged -> R.string.settings_sync_trigger_chapter
    SyncTrigger.TrackChanged -> R.string.settings_sync_trigger_track
    SyncTrigger.BookChanged -> R.string.settings_sync_trigger_book
    SyncTrigger.SleepTimerStopped -> R.string.settings_sync_trigger_timer
    SyncTrigger.ServiceShutdown -> R.string.settings_sync_trigger_shutdown
    SyncTrigger.AppBackgrounded -> R.string.settings_sync_trigger_background
}

/**
 * A wall clock, in the device's own zone.
 *
 * A time rather than "2 minutes ago". The reading is checked against a stopwatch during a device test, and a
 * relative label that only changes once a minute cannot be — which is the same mistake the sleep timer's
 * minutes-only countdown made.
 */
internal fun Instant.asClockTime(): String = TIME_FORMAT.withZone(ZoneId.systemDefault()).format(this)

/** Signed, because the sign is the interesting part: `+` means this device is behind the server. */
private fun Duration.asOffset(): String {
    val seconds = inWholeSeconds
    val sign = if (seconds < 0) "−" else "+"
    val magnitude = seconds.absoluteValue
    return when {
        magnitude < SECONDS_PER_MINUTE -> "$sign${magnitude}s"
        else -> "$sign${magnitude / SECONDS_PER_MINUTE}m ${magnitude % SECONDS_PER_MINUTE}s"
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private const val SECONDS_PER_MINUTE = 60L

private const val WEIGHT_FILL = 1f
