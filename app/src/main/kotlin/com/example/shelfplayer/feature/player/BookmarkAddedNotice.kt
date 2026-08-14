package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 11.1 / 21 — a long press kept this spot, and something has to say so.
 *
 * The bookmark button's long press deliberately opens nothing: a listener presses it because they just
 * heard something, and a sheet between the press and the bookmark loses the moment. That leaves no visible
 * result at all, which is the state PRODUCT_SPEC 21 does not allow — an action whose only feedback is that
 * the app did not crash.
 *
 * **The position is in the message** rather than a bare "Bookmarked", because it is the one thing a listener
 * would go and check. Seeing `2:41:07` answers "did it catch the right bit" without opening the list.
 *
 * No undo button, unlike [RewindNotice]. The two are not symmetrical: a rewind is something the *app* did to
 * the listener's position and has to be reversible, and a bookmark is something the listener did on purpose.
 * The list is one tap away and has a delete on every row.
 */
@Composable
fun BookmarkAddedNotice(at: Duration, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keyed on the position, so bookmarking twice restarts the countdown rather than inheriting what was
    // left of the first one's.
    LaunchedEffect(at) {
        delay(VISIBLE_MILLIS)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Snackbar {
            Text(text = stringResource(R.string.player_bookmark_added, at.asChapterClock()))
        }
    }
}

/** Material's long snackbar duration, matching the rewind notice. */
private const val VISIBLE_MILLIS = 4_000L
