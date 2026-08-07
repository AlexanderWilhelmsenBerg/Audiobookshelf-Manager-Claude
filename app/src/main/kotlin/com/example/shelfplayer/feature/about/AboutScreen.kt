package com.example.shelfplayer.feature.about

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.usecase.ServerDiagnostics

@Composable
fun AboutRoute(onNavigateUp: () -> Unit, modifier: Modifier = Modifier, viewModel: AboutViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AboutScreen(uiState = uiState, onNavigateUp = onNavigateUp, modifier = modifier)
}

/**
 * PRODUCT_SPEC SYNC-001 / SET-002 — what the app knows about itself and about the server.
 *
 * Split out of Settings on a device run's instruction, and the split is the right one: Settings holds
 * a decision the user makes (which library to open on), and everything here is a *reading*. A screen
 * that mixes the two teaches the user that scrolling past their preferences is normal.
 *
 * Everything below the Testing heading exists to make an acceptance case checkable on a device rather
 * than through `adb`. It is deliberately grouped and deliberately labelled as such: these are numbers
 * to verify a build against, not settings to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(uiState: AboutUiState, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.about_title)) },
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
            item { SectionHeader(text = stringResource(R.string.about_section_app)) }
            item { TextRow(labelRes = R.string.about_version, value = uiState.versionName) }
            item { Hint(text = stringResource(R.string.about_phase)) }

            item { SectionHeader(text = stringResource(R.string.about_section_testing)) }
            item { Hint(text = stringResource(R.string.about_testing_body)) }

            uiState.server?.let { server -> serverRows(server) }

            item { SubHeader(text = stringResource(R.string.settings_section_storage)) }
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
    item { SubHeader(text = stringResource(R.string.settings_section_server)) }
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

/** A heading *inside* Testing, so the two diagnostic blocks read as parts of it rather than as peers. */
@Composable
private fun SubHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
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
