package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfErrorState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Library

/**
 * PRODUCT_SPEC 16.4 — `*Route` wires navigation and state; `*Screen` is a pure function of its
 * arguments, which is what makes it previewable and screenshot-testable without Hilt.
 */
@Composable
fun HomeRoute(
    onLibrarySelected: (LibraryId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        onLibrarySelected = onLibrarySelected,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onLibrarySelected: (LibraryId) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !uiState.isRefreshing,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.home_refresh),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            uiState.isInitialLoad ->
                ShelfLoadingState(
                    label = stringResource(R.string.home_loading),
                    modifier = content,
                )

            uiState.libraries.isEmpty() && uiState.error != null ->
                ShelfErrorState(
                    title = stringResource(R.string.home_error_title),
                    body = uiState.error.summary,
                    technicalCode = uiState.error.code,
                    actionLabel = stringResource(R.string.home_refresh),
                    onAction = onRefresh,
                    modifier = content,
                )

            uiState.libraries.isEmpty() ->
                ShelfEmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.home_refresh),
                    onAction = onRefresh,
                    modifier = content,
                )

            else -> LibraryList(
                uiState = uiState,
                onLibrarySelected = onLibrarySelected,
                modifier = content,
            )
        }
    }
}

@Composable
private fun LibraryList(uiState: HomeUiState, onLibrarySelected: (LibraryId) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.home_demo_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = uiState.syncStatusLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items = uiState.libraries, key = { it.id.value }) { library ->
            LibraryCard(library = library, onClick = { onLibrarySelected(library.id) })
        }
    }
}

@Composable
private fun LibraryCard(library: Library, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val openLabel = stringResource(R.string.home_library_books, library.bookCount)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = library.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = openLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeUiState.syncStatusLabel(): String = when (syncStatus) {
    SyncStatus.Syncing -> stringResource(R.string.home_sync_running)
    SyncStatus.Failed -> stringResource(R.string.home_sync_failed)
    SyncStatus.NeverSynced -> stringResource(R.string.home_sync_never)
    SyncStatus.Succeeded,
    SyncStatus.PartiallySucceeded,
    -> stringResource(R.string.home_library_books, libraries.sumOf(Library::bookCount))
}
