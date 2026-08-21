package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R

/**
 * PRODUCT_SPEC MGR-007 — *"the UI warns that the server will modify source audio files"*.
 *
 * ### Why this dialog is longer than the other confirmations
 *
 * Because it is the only action in this app that changes a file the app never reads. Removing a download
 * deletes something the user can see; removing an item from the database is reversible by rescanning. This
 * rewrites the bytes of somebody's audio files, on a machine they own, and the app cannot undo it or even
 * verify it afterwards.
 *
 * So four clauses, and each is here because leaving it out would leave a plausible wrong reading:
 *
 *  - **the server rewrites this book's audio files** — the thing being agreed to, said in the words of what
 *    happens rather than of what it is called;
 *  - **nothing on this device changes** — the fear, and the truthful answer: no download is touched, no
 *    position moves, and the app's own copy of the metadata is the *input* to this, not its result;
 *  - **keep server-side backups** — MGR-007 asks for this advice in as many words. The request does send
 *    `backup=1`, and that is deliberately not offered as reassurance: the server's copy goes into the item's
 *    cache directory, which is a safety net for the operation and not a backup anybody could restore from;
 *  - **the cover goes with it** — because MGR-007 offers *"metadata only, cover only, or both"* and the API
 *    has no such parameter. Saying so is better than a chooser that quietly does the same thing three ways.
 */
@Composable
internal fun EmbedMetadataDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_embed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.book_embed_body, title))
                Text(
                    text = stringResource(R.string.book_embed_backups),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.book_embed_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.book_embed_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_embed_cancel)) }
        },
    )
}

/**
 * PRODUCT_SPEC MGR-007 — *"the operation is non-blocking and has visible status"*.
 *
 * ### Why a banner and not a snackbar
 *
 * Every other result on this screen is a snackbar, and a snackbar is right for them: they describe something
 * that already finished. This describes something still happening, for as long as it takes to rewrite every
 * file of an eighteen-hour book — and a status that disappears after four seconds is not a visible status.
 *
 * It also has to survive being read. The terminal states are dismissed by the user rather than by a timer,
 * because two of them ([EmbedStatus.Unknown] especially) are asking them to go and check something.
 *
 * ### Why "unknown" gets its own state and its own words
 *
 * Because the alternative is lying. Nothing replays a missed `task_finished`: if the connection drops while
 * the server is working, this app has no way to learn how it ended, and MGR-007's *"a failed operation never
 * marks local metadata as embedded"* is precisely a rule against guessing. So it says the connection dropped
 * and names the one place the answer exists.
 */
@Composable
internal fun EmbedStatusBanner(status: EmbedStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (status == EmbedStatus.Idle) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                text = status.text(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // No dismiss while it is working: there is nothing to acknowledge yet, and a banner the user
            // could clear would leave them with no way to see how it ended.
            if (!status.isWorking) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_embed_dismiss)) }
            }
        }
    }
}

/** Whether the server still owes an answer. Decides both the spinner and whether the banner can be cleared. */
private val EmbedStatus.isWorking: Boolean
    get() = this == EmbedStatus.Requesting || this == EmbedStatus.Running

/**
 * The sentence for each state, resolved here because the `ViewModel` has no business holding a resource.
 *
 * A failure the server explained and one it did not are different sentences, and the difference is
 * actionable: the first tells somebody where to look, and the second tells them there is nothing to find.
 */
@Composable
private fun EmbedStatus.text(): String = when (this) {
    EmbedStatus.Idle -> ""
    EmbedStatus.Requesting -> stringResource(R.string.book_embed_requesting)
    EmbedStatus.Running -> stringResource(R.string.book_embed_running)
    EmbedStatus.Finished -> stringResource(R.string.book_embed_finished)
    is EmbedStatus.Failed -> summary
    is EmbedStatus.ServerFailed -> stringResource(
        if (hasServerError) R.string.book_embed_server_failed else R.string.book_embed_server_failed_silent,
    )
    EmbedStatus.Unknown -> stringResource(R.string.book_embed_unknown)
}
