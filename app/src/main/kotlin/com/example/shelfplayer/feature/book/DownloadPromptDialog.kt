package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R

/**
 * PD-004 — what tapping an in-flight download offers: Pause, Stop, or carry on.
 *
 * Stop is the destructive one, and its body says exactly what it does. For a copy only this profile wants,
 * Stop cancels the transfer and deletes the partly downloaded files. For a copy another profile also
 * claims ([isShared]), Stop releases only this profile's claim: the other profile's transfer continues and
 * no files are deleted. Nothing here touches the server or the listening position.
 *
 * The buttons are stacked rather than offered as confirm/dismiss because there are three, and a row of
 * three does not survive 200% font size.
 */
@Composable
internal fun DownloadInFlightDialog(isShared: Boolean, onPause: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_download_prompt_title)) },
        text = {
            Text(
                text = stringResource(
                    if (isShared) R.string.book_download_prompt_shared_body else R.string.book_download_prompt_body,
                ),
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onPause) { Text(text = stringResource(R.string.book_download_prompt_pause)) }
                TextButton(onClick = onStop) {
                    Text(
                        text = stringResource(R.string.book_download_prompt_stop),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.book_download_prompt_keep)) }
            }
        },
    )
}
