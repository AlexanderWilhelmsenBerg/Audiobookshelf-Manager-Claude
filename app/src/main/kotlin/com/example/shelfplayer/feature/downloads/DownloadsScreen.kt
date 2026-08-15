package com.example.shelfplayer.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import java.util.Locale

@Composable
fun DownloadsRoute(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    DownloadsScreen(
        uiState = uiState,
        message = message,
        onMessageShown = viewModel::onMessageShown,
        onRemove = viewModel::onRemove,
        onPinnedChanged = viewModel::onPinnedChanged,
        onVerify = viewModel::onVerify,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

/**
 * PRODUCT_SPEC DL-003 / SET-002 / ADR-0018 decisions 6 and 8 — everything downloaded on this device.
 *
 * ### Why a row can have no title
 *
 * Decision 6 asks for every download to be listed regardless of which profile is signed in, and
 * PRODUCT_SPEC 5.2 forbids showing one profile's content to another. Both hold: the row is there, with its
 * size, and its name is replaced by *"a book in a library this profile cannot see"*. That is enough to
 * decide to delete it, which is the only reason this list exists.
 *
 * ### The two controls per row
 *
 * A **pin**, which is DL-006's "never remove this one automatically", and a **delete**, which asks first.
 * ShelfPlayer's downloaded panel offers only removal; the pin is here because this app has an automatic
 * cleanup that ShelfPlayer does not, and a cleanup with no way to say *not that one* is a cleanup nobody
 * will turn on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    uiState: DownloadsUiState,
    onRemove: (com.example.shelfplayer.core.model.LibraryItemId, com.example.shelfplayer.core.model.ServerId) -> Unit,
    onPinnedChanged: (
        com.example.shelfplayer.core.model.LibraryItemId,
        com.example.shelfplayer.core.model.ServerId,
        Boolean,
    ) -> Unit,
    onVerify: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbars.showSnackbar(text)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoaded && uiState.books.isEmpty()) {
            ShelfEmptyState(
                title = stringResource(R.string.downloads_empty_title),
                body = stringResource(R.string.downloads_empty_body),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.downloads_total, formatBytes(uiState.totalBytes)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        // Decision 8's *Repair*, named for what it can honestly do. The server sends an
                        // ETag rather than a checksum, so this checks the files are present, whole and
                        // openable — it does not compare bytes with the server. See ADR-0018.
                        text = stringResource(R.string.downloads_verify_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onVerify, modifier = Modifier.padding(top = 4.dp)) {
                        Text(text = stringResource(R.string.downloads_verify))
                    }
                }
                HorizontalDivider()
            }
            items(uiState.books, key = { row -> "${row.serverId.value}:${row.bookId.value}" }) { row ->
                DownloadRowItem(
                    row = row,
                    onPinnedChanged = { pinned -> onPinnedChanged(row.bookId, row.serverId, pinned) },
                    onRemove = { confirming = row.bookId.value },
                )
                if (confirming == row.bookId.value) {
                    RemoveDialog(
                        isShared = row.isSharedWithAnotherProfile,
                        onConfirm = {
                            confirming = null
                            onRemove(row.bookId, row.serverId)
                        },
                        onDismiss = { confirming = null },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DownloadRowItem(row: DownloadRow, onPinnedChanged: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.title ?: stringResource(R.string.downloads_hidden_title),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    formatBytes(row.bytes),
                    pluralStringResource(R.plurals.downloads_files, row.fileCount, row.fileCount),
                    stringResource(R.string.downloads_incomplete).takeIf { !row.isComplete },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (row.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconToggleButton(checked = row.isPinned, onCheckedChange = onPinnedChanged) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = stringResource(
                    if (row.isPinned) R.string.downloads_unpin else R.string.downloads_pin,
                ),
                tint = if (row.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.downloads_remove),
            )
        }
    }
}

/**
 * PRODUCT_SPEC 21 / DL-003 — the same four claims the book screen's dialog makes, plus one.
 *
 * When another profile on this device also downloaded the book, the dialog says so **before** the tap
 * rather than reporting it afterwards: "this will not free any space" is a different decision from "this
 * will free 412 MB", and a user is entitled to make it knowingly.
 */
@Composable
private fun RemoveDialog(isShared: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_download_remove_title)) },
        text = {
            Text(
                text = if (isShared) {
                    stringResource(R.string.downloads_remove_shared_body)
                } else {
                    stringResource(R.string.book_download_remove_body)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.book_download_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.book_download_remove_cancel)) }
        },
    )
}

/**
 * Bytes as a person reads them.
 *
 * Powers of 1024 with the units Android itself uses. One decimal below ten, none above: "1.4 GB" is worth
 * a digit and "412 MB" is not, and a screen full of "411.7 MB" is a screen of noise.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < KILOBYTE) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / KILOBYTE
    var index = 0
    while (value >= KILOBYTE && index < units.lastIndex) {
        value /= KILOBYTE
        index++
    }
    val pattern = if (value < TEN) "%.1f %s" else "%.0f %s"
    return String.format(Locale.getDefault(), pattern, value, units[index])
}

private const val KILOBYTE = 1024.0
private const val TEN = 10.0
