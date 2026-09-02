package com.example.shelfplayer.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import com.example.shelfplayer.ui.glass.LocalGlassHazeState
import com.example.shelfplayer.ui.glass.frostedGlass
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 — what is playing, on every screen.
 *
 * ### Why it renders nothing when nothing is playing
 *
 * A persistent empty bar costs a strip of every screen to say "no". It appears when there is a book and
 * disappears when the user stops one, which also means no screen has to reserve space for it.
 *
 * ### The accessibility bits are not decoration
 *
 * The transport button's content description changes with the state, because "Play" on a button that
 * pauses is what a screen-reader user hears instead of what happens. The title is a polite live region
 * so that a book starting is announced without interrupting whatever is being read.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    timer: SleepTimerState,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onExpand: () -> Unit,
    skips: SkipControls,
    modifier: Modifier = Modifier,
) {
    val activeTimerLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
    val openLabel = stringResource(R.string.player_open)
    val backSeconds = skips.intervals.back.inWholeSeconds.toInt()
    val forwardSeconds = skips.intervals.forward.inWholeSeconds.toInt()
    val hazeState = LocalGlassHazeState.current
    val systemBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AnimatedVisibility(
        visible = state.bookId != null,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // The glass runs to the bottom of the window and the *content* is inset instead. Stopping
                // the surface above the system navigation bar would leave a strip of unblurred shelf under
                // it; stopping the content there is what keeps the controls off the gesture handle.
                .height(MINI_PLAYER_HEIGHT + systemBarInset)
                .frostedGlass(
                    state = hazeState,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                ),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = systemBarInset),
            ) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // The cover and the text are one tap target that opens the player; the controls sit
                    // outside it. That is "anywhere except the buttons", expressed as a region rather than as
                    // a click on the whole bar that the buttons then have to out-compete.
                    //
                    // Not `clickable` on the `Surface`: a clickable container **merges** its descendants'
                    // semantics, so the buttons inside would stop being separate nodes — a screen reader
                    // would find one control where there are five, and a tap on the pause icon would fire
                    // the container's click instead of the button's.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onExpand, onClickLabel = openLabel),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniPlayerArtwork(state = state, isLoading = state.isLoading)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            state.author?.let { author ->
                                Text(
                                    text = author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // PRODUCT_SPEC PLAY-007 — the two skips a listener reaches for without looking, at the
                    // configured intervals. The glyph drops its number rather than printing a wrong one.
                    MiniPlayerButton(onClick = skips.onBack) {
                        Icon(
                            imageVector = SkipIcons.back(skips.intervals.back),
                            contentDescription = pluralStringResource(
                                R.plurals.player_skip_back,
                                backSeconds,
                                backSeconds,
                            ),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                    // PRODUCT_SPEC PLAY-008 — the remaining time doubles as the control's label, so a
                    // listener can see the timer is running without opening anything.
                    MiniPlayerButton(onClick = onOpenSleepTimer) {
                        if (timer.isActive) {
                            Text(
                                text = timer.remaining.asCountdownLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics {
                                    contentDescription = activeTimerLabel
                                },
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Bedtime,
                                contentDescription = stringResource(R.string.sleep_timer_open),
                                modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                            )
                        }
                    }
                    MiniPlayerButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.player_pause else R.string.player_resume,
                            ),
                            modifier = Modifier.size(PLAY_ICON_SIZE),
                        )
                    }
                    MiniPlayerButton(onClick = skips.onForward) {
                        Icon(
                            imageVector = SkipIcons.forward(skips.intervals.forward),
                            contentDescription = pluralStringResource(
                                R.plurals.player_skip_forward,
                                forwardSeconds,
                                forwardSeconds,
                            ),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                    MiniPlayerButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.player_stop),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { state.fractionComplete },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PROGRESS_HEIGHT),
                )
                MiniPlayerTimeLabels(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            start = ARTWORK_WIDTH + TIME_LABEL_HORIZONTAL_INSET,
                            top = TIME_LABEL_TOP_INSET,
                            end = TIME_LABEL_HORIZONTAL_INSET,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerTimeLabels(state: PlaybackUiState, modifier: Modifier = Modifier) {
    val elapsed = state.position.coerceAtLeast(Duration.ZERO)
    val remaining = (state.duration - elapsed).coerceAtLeast(Duration.ZERO)
    Box(modifier = modifier) {
        Text(
            text = elapsed.asChapterClock(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            text = "-${remaining.asChapterClock()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun MiniPlayerButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxHeight()
            .width(CONTROL_WIDTH),
    ) {
        content()
    }
}

/** The cover touches the left, top and bottom edges of the mini player with no surrounding padding. */
@Composable
private fun MiniPlayerArtwork(state: PlaybackUiState, isLoading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(ARTWORK_WIDTH)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = GLASS_ARTWORK_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        val uri = state.artworkUri
        if (uri == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/** Roughly 60% of the previous 112 dp mini-player height. Shared so Home can clear the floating axis bar. */
internal val MINI_PLAYER_HEIGHT = 68.dp
private val ARTWORK_WIDTH = 46.dp
private val CONTROL_WIDTH = 48.dp
private val TRANSPORT_ICON_SIZE = 28.dp
private val PLAY_ICON_SIZE = 34.dp
private val PROGRESS_HEIGHT = 2.dp
private val TIME_LABEL_HORIZONTAL_INSET = 6.dp
private val TIME_LABEL_TOP_INSET = 3.dp
private const val GLASS_ARTWORK_ALPHA = 0.72f
