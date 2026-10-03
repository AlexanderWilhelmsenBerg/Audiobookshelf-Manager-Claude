package com.example.shelfplayer.feature.book

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R

/** So a test can find the button without depending on which of its icons is showing. */
internal const val BOOK_DOWNLOAD_BUTTON = "book-download-button"

/**
 * PRODUCT_SPEC DL-001 / PD-003 — one button, a handful of states, and each tap means the obvious thing.
 *
 * ### Why one control rather than separate download and delete
 *
 * This follows the ShelfPlayer fork the owner named as the UI reference, whose `DownloadButton` cycles the
 * same way: *nothing here* → download; *arriving* → pause or stop (asked first); *here* → remove. It is also
 * what the official Audiobookshelf app does, and the reason both landed there is that a book is in exactly
 * one of those states, so a second control would always be the one that does nothing.
 *
 * The extra states are **paused**, **failed** and **on device for another profile**. Failed gives retry rather than
 * hiding the error; OnDevice lets an entitled profile claim the existing physical copy without another
 * transfer.
 *
 * The failed state is added because
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
 *
 * ### The tap is answered before the download exists (#202)
 *
 * Pressing *Download* first checks the account's permission, the book's files and the free space, and only
 * then writes the download — which can take seconds, and the button used to sit unchanged for all of it.
 * [DownloadButtonState.Starting] is shown from the tap itself: the same indeterminate ring, the icon of the
 * action already taken, and the control disabled, so a second tap can neither repeat the request nor be
 * read as *cancel*.
 */
@Composable
internal fun DownloadButton(state: DownloadButtonState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = state.progress ?: 0f,
        label = "download-progress",
    )
    val description = state.description()
    Box(modifier = modifier.size(BUTTON), contentAlignment = Alignment.Center) {
        // The ring and the number are decoration for the button's own content description, which already
        // carries the phase and the percent. Left in the tree, TalkBack would announce the percent twice.
        Box(modifier = Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            DownloadRing(state = state, progress = progress)
        }
        FilledTonalIconButton(
            onClick = onClick,
            enabled = state !is DownloadButtonState.Starting,
            modifier = Modifier
                .testTag(BOOK_DOWNLOAD_BUTTON)
                .semantics { contentDescription = description },
        ) {
            val icon = state.icon()
            when {
                icon != null -> Icon(imageVector = icon, contentDescription = null)
                state is DownloadButtonState.Downloading -> PercentLabel(state.percent)
                state is DownloadButtonState.Paused -> PercentLabel(state.percent)
            }
        }
    }
}

/**
 * The spoken description: the phase and the percent while arriving, so TalkBack says what the number says,
 * and the action's own label otherwise.
 */
@Composable
internal fun DownloadButtonState.description(): String = when (this) {
    is DownloadButtonState.Downloading -> stringResource(
        R.string.book_download_in_progress_description,
        stringResource(phase.status),
        percent,
    )

    is DownloadButtonState.Paused -> stringResource(R.string.book_download_paused_description, percent)

    else -> stringResource(label)
}

/**
 * The ring: indeterminate before the first byte of a transfer that is actually running, static at zero
 * while waiting or retrying (a spinner would claim work is happening), and muted when paused.
 */
@Composable
private fun DownloadRing(state: DownloadButtonState, progress: Float) {
    val showsRing = state is DownloadButtonState.Downloading ||
        state is DownloadButtonState.Starting ||
        state is DownloadButtonState.Paused
    if (!showsRing) return
    val color = if (state is DownloadButtonState.Paused) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.primary
    }
    if (state.isIndeterminate()) {
        CircularProgressIndicator(modifier = Modifier.size(RING), strokeWidth = STROKE, color = color)
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(RING),
            strokeWidth = STROKE,
            color = color,
        )
    }
}

private fun DownloadButtonState.isIndeterminate(): Boolean = when (this) {
    is DownloadButtonState.Starting -> true

    is DownloadButtonState.Downloading ->
        progress == null && (phase == DownloadPhase.Queued || phase == DownloadPhase.Transferring)

    else -> false
}

/** `null` for the two states whose face is the percent rather than an icon. */
private fun DownloadButtonState.icon(): ImageVector? = when (this) {
    is DownloadButtonState.NotDownloaded -> Icons.Filled.Download

    is DownloadButtonState.OnDevice -> Icons.Filled.AddCircle

    is DownloadButtonState.Starting -> Icons.Filled.Download

    is DownloadButtonState.Downloaded -> Icons.Filled.DownloadDone

    is DownloadButtonState.Failed -> Icons.Filled.Refresh

    is DownloadButtonState.Downloading,
    is DownloadButtonState.Paused,
    -> null
}

/**
 * The integer percent inside the button. A fixed size, not scaled with the font, so "99 %" still fits the
 * 40dp face inside the 48dp touch target at 200% text size. Its number is the content description's too,
 * so it is hidden from accessibility.
 */
@Composable
private fun PercentLabel(percent: Int) {
    Box(modifier = Modifier.clearAndSetSemantics {}) {
        Text(
            text = stringResource(R.string.book_download_percent, percent),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontSize = with(LocalDensity.current) { PERCENT_TEXT.toSp() },
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** What an in-flight transfer is doing right now, for its spoken description. */
enum class DownloadPhase(@StringRes val status: Int) {
    Queued(R.string.downloads_queued),
    Transferring(R.string.downloads_downloading),
    Waiting(R.string.downloads_waiting),
    Retrying(R.string.downloads_retrying),
}

/**
 * What the button is showing, and therefore what a tap does.
 *
 * A sealed hierarchy rather than an enum plus a nullable float, because only one state has progress and a
 * reader should not have to know which. The label travels with the state for the same reason: the content
 * description is the only thing that distinguishes *pause or stop* from *remove* for somebody using TalkBack, and
 * pairing it with the icon here makes them impossible to get out of step.
 */
@Immutable
sealed interface DownloadButtonState {
    val label: Int
    val progress: Float? get() = null

    /** No copy on this device for this profile. A tap starts or joins one. */
    data object NotDownloaded : DownloadButtonState {
        override val label: Int = R.string.book_download
    }

    /**
     * The complete physical copy already exists for another local profile.
     *
     * A tap adds this profile's claim immediately; no bytes are downloaded again.
     */
    data object OnDevice : DownloadButtonState {
        override val label: Int = R.string.book_download_use_device_copy
    }

    /**
     * #202 — the tap has been taken and the download is being authorised and queued. Nothing is on the
     * device yet and there is nothing to cancel, so a tap does nothing; the manifest replaces this the moment
     * the download is written, and a refusal reverts it with the reason.
     */
    data object Starting : DownloadButtonState {
        override val label: Int = R.string.book_download_starting
    }

    /**
     * Arriving, or expected to be: queued, transferring, waiting for a network or backing off to retry. A
     * tap opens the Pause / Stop prompt; it never acts on its own.
     *
     * @property progress `null` until the first byte, which shows an indeterminate ring.
     * @property percent whole percent, 0 to 99. Never 100: completion shows [Downloaded] instead.
     * @property phase what the transfer is doing, for the spoken description.
     */
    data class Downloading(
        override val progress: Float?,
        val percent: Int = 0,
        val phase: DownloadPhase = DownloadPhase.Transferring,
    ) : DownloadButtonState {
        override val label: Int = R.string.downloads_downloading
    }

    /**
     * The listener paused it. A tap resumes through `DownloadBookUseCase`, with no prompt: nothing is lost
     * by resuming. Shown as a muted ring and the percent, with no play glyph.
     */
    data class Paused(override val progress: Float?, val percent: Int) : DownloadButtonState {
        override val label: Int = R.string.downloads_resume
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

/**
 * #202 — what the button shows while a Download tap is still being authorised and queued.
 *
 * The manifest's own evidence always wins: once it says the book is arriving or here, that is what is shown,
 * whether or not the tap's request has reported back. Before that, the button says the tap was taken.
 */
internal fun DownloadButtonState.whileStarting(isStarting: Boolean): DownloadButtonState = when {
    !isStarting -> this
    this is DownloadButtonState.Downloading || this is DownloadButtonState.Downloaded -> this
    else -> DownloadButtonState.Starting
}

/** Material 3's icon-button touch target, which the ring has to sit outside rather than inside. */
private val BUTTON = 48.dp
private val RING = 44.dp
private val STROKE = 2.dp
private val PERCENT_TEXT = 12.dp
