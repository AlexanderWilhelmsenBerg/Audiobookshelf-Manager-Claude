package com.example.shelfplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
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
    if (state.bookId == null) return
    val activeTimerLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
    val openLabel = stringResource(R.string.player_open)
    val backSeconds = skips.intervals.back.inWholeSeconds.toInt()
    val forwardSeconds = skips.intervals.forward.inWholeSeconds.toInt()
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            LinearProgressIndicator(
                progress = { state.fractionComplete },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniPlayerArtwork(state = state)
                    Column(modifier = Modifier.weight(1f)) {
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
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                // PRODUCT_SPEC PLAY-007 — the two skips a listener reaches for without looking, at the
                // configured intervals. The glyph drops its number rather than printing a wrong one.
                IconButton(onClick = skips.onBack) {
                    Icon(
                        imageVector = SkipIcons.back(skips.intervals.back),
                        contentDescription = pluralStringResource(
                            R.plurals.player_skip_back,
                            backSeconds,
                            backSeconds,
                        ),
                    )
                }
                // PRODUCT_SPEC PLAY-008 — the remaining time doubles as the control's label, so a
                // listener can see the timer is running without opening anything.
                IconButton(onClick = onOpenSleepTimer) {
                    if (timer.isActive) {
                        Text(
                            text = timer.remaining.asCountdownLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics {
                                contentDescription = activeTimerLabel
                            },
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Bedtime,
                            contentDescription = stringResource(R.string.sleep_timer_open),
                        )
                    }
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_resume,
                        ),
                    )
                }
                IconButton(onClick = skips.onForward) {
                    Icon(
                        imageVector = SkipIcons.forward(skips.intervals.forward),
                        contentDescription = pluralStringResource(
                            R.plurals.player_skip_forward,
                            forwardSeconds,
                            forwardSeconds,
                        ),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.player_stop),
                    )
                }
            }
        }
    }
}

/**
 * The cover, at the height the bar gives it.
 *
 * Present in the enlarged bar and absent from the old one, and it is most of what the extra height buys:
 * a bar with the book's cover on it says which book is playing before any text is read.
 */
@Composable
private fun MiniPlayerArtwork(state: PlaybackUiState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(ARTWORK_SIZE)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val uri = state.artworkUri
        if (uri == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
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
 * Double the old bar.
 *
 * The old one was a single row of text and two icons at about 56dp. Doubling it is what makes room for
 * the cover and for four controls at a comfortable touch size, and it makes the bar a target big enough
 * to tap without aiming — which matters now that tapping it opens the player.
 */
private val BAR_HEIGHT = 112.dp

private val ARTWORK_SIZE = 88.dp
