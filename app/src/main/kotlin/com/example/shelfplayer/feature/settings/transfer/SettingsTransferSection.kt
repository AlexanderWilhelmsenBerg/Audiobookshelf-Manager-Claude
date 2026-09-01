package com.example.shelfplayer.feature.settings.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.settings.SettingsImport

/**
 * PRODUCT_SPEC SET-001 — export and import, on the About tab.
 *
 * Self-contained, with its ViewModel as a defaulted parameter, for the reason `EventLogSheet` is: adding
 * two launchers and a message to `SettingsScreen`'s signature would describe in the caller something the
 * caller cannot do anything with. A test hands in its own ViewModel.
 */
@Composable
fun SettingsTransferSection(modifier: Modifier = Modifier, viewModel: SettingsTransferViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.settings_transfer_saved)

    // The document produced by the last `export`, waiting for the location the user is choosing. Held here
    // rather than in the ViewModel so that the text — which names every server this device knows — exists
    // only while a picker is open. See `SettingsTransferViewModel`.
    val pending = remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(CreateDocument(SettingsFile.MIME_TYPE)) { uri ->
        val document = pending.value
        pending.value = null
        // A null uri is the user backing out of the picker, which is not a failure and must not be
        // reported as one.
        if (uri == null || document == null) return@rememberLauncherForActivityResult
        val written = SettingsFile.write(context.contentResolver, uri, document)
        viewModel.exportFinished(
            error = (written as? AppResult.Failure)?.error?.summary,
            savedMessage = savedMessage,
        )
    }
    val open = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val text = SettingsFile.read(context.contentResolver, uri)) {
            is AppResult.Failure -> viewModel.failed(text.error.summary)
            is AppResult.Success -> viewModel.import(text.value)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.export { document, suggestedFileName ->
                        pending.value = document
                        create.launch(suggestedFileName)
                    }
                },
                enabled = !state.isBusy,
            ) {
                Text(text = stringResource(R.string.settings_transfer_export))
            }
            OutlinedButton(
                // `*/*` alongside the JSON type on purpose: a provider that reports an exported file as
                // `application/octet-stream` — several do, for anything they do not recognise — would
                // otherwise grey out the user's own settings file in the picker.
                onClick = { open.launch(arrayOf(SettingsFile.MIME_TYPE, "*/*")) },
                enabled = !state.isBusy,
            ) {
                Text(text = stringResource(R.string.settings_transfer_import))
            }
        }
        TransferMessage(state = state, onShown = viewModel::messageShown)
    }
}

/**
 * What the last export or import did, in a sentence, announced to a screen reader.
 *
 * The import's counts are built here rather than in the ViewModel because they need pluralisation, and a
 * ViewModel has no resources. The skipped count is *always* shown when it is non-zero: on a fresh install
 * every per-account preference is skipped, and a bare "settings imported" would be claiming more than
 * happened.
 */
@Composable
private fun TransferMessage(state: SettingsTransferUiState, onShown: () -> Unit, modifier: Modifier = Modifier) {
    val text = state.message ?: state.imported?.let { importedSentence(it) } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    // Read once. Without this the sentence outlives the action it describes, so a later visit to the tab
    // opens on "settings imported" from a session the user has forgotten.
    DisposableEffect(text) { onDispose(onShown) }
}

@Composable
internal fun importedSentence(imported: SettingsImport): String {
    // A file with no server in it is a legitimate export — somebody who signed out first — and "including
    // 0 server addresses" is what a plural with a zero case would say. Two strings instead.
    val done = if (imported.serverUrls.isEmpty()) {
        stringResource(R.string.settings_transfer_imported)
    } else {
        pluralStringResource(
            R.plurals.settings_transfer_imported_servers,
            imported.serverUrls.size,
            imported.serverUrls.size,
        )
    }
    if (imported.profilePreferencesSkipped == 0) return done
    val skipped = pluralStringResource(
        R.plurals.settings_transfer_skipped,
        imported.profilePreferencesSkipped,
        imported.profilePreferencesSkipped,
    )
    return "$done $skipped"
}

/**
 * PRODUCT_SPEC SET-001 / AUTH-001 — "browse for settings", under the server address.
 *
 * The owner asked for the settings file to be *found* at startup and offered here. It cannot be found —
 * [SettingsFile] records why, and the reason is a permission this app deliberately does not hold — so this
 * is the other half of what they asked for: a button, in the one place a fresh install passes through.
 *
 * Importing here does **not** sign anybody in and does not create a server. It fills the address field
 * with the first address the file names and lets the ordinary probe run, so what the user reads before
 * typing a password describes the server now rather than when the file was written.
 */
@Composable
fun ImportSettingsButton(
    onServerPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val text = SettingsFile.read(context.contentResolver, uri)) {
            is AppResult.Failure -> viewModel.failed(text.error.summary)
            is AppResult.Success -> viewModel.import(text.value) { imported ->
                imported.serverUrls.firstOrNull()?.let(onServerPicked)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { open.launch(arrayOf(SettingsFile.MIME_TYPE, "*/*")) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.sign_in_import_settings))
        }
        Text(
            text = stringResource(R.string.sign_in_import_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TransferMessage(state = state, onShown = viewModel::messageShown)
    }
}
