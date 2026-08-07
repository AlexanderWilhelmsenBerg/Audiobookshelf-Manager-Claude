package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.usecase.ServerDiagnostics

/**
 * PRODUCT_SPEC SET-002 / SYNC-001 — the rows both settings tabs are built from.
 *
 * Split from `SettingsScreen.kt` so neither file is a wall: that one owns the layout and the one
 * preference, this one owns the readings and the small row composables they share.
 */

/**
 * What the app is connected to — the Server tab.
 *
 * The address is printed because this is the screen a user reads to answer "which server am I even
 * talking to" with two profiles on the go. It never reaches a log or a report unless the user has opted
 * in (PRODUCT_SPEC SET-002, Privacy/diagnostics); on screen it is their own address.
 */
internal fun LazyListScope.serverInfoRows(server: ServerDiagnostics) {
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
}

/**
 * PRODUCT_SPEC SYNC-001 — "the compatibility result is visible in diagnostics".
 *
 * Under *Testing* rather than beside the address, because it is not a fact about the server so much as a
 * record of what this build asked it — and a device run said so in as many words.
 *
 * The interesting line is that most of the list says **no**. The handshake confirms nothing it has not
 * probed, and from outside the app "confirmed nothing" and "never ran" look identical — so the header
 * says which of those happened, and the rows say what was asked.
 */
internal fun LazyListScope.capabilityRows(server: ServerDiagnostics) {
    item { SubHeader(text = stringResource(R.string.settings_section_capabilities)) }
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
internal fun LazyListScope.storageRows(storage: StorageDiagnostics) {
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
internal fun TextRow(labelRes: Int, value: String, modifier: Modifier = Modifier) {
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
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** A heading *inside* a section, so two blocks read as parts of it rather than as peers. */
@Composable
internal fun SubHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
