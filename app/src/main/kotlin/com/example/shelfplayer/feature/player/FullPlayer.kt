package com.example.shelfplayer.feature.player

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003 — the full-screen player.
 *
 * ### The layout, and why it is this one
 *
 * It follows the shape audiobook players have converged on, and each part earns its place rather than
 * being copied:
 *
 *  - **A tinted ground, not a flat surface.** A vertical wash from the container colour down to the
 *    background gives the artwork something to sit against and stops the screen reading as a form.
 *  - **The artwork is the biggest thing on screen**, because it is how a listener recognises where they
 *    are before reading a word.
 *  - **Title and author are centred beneath it and are not controls.** They label the picture above them.
 *  - **The scrubber sits directly above the transport**, elapsed left, remaining right. That pair is what
 *    a listener actually reads: how far in, and how much is left.
 *  - **Transport is one large primary button flanked by two skips**, and nothing else shares the row. The
 *    play button is deliberately much larger — it is the control pressed in the dark.
 *  - **Secondary actions are a quieter row underneath.** One tap away and visually subordinate, which is
 *    the right weight for controls used once a session rather than once a minute.
 *
 * ### Why an overlay rather than a navigation destination
 *
 * The two forms are one thing in two sizes rather than two screens. As an overlay with a [BackHandler]
 * that collapses it, back shows the bar and the screen underneath keeps its scroll position — which a
 * destination would have to restore. It also means the player cannot end up in the back stack twice,
 * which is the usual bug with a player reachable from several screens.
 */
@Composable
fun FullPlayer(
    state: PlaybackUiState,
    timer: SleepTimerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    skips: SkipControls = SkipControls.Inert,
    isNotificationBlocked: Boolean = false,
) {
    BackHandler(onBack = actions.onCollapse)
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(playerBackground())
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            TopBar(
                timer = timer,
                onCollapse = actions.onCollapse,
                onOpenSleepTimer = actions.onOpenSleepTimer,
            )

            // PRODUCT_SPEC PLAY-001 — the requirement is a notification with transport controls, and on
            // Android 13+ the user can decline it. Saying so here is the difference between a missing
            // notification and a *silently* missing one: the app cannot grant itself the permission, but it can
            // stop pretending the feature is working.
            if (isNotificationBlocked) NotificationBlockedNotice()

            Spacer(modifier = Modifier.height(8.dp))
            PlayerArtwork(state = state, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(28.dp))
            NowPlaying(state = state)

            Spacer(modifier = Modifier.height(4.dp))
            // PRODUCT_SPEC PLAY-003 — which chapter, under the title it belongs to.
            //
            // Rendered as an empty line when a book has no chapters rather than omitted, so the transport
            // does not shift up and down between books.
            Text(
                text = state.currentChapter?.title.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(WEIGHT_FILL))
            SeekBar(state = state, onSeekTo = actions.onSeekTo)

            Spacer(modifier = Modifier.height(8.dp))
            TransportRow(state = state, skips = skips, onTogglePlayPause = actions.onTogglePlayPause)

            Spacer(modifier = Modifier.height(12.dp))
            SecondaryRow(
                state = state,
                timer = timer,
                onOpenSleepTimer = actions.onOpenSleepTimer,
                onOpenChapters = actions.onOpenChapters,
                onOpenSpeed = actions.onOpenSpeed,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * The wash behind the player.
 *
 * A gradient rather than a colour sampled from the cover. Sampling is what these players usually do and
 * it is a bad trade: it needs the bitmap decoded before the first frame, and it produces unpredictable
 * contrast against the text on top of it, which PRODUCT_SPEC 21's contrast requirement cannot promise for
 * an arbitrary book. The theme's own container colour gives the same lift with none of that.
 */
@Composable
private fun playerBackground(): Brush = Brush.verticalGradient(
    colors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.surface,
    ),
)

/**
 * PRODUCT_SPEC PLAY-001 — one line and one action, shown only while something is blocking the notification.
 *
 * The action leaves the app, because the runtime permission cannot be asked for twice: once declined,
 * `launch` silently does nothing, and the system settings page is the only route back. Deep-linked to this
 * app's own page rather than the general list — a user who has to find the app among two hundred will not.
 */
@Composable
private fun NotificationBlockedNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_notifications_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        ) {
            Text(text = stringResource(R.string.player_notifications_fix))
        }
    }
}

/**
 * PRODUCT_SPEC PLAY-007 — the speed control, showing the speed.
 *
 * The number rather than the glyph alone, and coloured when it is not 1.0×. A listener who left a book at
 * 2× three weeks ago and comes back to it needs to be told, not asked to remember; the icon on its own
 * looks identical either way.
 */
@Composable
private fun SpeedAction(speed: PlaybackSpeed, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.player_speed_value, speed.label())
    IconButton(onClick = onClick, modifier = modifier) {
        if (speed.isDefault) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TopBar(
    timer: SleepTimerState,
    onCollapse: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) {
            Icon(
                // A chevron down, not a back arrow: the gesture puts the player away into the bar rather
                // than returning to some previous screen.
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_collapse),
            )
        }
        Spacer(modifier = Modifier.weight(WEIGHT_FILL))
        SleepTimerReadout(timer = timer, onClick = onOpenSleepTimer)
    }
}

/**
 * The artwork, and the empty square that stands in for it.
 *
 * The square is drawn whether or not there is anything to put in it, so the layout does not move when the
 * image arrives. `contentDescription` is null because the title is directly beneath it — the same
 * reasoning `BookCover` records.
 */
@Composable
private fun PlayerArtwork(state: PlaybackUiState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(max = ARTWORK_MAX)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val uri = state.artworkUri
        if (uri == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        } else {
            // The same Coil loader the shelves use, so the artwork goes over the authenticated client.
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NowPlaying(state: PlaybackUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = state.author.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-003 — the position bar, with a draggable dot.
 *
 * `dragged` is the whole trick. Without it the ticker's next emission overwrites the thumb mid-gesture
 * and the dot springs back under the finger; with it the bar is the drag's own state until the finger
 * lifts, and only then does a seek happen — once, rather than on every pixel of the drag.
 *
 * Elapsed on the left, **remaining** on the right rather than the total. A listener checking a player
 * wants to know whether to keep going; the book's length is on its detail screen.
 */
@Composable
private fun SeekBar(state: PlaybackUiState, onSeekTo: (Duration) -> Unit, modifier: Modifier = Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val inProgress = dragged
    val totalMs = state.duration.inWholeMilliseconds
    val shownPosition = if (inProgress == null) {
        state.position
    } else {
        (inProgress * totalMs).toLong().milliseconds
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = (inProgress ?: state.fractionComplete).coerceIn(0f, 1f),
            onValueChange = { value -> dragged = value },
            onValueChangeFinished = {
                dragged?.let { value -> onSeekTo((value * totalMs).toLong().milliseconds) }
                dragged = null
            },
            // A book whose duration is not known yet cannot be seeked in, and a bar that moves but does
            // nothing is worse than one that is plainly not ready.
            enabled = totalMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TimeLabel(text = shownPosition.asChapterClock())
            Spacer(modifier = Modifier.weight(WEIGHT_FILL))
            TimeLabel(
                text = stringResource(
                    R.string.player_remaining,
                    (state.duration - shownPosition).coerceAtLeast(Duration.ZERO).asChapterClock(),
                ),
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** PRODUCT_SPEC PLAY-001 / PLAY-007 — the row used once a minute, and nothing else in it. */
@Composable
private fun TransportRow(
    state: PlaybackUiState,
    skips: SkipControls,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backSeconds = skips.intervals.back.inWholeSeconds.toInt()
    val forwardSeconds = skips.intervals.forward.inWholeSeconds.toInt()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(
            // PRODUCT_SPEC PLAY-007 — the glyph follows the interval, and drops its number rather than
            // printing a wrong one. See `SkipIcons`.
            icon = SkipIcons.back(skips.intervals.back),
            description = pluralStringResource(R.plurals.player_skip_back, backSeconds, backSeconds),
            onClick = skips.onBack,
        )
        PlayPauseButton(state = state, onClick = onTogglePlayPause)
        SkipButton(
            icon = SkipIcons.forward(skips.intervals.forward),
            description = pluralStringResource(R.plurals.player_skip_forward, forwardSeconds, forwardSeconds),
            onClick = skips.onForward,
        )
    }
}

/**
 * A filled circle, drawn rather than assembled from a filled-button component.
 *
 * `FilledIconButton` sizes itself to the Material touch target and will not grow to
 * [PLAY_BUTTON_SIZE], so the circle is the button's own background. The `IconButton` still supplies the
 * role and the ripple, so a screen reader and a finger both see a button.
 */
@Composable
private fun PlayPauseButton(state: PlaybackUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = when {
        state.isLoading -> stringResource(R.string.player_starting)
        state.isPlaying -> stringResource(R.string.player_pause)
        else -> stringResource(R.string.player_resume)
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(PLAY_BUTTON_SIZE)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primary),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = label },
            )
        } else {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(56.dp)) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(34.dp))
    }
}

/**
 * The controls used once a session rather than once a minute.
 *
 * Bookmark is still a **disabled placeholder** and says so in its content description rather than looking
 * live — the row is the shape the player will keep, and a slot that admits it does nothing beats one that
 * silently does nothing (PRODUCT_SPEC 21). Speed became real in wave 4 and now shows its own value, because
 * "1.5×" on the button is the fastest way to answer "why does this sound odd".
 */
@Composable
private fun SecondaryRow(
    state: PlaybackUiState,
    timer: SleepTimerState,
    onOpenSleepTimer: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedAction(speed = state.speed, onClick = onOpenSpeed)
        SecondaryAction(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            description = stringResource(R.string.player_chapters),
            // PRODUCT_SPEC LIB-004 — disabled on a book with no chapter metadata, which is common in a
            // self-hosted library. A sheet that opens onto nothing is worse than a control that is
            // plainly unavailable.
            enabled = state.chapters.isNotEmpty(),
            onClick = onOpenChapters,
        )
        SecondaryAction(
            icon = Icons.Filled.Bookmark,
            description = stringResource(R.string.player_bookmark_later),
            enabled = false,
            onClick = {},
        )
        SecondaryAction(
            icon = Icons.Filled.Bedtime,
            description = if (timer.isActive) {
                stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
            } else {
                stringResource(R.string.sleep_timer_open)
            },
            enabled = true,
            onClick = onOpenSleepTimer,
        )
    }
}

@Composable
private fun SecondaryAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-008 — the countdown, at the top, when one is running.
 *
 * The *readout* rather than the control: the button in the secondary row is how a timer is set, and this
 * is where a listener glances to see how long is left. It renders nothing when no timer is running, so the
 * top bar is empty rather than carrying a second way to open the same sheet.
 */
@Composable
private fun SleepTimerReadout(timer: SleepTimerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!timer.isActive) return
    val activeLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
    IconButton(onClick = onClick, modifier = modifier) {
        Text(
            text = timer.remaining.asCountdownLabel(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = activeLabel },
        )
    }
}

private const val WEIGHT_FILL = 1f

/** Big enough to press without looking, which is the whole point of the hierarchy in the transport row. */
private val PLAY_BUTTON_SIZE = 88.dp

/** Keeps the artwork sane on a tablet, where a full-width square cover would be enormous. */
private val ARTWORK_MAX = 420.dp
