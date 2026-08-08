package com.example.shelfplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.AutoRewindController
import kotlinx.coroutines.delay

/**
 * PRODUCT_SPEC PLAY-009 — "applied rewind is visible briefly and can be undone".
 *
 * ### Why both halves are one component
 *
 * The requirement asks for a rewind the listener can *see* and can *reverse*, and those have to appear
 * together: a message with no undo tells somebody their position moved and leaves them to fix it by hand,
 * and an undo with no message is a control for something they never noticed happened.
 *
 * ### Why it disappears on its own
 *
 * "Briefly" is the requirement's word. A notice that stayed would sit over the player until dismissed, and
 * the feature it describes is one the listener opted into — it should not need acknowledging every time.
 * Four seconds is Material's long snackbar duration, which is what this is.
 *
 * ### Drawn over everything, including the collapsed player
 *
 * A rewind can be applied while the app is on the shelf, or while it is in the background entirely. Putting
 * the notice inside the full player would mean the one case a listener is most likely to notice — reaching
 * for the phone after a long pause — is the one where they never see it.
 */
@Composable
fun RewindNotice(
    applied: AutoRewindController.Applied,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seconds = applied.amount.inWholeSeconds.toInt()
    // Keyed on the amount *and* the position, so a second rewind restarts the countdown rather than
    // inheriting what was left of the first one's.
    LaunchedEffect(applied) {
        delay(VISIBLE_MILLIS)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Snackbar(
            action = {
                TextButton(onClick = onUndo) {
                    Text(
                        text = stringResource(R.string.player_rewind_undo),
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            },
            content = {
                Text(text = pluralStringResource(R.plurals.player_rewind_applied, seconds, seconds))
            },
        )
    }
}

/** Material's long snackbar duration, which is what "briefly" means here. */
private const val VISIBLE_MILLIS = 4_000L
