package com.example.shelfplayer.feature.book

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R

/** So a test can find the button without depending on which of its four icons is showing. */
internal const val BOOK_DOWNLOAD_BUTTON = "book-download-button"

/**
 * PRODUCT_SPEC DL-001 — one button, four states, and each tap means the obvious thing.
 *
 * ### Why one control rather than separate download and delete
 *
 * This follows the ShelfPlayer fork the owner named as the UI reference, whose `DownloadButton` cycles the
 * same way: *nothing here* → download; *arriving* → cancel; *here* → remove. It is also what the official
 * Audiobookshelf app does, and the reason both landed there is that a book is in exactly one of those
 * states, so a second control would always be the one that does nothing.
 *
 * The fourth state is **failed**, which ShelfPlayer does not surface separately. It is added here because
 * this app has to work against a self-hosted server on a home connection, where a stopped download is
 * common and "tap to try again" is a more useful thing to show than an idle download icon that hides the
 * fact that something already went wrong.
 *
 * ### The ring is the progress, and it replaces the icon
 *
 * A determinate ring around the button, again as in ShelfPlayer. Weighted by bytes rather than by file
 * count — see `BookDownloader.Weights` — so it moves at the speed the transfer actually goes rather than
 * jumping a twelfth at a time.
 *
 * The ring is *indeterminate* until the first byte lands. A determinate ring frozen at zero is
 * indistinguishable from a download that is not happening, and the gap between pressing and the first byte
 * is exactly when a user is deciding whether the button worked.
 */
@Composable
internal fun DownloadButton(state: DownloadButtonState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = state.progress ?: 0f,
        label = "download-progress",
    )
    Box(modifier = modifier.size(BUTTON), contentAlignment = Alignment.Center) {
        if (state is DownloadButtonState.Downloading) {
            if (state.progress == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(RING),
                    strokeWidth = STROKE,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(RING),
                    strokeWidth = STROKE,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.testTag(BOOK_DOWNLOAD_BUTTON)) {
            Icon(
                imageVector = when (state) {
                    is DownloadButtonState.NotDownloaded -> Icons.Filled.Download
                    is DownloadButtonState.Downloading -> Icons.Filled.Close
                    is DownloadButtonState.Downloaded -> Icons.Filled.DownloadDone
                    is DownloadButtonState.Failed -> Icons.Filled.Refresh
                },
                contentDescription = stringResource(state.label),
            )
        }
    }
}

/**
 * What the button is showing, and therefore what a tap does.
 *
 * A sealed hierarchy rather than an enum plus a nullable float, because three of the four states have no
 * progress and a reader should not have to know which. The label travels with the state for the same
 * reason: the content description is the only thing that distinguishes *cancel* from *remove* for somebody
 * using TalkBack, and pairing it with the icon here makes them impossible to get out of step.
 */
@Immutable
sealed interface DownloadButtonState {
    val label: Int
    val progress: Float? get() = null

    /** No copy on this device. A tap starts one. */
    data object NotDownloaded : DownloadButtonState {
        override val label: Int = R.string.book_download
    }

    /**
     * Arriving. A tap **cancels**, and cancelling keeps what has already been fetched.
     *
     * @property progress `null` until the first byte, which shows an indeterminate ring.
     */
    data class Downloading(override val progress: Float?) : DownloadButtonState {
        override val label: Int = R.string.book_download_cancel
    }

    /** Here, complete, playable with no network. A tap **removes** it, after asking. */
    data object Downloaded : DownloadButtonState {
        override val label: Int = R.string.book_download_remove
    }

    /** Stopped. A tap retries, and the retry resumes from the bytes already on disk. */
    data object Failed : DownloadButtonState {
        override val label: Int = R.string.book_download_retry
    }
}

/** Material 3's icon-button touch target, which the ring has to sit outside rather than inside. */
private val BUTTON = 48.dp
private val RING = 44.dp
private val STROKE = 2.dp
