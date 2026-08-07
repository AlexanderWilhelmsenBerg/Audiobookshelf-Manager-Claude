package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.library.Library

@Composable
fun SettingsRoute(
    onLibrarySelected: (LibraryId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onLibrarySelected = onLibrarySelected,
        onDefaultLibraryChanged = viewModel::onDefaultLibraryChanged,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onLibrarySelected: (LibraryId) -> Unit,
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
                        onClick = { onLibrarySelected(library.id) },
                        onDefaultChanged = { isDefault ->
                            onDefaultLibraryChanged(library.id.takeIf { isDefault })
                        },
                    )
                }
                item { Hint(text = stringResource(R.string.settings_default_library_hint)) }
            }

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
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the on-device answer to the questions that used to need
 * `adb shell run-as … sqlite3`.
 *
 * The two pairs are the interesting ones. **Libraries stored** against **visible to this profile** is how
 * "unauthorized libraries never appear" becomes checkable: the requirement is that unauthorized rows were
 * never *written*, and a screen that merely hides them looks identical to one that never had them. Same
 * for books. Counts only — printing the names of libraries this profile may not see would be a strange way
 * to demonstrate that they are hidden.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.storageRows(storage: StorageDiagnostics) {
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
 * PRODUCT_SPEC 6.1 step 9 — opening a library and choosing it as the default are two separate targets.
 *
 * The star is not part of the row's click area on purpose. Tapping a library to browse it is the common
 * action by a wide margin; making that same tap also change what the home screen shows would be a
 * setting the user changed without meaning to, and would not survive them tapping a second library.
 */
@Composable
private fun LibraryRow(
    library: Library,
    isDefault: Boolean,
    onClick: () -> Unit,
    onDefaultChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = pluralStringResource(R.plurals.home_library_books, library.bookCount, library.bookCount)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = library.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconToggleButton(
            checked = isDefault,
            onCheckedChange = onDefaultChanged,
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Icon(
                imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                // PRODUCT_SPEC 21 — the label says what the tap will do, and names the library, because
                // a screen of identical "Set as default" buttons is unusable with a screen reader.
                contentDescription = stringResource(
                    if (isDefault) R.string.settings_default_library_clear else R.string.settings_default_library_set,
                    library.name,
                ),
            )
        }
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
