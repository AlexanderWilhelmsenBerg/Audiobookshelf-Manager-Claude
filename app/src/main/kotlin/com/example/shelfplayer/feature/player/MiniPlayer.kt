package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    if (state.bookId == null) return
    val activeTimerLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
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
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
