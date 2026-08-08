package com.example.shelfplayer.feature.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-003 — the full-screen player.
 *
 * ### Why an overlay rather than a navigation destination
 *
 * "Going back shows the mini player" is the whole requirement, and the two forms are one thing in two
 * sizes rather than two screens. As an overlay with a [BackHandler] that collapses it, back does exactly
 * that and the shelf underneath keeps its scroll position — which a destination would have to restore.
 * It also means the player cannot end up in the back stack twice, which is the usual bug with a player
 * you can reach from several screens.
 *
 * ### The seek bar
 *
 * A [Slider], whose thumb *is* the draggable dot. While a drag is in progress the bar shows the dragged
 * value and ignores the position ticker — otherwise every tick would yank the thumb back under the
 * finger. The seek is committed on release, once, rather than on every pixel of the drag: a seek per
 * frame would ask the player to rebuffer forty times for one gesture.
 */
@Composable
fun FullPlayer(
    state: PlaybackUiState,
    timer: SleepTimerState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Duration) -> Unit,
    onSkipBy: (Duration) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCollapse)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = stringResource(R.string.player_collapse),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                SleepTimerButton(timer = timer, onClick = onOpenSleepTimer)
            }

            PlayerArtwork(state = state, modifier = Modifier.fillMaxWidth())

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall,
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

            SeekBar(state = state, onSeekTo = onSeekTo)

            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onSkipBy = onSkipBy,
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The artwork, and the empty square that stands in for it.
 *
 * The square is drawn whether or not there is anything to put in it, so the layout does not jump when
 * the image arrives. `contentDescription` is null because the title is directly beneath it — the same
 * reasoning `BookCover` records.
 */
@Composable
private fun PlayerArtwork(state: PlaybackUiState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val uri = state.artworkUri
        if (uri == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
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

/**
 * PRODUCT_SPEC PLAY-008 — the timer control, with the countdown as its label when one is running.
 *
 * Shared in spirit with the mini player's, and written twice rather than extracted: the two differ in
 * size, in colour and in what they sit beside, and a single parameterised control would take more
 * arguments than either use needs.
 */
@Composable
private fun SleepTimerButton(timer: SleepTimerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val activeLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
    IconButton(onClick = onClick, modifier = modifier) {
        if (timer.isActive) {
            Text(
                text = timer.remaining.asCountdownLabel(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { contentDescription = activeLabel },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = stringResource(R.string.sleep_timer_open),
            )
        }
    }
}

/**
 * PRODUCT_SPEC PLAY-003 — the position bar, with a draggable dot.
 *
 * `isDragging` is the whole trick. Without it the ticker's next emission overwrites the thumb's position
 * mid-gesture and the dot springs back under the finger; with it the bar is the drag's own state until
 * the finger lifts, and only then does a seek happen.
 */
@Composable
private fun SeekBar(state: PlaybackUiState, onSeekTo: (Duration) -> Unit, modifier: Modifier = Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val inProgress = dragged
    val totalMs = state.duration.inWholeMilliseconds
    val fraction = inProgress ?: state.fractionComplete
    val shownPosition = if (inProgress == null) {
        state.position
    } else {
        (inProgress * totalMs).toLong().milliseconds
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
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
            Text(
                text = shownPosition.asClockLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                // What is left, not the total: a listener checking a player at night wants to know
                // whether to keep going, and the book's length is on its detail screen.
                text = stringResource(
                    R.string.player_remaining,
                    (state.duration - shownPosition).coerceAtLeast(Duration.ZERO).asClockLabel(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportRow(
    state: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onSkipBy: (Duration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(
            icon = Icons.Filled.Replay30,
            description = pluralStringResource(R.plurals.player_skip_back, SKIP_SECONDS, SKIP_SECONDS),
            onClick = { onSkipBy(-SKIP_INTERVAL) },
            mirrored = false,
        )
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.player_pause else R.string.player_resume,
                    ),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        SkipButton(
            icon = Icons.Filled.Replay30,
            description = pluralStringResource(R.plurals.player_skip_forward, SKIP_SECONDS, SKIP_SECONDS),
            onClick = { onSkipBy(SKIP_INTERVAL) },
            // Material has no `Forward30`, so the replay glyph is mirrored. The content description is
            // what actually says which way it goes, which is the part a screen reader relies on.
            mirrored = true,
        )
    }
}

@Composable
private fun SkipButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(56.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier
                .size(32.dp)
                .then(if (mirrored) Modifier.mirrorHorizontally() else Modifier),
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-007 — thirty seconds, both ways, for now.
 *
 * The requirement wants both configurable from 5–120 seconds, and asymmetric by default. This is the
 * project owner's number and it is a constant rather than a setting until that setting exists — stated
 * here so the gap is visible in the code rather than only in a plan.
 */
private val SKIP_INTERVAL = 30.seconds

/** The same number as an `Int`, for the plural lookups. */
private const val SKIP_SECONDS = 30

/** `1:04:12` on a long book, `12:30` on a short one. Hours only when there are any. */
@Composable
@ReadOnlyComposable
private fun Duration.asClockLabel(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        stringResource(R.string.player_clock_hours, hours, minutes, seconds)
    } else {
        stringResource(R.string.player_clock, minutes, seconds)
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Flips a glyph left-to-right, for the forward skip that reuses the replay icon. */
private fun Modifier.mirrorHorizontally(): Modifier = scale(scaleX = -1f, scaleY = 1f)
