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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.example.shelfplayer.core.model.library.Library

@Composable
fun SettingsRoute(
    onAboutSelected: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onDefaultLibraryChanged = viewModel::onDefaultLibraryChanged,
        onAboutSelected = onAboutSelected,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDefaultLibraryChanged: (LibraryId?) -> Unit,
    onAboutSelected: () -> Unit,
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

            item { SectionHeader(text = stringResource(R.string.settings_section_about)) }
            item { NavigationRow(labelRes = R.string.settings_about_row, onClick = onAboutSelected) }
        }
    }
}

/** A row that opens another screen — the only navigation Settings still does. */
@Composable
private fun NavigationRow(labelRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
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
