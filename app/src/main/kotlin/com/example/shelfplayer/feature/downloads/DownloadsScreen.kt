package com.example.shelfplayer.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.layout.centredListPadding
import com.example.shelfplayer.core.designsystem.layout.windowWidth
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import java.util.Locale

@Composable
fun DownloadsRoute(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val volumes by viewModel.volumes.collectAsStateWithLifecycle()
    val selectedVolume by viewModel.selectedVolume.collectAsStateWithLifecycle()
    DownloadsScreen(
        uiState = uiState,
        message = message,
        volumes = volumes,
        selectedVolume = selectedVolume,
        onVolumeChosen = viewModel::onVolumeChosen,
        onMessageShown = viewModel::onMessageShown,
        onRemove = viewModel::onRemove,
        onPinnedChanged = viewModel::onPinnedChanged,
        onPauseToggled = viewModel::onPauseToggled,
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
    /** PRODUCT_SPEC DL-001 — pause a running download, or resume a paused one. */
    onPauseToggled: (com.example.shelfplayer.core.model.LibraryItemId, Boolean) -> Unit,
    onVerify: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    onMessageShown: () -> Unit = {},
    volumes: List<StorageVolumeOption> = emptyList(),
    selectedVolume: String = StorageVolumeOption.INTERNAL_UUID,
    onVolumeChosen: (String) -> Unit = {},
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // PRODUCT_SPEC 4 / §51 — a queue is a list of rows, and a row stretched across a tablet puts
            // its title and its progress a hand-span apart. The column keeps a readable measure.
            contentPadding = centredListPadding(width = windowWidth()),
        ) {
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
                // PRODUCT_SPEC DL-003 / ADR-0020 — only worth a row when there is more than one answer.
                // A phone with no card would otherwise get a picker with one option in it.
                if (volumes.size > 1) {
                    StorageVolumePicker(
                        volumes = volumes,
                        selected = selectedVolume,
                        onChosen = onVolumeChosen,
                    )
                    HorizontalDivider()
                }
            }
            items(uiState.books, key = { row -> "${row.serverId.value}:${row.bookId.value}" }) { row ->
                DownloadRowItem(
                    row = row,
                    onPinnedChanged = { pinned -> onPinnedChanged(row.bookId, row.serverId, pinned) },
                    onPauseToggled = { paused -> onPauseToggled(row.bookId, paused) },
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

/**
 * PRODUCT_SPEC DL-003 / ADR-0018 decision 4 / ADR-0020 — where the *next* download goes.
 *
 * ### It says what it does not do
 *
 * "Change download location" reads like a promise to move things, and this moves nothing: every downloaded
 * file's location is recorded absolutely in the manifest, so the books already here keep playing from where
 * they are. The hint states that rather than leaving it to be discovered by a user who chose a card and
 * then found their phone no emptier.
 *
 * ### And what removing the card costs
 *
 * Books on a card that is taken out fail the start-up check and offer a re-download — the same handling any
 * unreadable local file gets (PLAY-003). Nothing is deleted on the strength of an absent volume, which is
 * why this can be a plain radio list and not a warning dialog.
 */
@Composable
private fun StorageVolumePicker(volumes: List<StorageVolumeOption>, selected: String, onChosen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.downloads_location),
            style = MaterialTheme.typography.titleSmall,
        )
        volumes.forEach { volume ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = volume.uuid == selected,
                        role = Role.RadioButton,
                        onClick = { onChosen(volume.uuid) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = volume.uuid == selected, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (volume.isInternal) {
                            stringResource(R.string.downloads_location_internal)
                        } else {
                            volume.label
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = listOfNotNull(
                            stringResource(R.string.downloads_location_free, formatBytes(volume.freeBytes)),
                            stringResource(R.string.downloads_location_removable).takeIf { volume.isRemovable },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.downloads_location_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadRowItem(
    row: DownloadRow,
    onPinnedChanged: (Boolean) -> Unit,
    onPauseToggled: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
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
                    // PRODUCT_SPEC DL-001 — "paused" and "incomplete" are the same fact stated at two
                    // depths, so the row says the more specific one and not both.
                    when {
                        row.isPaused -> stringResource(R.string.downloads_paused)
                        !row.isComplete -> stringResource(R.string.downloads_incomplete)
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                // A paused download is not an error and is not coloured like one. That distinction is the
                // entire reason `DownloadState.Paused` exists.
                color = if (row.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // PRODUCT_SPEC DL-001 — only for a book still being fetched. A completed download has nothing to
        // pause, and a control that does nothing is worse than no control.
        if (!row.isComplete) {
            IconButton(onClick = { onPauseToggled(!row.isPaused) }) {
                Icon(
                    imageVector = if (row.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(
                        if (row.isPaused) R.string.downloads_resume else R.string.downloads_pause,
                    ),
                )
            }
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
