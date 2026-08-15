package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.library.Book
import kotlin.time.Duration

/** The overflow button, so a test can find it without depending on the icon's content description. */
internal const val BOOK_OVERFLOW_BUTTON = "book-overflow-button"

/**
 * PRODUCT_SPEC LIB-004 / PLAY-003 / PLAY-004 — everything you can do with a book that is not *play* it.
 *
 * ### Why a menu, and why here
 *
 * The owner asked for it in these terms: *"For the book view… I want a three dot next to the download and
 * play button. On the right. So download, play and then three dots."* The order is the order a hand reaches
 * them, and the menu is last because none of it is pressed often.
 *
 * It replaces the **Finished checkbox** that used to sit under the progress bar. One control, in the place
 * everything else about the book lives, rather than a checkbox in the middle of a reading surface — which is
 * also what the official Audiobookshelf app does, and the reason the owner asked for the menu shape.
 *
 * ### The two placeholders say which phase they arrive in
 *
 * *Delete local item* is live as of Phase 3 slice 4, and it is the same action as the download button's
 * third state — the owner asked for it in both places, and one action reachable two ways is better than two
 * that could disagree. It is enabled only when there is a copy to delete.
 *
 * *Manage local files* opens the storage screen, which lists every download on the device with its size
 * (ADR-0018 decision 6). Both rows are live as of Phase 3 slice 7; neither is a placeholder any more.
 *
 * *Add to playlist* is absent rather than disabled: this app has no playlists at all, in any phase that is
 * planned, so a disabled row would promise something nothing is building.
 *
 * ### *Go to web client* is a UI route, not an API call
 *
 * `{baseUrl}/item/{id}` is the Audiobookshelf **web client's** own address for an item, not an endpoint, so
 * PRODUCT_SPEC 22.4's "capture before you rely on it" does not have a fixture to offer. The failure mode is
 * also the mildest in the app: a wrong route shows the web client's own not-found page, and nothing is lost.
 * It is still an assumption, and it is recorded as one in the pull request rather than left in the code.
 */
@Composable
internal fun BookOverflowMenu(book: Book, actions: BookMenuActions, modifier: Modifier = Modifier) {
    var isOpen by remember { mutableStateOf(false) }
    val isFinished = book.progress?.isFinished == true
    FilledTonalIconButton(
        onClick = { isOpen = true },
        modifier = modifier.testTag(BOOK_OVERFLOW_BUTTON),
    ) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.book_more_actions),
        )
    }
    DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
        MenuRow(
            labelRes = R.string.book_menu_history,
            icon = Icons.Filled.History,
            onClick = {
                isOpen = false
                actions.onOpenHistory()
            },
        )
        MenuRow(
            // The label names the state it would put the book *into*, not the state it is in. "Finished" on a
            // finished book reads as a heading rather than as a button.
            labelRes = if (isFinished) R.string.book_menu_unfinished else R.string.book_menu_finished,
            icon = if (isFinished) Icons.Outlined.CheckCircle else Icons.Filled.CheckCircle,
            onClick = {
                isOpen = false
                actions.onFinishedChanged(!isFinished)
            },
        )
        MenuRow(
            labelRes = R.string.book_menu_discard,
            icon = Icons.Filled.RestartAlt,
            // Enabled only when there is something to discard. On a book never started it would be a
            // destructive-sounding control whose effect is nothing at all.
            isEnabled = book.progress?.let { it.position > Duration.ZERO || it.isFinished } == true,
            onClick = {
                isOpen = false
                actions.onDiscardRequested()
            },
        )
        MenuRow(
            labelRes = R.string.book_menu_local_files,
            icon = Icons.Filled.Folder,
            onClick = {
                isOpen = false
                actions.onManageDownloads()
            },
        )
        MenuRow(
            // The same action as the download button's third state, reached the other way the owner asked
            // for it. Enabled only when there is a copy: on a book that was never downloaded this is a
            // destructive-sounding control whose effect is nothing at all.
            labelRes = R.string.book_menu_delete_local,
            icon = Icons.Filled.Delete,
            isEnabled = actions.isDownloaded,
            onClick = {
                isOpen = false
                actions.onRemoveDownload()
            },
        )
        MenuRow(
            labelRes = R.string.book_menu_edit_metadata,
            icon = Icons.Filled.Edit,
            onClick = {
                isOpen = false
                actions.onEditMetadata()
            },
        )
        MenuRow(
            labelRes = R.string.book_menu_web_client,
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            isEnabled = actions.webUrl != null,
            onClick = {
                isOpen = false
                actions.webUrl?.let(actions.onOpenWebClient)
            },
        )
        MenuRow(
            labelRes = R.string.book_menu_more_info,
            icon = Icons.Filled.Info,
            onClick = {
                isOpen = false
                actions.onOpenInfo()
            },
        )
    }
}

@Composable
private fun MenuRow(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
) {
    DropdownMenuItem(
        text = { Text(text = stringResource(labelRes)) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        enabled = isEnabled,
        onClick = onClick,
    )
}

/**
 * PRODUCT_SPEC 21 — a destructive action that describes its actual effect.
 *
 * Every clause is load-bearing, and the reason is that *"discard progress"* on its own could plausibly mean
 * any of three things: forget where I am, delete the download, or remove the book. It means the first. Saying
 * which of the other two it does **not** do is the difference between a confirmation and a warning label.
 */
@Composable
internal fun DiscardProgressDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_discard_title)) },
        text = { Text(text = stringResource(R.string.book_discard_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.book_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.book_discard_cancel)) }
        },
    )
}

/**
 * PRODUCT_SPEC LIB-004 — the metadata the sync stores and the screen has no room for.
 *
 * The screen above shows what a reader chooses a book by: title, author, narrator, series, length, progress.
 * This is the rest — the identifiers and the file facts — which are exactly what somebody looks for when
 * something is *wrong*: a book matched to the wrong edition, a duplicate, a file that will not play. So it is
 * a sheet rather than a section, and every row that has no value is absent rather than blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookInfoSheet(book: Book, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.book_menu_more_info), style = MaterialTheme.typography.titleMedium)
            InfoRow(R.string.book_info_title, book.title)
            InfoRow(R.string.book_info_subtitle, book.subtitle)
            InfoRow(R.string.book_info_publisher, book.publisher)
            InfoRow(R.string.book_info_published, book.publishedYear?.toString())
            InfoRow(R.string.book_info_language, book.language)
            InfoRow(R.string.book_info_isbn, book.isbn)
            InfoRow(R.string.book_info_asin, book.asin)
            InfoRow(
                R.string.book_info_flags,
                listOfNotNull(
                    stringResource(R.string.book_info_explicit).takeIf { book.isExplicit },
                    stringResource(R.string.book_info_abridged).takeIf { book.isAbridged },
                ).joinToString(", ").takeIf(String::isNotBlank),
            )
            InfoRow(R.string.book_info_tracks, book.trackCount.toString())
            // The server's own id, which is the thing a support question is actually about. Shown because a
            // self-hosted user *is* the administrator: this is their own data, not somebody else's identifier.
            InfoRow(R.string.book_info_item_id, book.id.value)
            Text(
                text = stringResource(R.string.book_info_description_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** One label and value, or nothing at all when there is no value. */
@Composable
private fun InfoRow(labelRes: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What the menu can do, as one bundle.
 *
 * The same shape `PlaybackSettingsActions` uses, and for the same reason: seven callbacks passed individually
 * push the screen past detekt's parameter limit, and that limit is protecting against exactly this — a
 * composable whose argument order somebody will get wrong.
 */
@Immutable
internal data class BookMenuActions(
    val onOpenHistory: () -> Unit,
    val onFinishedChanged: (Boolean) -> Unit,
    val onDiscardRequested: () -> Unit,
    val onOpenWebClient: (String) -> Unit,
    val onOpenInfo: () -> Unit,
    /** `null` when the server's address is not known, which disables the row rather than hiding it. */
    val webUrl: String?,
    /** PRODUCT_SPEC DL-003 — whether *Delete local item* has anything to delete. */
    val isDownloaded: Boolean = false,
    val onRemoveDownload: () -> Unit = {},
    /** PRODUCT_SPEC DL-003 — everything downloaded on this device, in one list. */
    val onManageDownloads: () -> Unit = {},
    /**
     * PRODUCT_SPEC MGR-001 — the metadata editor.
     *
     * Always offered, even to an account that may not save. The editor itself explains the refusal, which
     * is more useful than a row that is simply not there: "why can I not edit this" has an answer, and a
     * missing row does not give it.
     */
    val onEditMetadata: () -> Unit = {},
)

/**
 * PRODUCT_SPEC 21 / DL-003 — removing a download, described by what it actually does.
 *
 * Four clauses, and each one exists because *"remove the download"* could plausibly mean something worse:
 *
 *  - **the files go and the space is freed** — the thing that was asked for;
 *  - **nothing is deleted on your server** — the fear, and the one this app must never earn. PRODUCT_SPEC
 *    forbids even *claiming* that a database delete removes media; a local delete certainly does not;
 *  - **your position is kept** — because `media_progress` is untouched, and a listener who removes a
 *    finished book to reclaim space must not lose where they were in it;
 *  - **another profile's copy stays** — DL-003 criterion 5. On a shared device this is the difference
 *    between freeing your own space and deleting somebody else's book.
 */
@Composable
internal fun RemoveDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_download_remove_title)) },
        text = { Text(text = stringResource(R.string.book_download_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.book_download_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.book_download_remove_cancel)) }
        },
    )
}
