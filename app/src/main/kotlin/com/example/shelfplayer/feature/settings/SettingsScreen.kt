package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.usecase.ServerDiagnostics

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onDefaultLibraryChanged = viewModel::onDefaultLibraryChanged,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDefaultLibraryChanged: (LibraryId?) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(text = stringResource(R.string.settings_section_libraries)) }
            if (uiState.libraries.isEmpty()) {
                item { Hint(text = stringResource(R.string.settings_libraries_empty)) }
            } else {
                items(uiState.libraries, key = { it.id.value }) { library ->
                    LibraryRow(
                        library = library,
                        isDefault = library.id == uiState.defaultLibraryId,
                        onToggled = { isDefault -> onDefaultLibraryChanged(library.id.takeIf { isDefault }) },
                    )
                }
                item { Hint(text = stringResource(R.string.settings_default_library_hint)) }
            }

            uiState.server?.let { server -> serverRows(server) }

            item { SectionHeader(text = stringResource(R.string.settings_section_storage)) }
            item { Hint(text = stringResource(R.string.settings_storage_body)) }
            if (uiState.isLoaded) {
                storageRows(uiState.storage)
            } else {
                item { Hint(text = stringResource(R.string.settings_storage_loading)) }
            }
        }
    }
}

/**
 * PRODUCT_SPEC SYNC-001 — "the compatibility result is visible in diagnostics".
 *
 * The interesting line is the capability list, and specifically that most of it says **no**. The
 * handshake confirms nothing it has not probed, and from outside the app "confirmed nothing" and
 * "never ran" look identical — so the header says which of those happened, and the rows say what was
 * asked.
 *
 * The address is printed because this is the screen a user reads to answer "which server am I even
 * talking to" with two profiles on the go. It never reaches a log or a report unless the user has
 * opted in (PRODUCT_SPEC SET-002, Privacy/diagnostics); on screen it is their own address.
 */
private fun LazyListScope.serverRows(server: ServerDiagnostics) {
    item { SectionHeader(text = stringResource(R.string.settings_section_server)) }
    server.serverAddress?.let { address ->
        item { TextRow(labelRes = R.string.settings_server_address, value = address) }
    }
    item {
        TextRow(
            labelRes = R.string.settings_server_version,
            value = server.reportedVersion ?: stringResource(R.string.settings_server_version_unknown),
        )
    }
    item {
        TextRow(
            labelRes = R.string.settings_server_auth,
            value = server.authMethods.joinToString().ifEmpty { stringResource(R.string.settings_server_none) },
        )
    }
    item {
        TextRow(
            labelRes = R.string.settings_server_socket,
            value = stringResource(server.socketStatus.labelRes()),
        )
    }
    item {
        Hint(
            text = stringResource(
                if (server.hasHandshake) {
                    R.string.settings_server_capabilities_hint
                } else {
                    R.string.settings_server_no_handshake
                },
            ),
        )
    }
    if (server.hasHandshake) {
        items(server.allCapabilities, key = { it.first.name }) { (capability, confirmed) ->
            CapabilityRow(name = capability.name, confirmed = confirmed)
        }
    }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

private fun RealtimeStatus.labelRes(): Int = when (this) {
    RealtimeStatus.Idle -> R.string.settings_server_socket_idle
    RealtimeStatus.Connecting -> R.string.settings_server_socket_connecting
    RealtimeStatus.Connected -> R.string.settings_server_socket_connected
    RealtimeStatus.Disconnected -> R.string.settings_server_socket_disconnected
}

@Composable
private fun CapabilityRow(name: String, confirmed: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(
                if (confirmed) R.string.settings_server_confirmed else R.string.settings_server_unconfirmed,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (confirmed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TextRow(labelRes: Int, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the on-device answer to the questions that used to need
 * `adb shell run-as … sqlite3`.
 *
 * The two pairs are the interesting ones. **Libraries stored** against **visible to this profile** is how
 * "unauthorized libraries never appear" becomes checkable: the requirement is that unauthorized rows were
 * never *written*, and a screen that merely hides them looks identical to one that never had them. Same
 * for books. Counts only — printing the names of libraries this profile may not see would be a strange way
 * to demonstrate that they are hidden.
 */
private fun LazyListScope.storageRows(storage: StorageDiagnostics) {
    item {
        ValueRow(
            labelRes = R.string.settings_storage_servers,
            value = storage.serversStored,
            hintRes = R.string.settings_storage_servers_hint,
        )
    }
    item { ValueRow(labelRes = R.string.settings_storage_profiles, value = storage.profilesStored) }
    item {
        ValueRow(
            labelRes = R.string.settings_storage_credentials,
            value = storage.storedCredentials,
            hintRes = R.string.settings_storage_credentials_hint,
        )
    }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    item {
        ValueRow(
            labelRes = R.string.settings_storage_libraries,
            value = storage.librariesStored,
            hintRes = R.string.settings_storage_libraries_hint,
        )
    }
    item { ValueRow(labelRes = R.string.settings_storage_libraries_visible, value = storage.librariesAccessible) }
    item { ValueRow(labelRes = R.string.settings_storage_books, value = storage.booksStored) }
    item { ValueRow(labelRes = R.string.settings_storage_books_visible, value = storage.booksAccessible) }
    item {
        ValueRow(
            labelRes = R.string.settings_storage_books_deleted,
            value = storage.booksSoftDeleted,
            hintRes = R.string.settings_storage_books_deleted_hint,
        )
    }
    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    item { ValueRow(labelRes = R.string.settings_storage_progress, value = storage.progressRecords) }
    item {
        ValueRow(
            labelRes = R.string.settings_storage_progress_unsynced,
            value = storage.unsyncedProgressRecords,
            hintRes = R.string.settings_storage_progress_unsynced_hint,
        )
    }
}

/**
 * PRODUCT_SPEC 6.1 step 9 — the whole row toggles the choice, and that is the only thing it does.
 *
 * It used to open a second browse screen, with the star as a separate target beside it. A device run
 * asked for the opposite — "pressing a library will star it so it is filtered on that library, but not
 * enter it" — and the reason it is the right call is that the browse screen behind it was a duplicate
 * of the home screen. With that screen gone there is one action left, so the row is one target: a
 * `toggleable` row rather than a row containing a button, which is also what gives TalkBack a single
 * stop announcing its own checked state.
 */
@Composable
private fun LibraryRow(
    library: Library,
    isDefault: Boolean,
    onToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = pluralStringResource(R.plurals.home_library_books, library.bookCount, library.bookCount)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = isDefault, role = Role.Switch, onValueChange = onToggled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = library.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
            // Null: the row already carries the name and the checked state, and a second description
            // here would have TalkBack read the library twice.
            contentDescription = null,
            tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ValueRow(labelRes: Int, value: Int, modifier: Modifier = Modifier, hintRes: Int? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            hintRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
