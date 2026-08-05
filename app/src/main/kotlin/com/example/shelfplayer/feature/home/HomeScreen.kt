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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfErrorState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.feature.library.BookCard
import com.example.shelfplayer.feature.library.BookSortRow

/**
 * PRODUCT_SPEC 16.4 — `*Route` wires navigation and state; `*Screen` is a pure function of its
 * arguments, which is what makes it previewable and screenshot-testable without Hilt.
 */
@Composable
fun HomeRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onLibrarySelected: (LibraryId) -> Unit,
    onProfilesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onSignInSelected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // PRODUCT_SPEC LIB-001 — a profile that has never synced gets one attempt without being asked. Keyed
    // on the profile so switching accounts gives the new one its chance too.
    LaunchedEffect(uiState.profile?.id) { viewModel.onVisible() }

    HomeScreen(
        uiState = uiState,
        actions = HomeActions(
            onBookSelected = onBookSelected,
            onLibrarySelected = onLibrarySelected,
            onQueryChanged = viewModel::onQueryChanged,
            onOrderChanged = viewModel::onOrderChanged,
            onRefresh = viewModel::refresh,
            onProfilesSelected = onProfilesSelected,
            onSettingsSelected = onSettingsSelected,
            onSignInSelected = onSignInSelected,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(uiState: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                actions = {
                    IconButton(
                        onClick = actions.onRefresh,
                        enabled = !uiState.isRefreshing,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.home_refresh),
                        )
                    }
                    IconButton(onClick = actions.onProfilesSelected) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = stringResource(R.string.home_profiles),
                        )
                    }
                    IconButton(onClick = actions.onSettingsSelected) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val refreshingLabel = stringResource(R.string.home_sync_running)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // PRODUCT_SPEC LIB-001 — sync status is "visible but non-blocking". A bar above the content
            // rather than a spinner in place of it: the cached library is what the user came for, and a
            // sync of a real library is an N+1 over every item.
            if (uiState.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = refreshingLabel
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
            HomeContent(uiState = uiState, actions = actions)
        }
    }
}

@Composable
private fun HomeContent(uiState: HomeUiState, actions: HomeActions) {
    val content = Modifier.fillMaxSize()

    when {
        // The only blocking state, and it blocks on Room rather than on the network: without it a cold
        // start with a saved profile flashes "No server connected" before the profile arrives.
        !uiState.isLoaded ->
            ShelfLoadingState(
                label = stringResource(R.string.home_loading),
                modifier = content,
            )

        // Checked before the error branch: with no profile there is nothing to refresh, and
        // "add a server" is a more useful thing to read than whatever the last attempt reported.
        uiState.profile == null ->
            ShelfEmptyState(
                title = stringResource(R.string.home_no_profile_title),
                body = stringResource(R.string.home_no_profile_body),
                actionLabel = stringResource(R.string.home_sign_in),
                onAction = actions.onSignInSelected,
                modifier = content,
            )

        uiState.showsLibraries -> LibraryList(uiState = uiState, actions = actions, modifier = content)

        else -> BookShelf(uiState = uiState, actions = actions, modifier = content)
    }
}

/**
 * PRODUCT_SPEC LIB-002 — the shelf: every accessible book, most recently played first.
 *
 * Search and sort appear once there is something to search. A search field above "No books yet" offers
 * the user a way to narrow nothing down.
 */
@Composable
private fun BookShelf(uiState: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (uiState.books.isNotEmpty() || uiState.query.isNotEmpty()) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = actions.onQueryChanged,
                singleLine = true,
                label = { Text(text = stringResource(R.string.home_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            BookSortRow(selected = uiState.order, onOrderChanged = actions.onOrderChanged)
        }

        when {
            uiState.books.isEmpty() && uiState.query.isNotEmpty() ->
                ShelfEmptyState(
                    title = stringResource(R.string.home_no_matches_title),
                    body = stringResource(R.string.home_no_matches_body),
                    modifier = Modifier.fillMaxSize(),
                )

            uiState.books.isEmpty() -> EmptyOrFailed(uiState = uiState, onRefresh = actions.onRefresh)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ShelfHeader(uiState) }
                items(items = uiState.books, key = { it.id.value }) { book ->
                    BookCard(book = book, onClick = { actions.onBookSelected(book.id) })
                }
            }
        }
    }
}

@Composable
private fun LibraryList(uiState: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    if (uiState.libraries.isEmpty()) {
        EmptyOrFailed(uiState = uiState, onRefresh = actions.onRefresh, modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ShelfHeader(uiState) }
        items(items = uiState.libraries, key = { it.id.value }) { library ->
            LibraryCard(library = library, onClick = { actions.onLibrarySelected(library.id) })
        }
    }
}

/**
 * The empty and error states share a branch because they answer the same question — why is the shelf
 * bare — and the recorded error is the better answer whenever there is one.
 */
@Composable
private fun EmptyOrFailed(uiState: HomeUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val content = modifier.fillMaxSize()
    if (uiState.error != null) {
        ShelfErrorState(
            title = stringResource(R.string.home_error_title),
            body = uiState.error.summary,
            technicalCode = uiState.error.code,
            actionLabel = stringResource(R.string.home_refresh),
            onAction = onRefresh,
            modifier = content,
        )
    } else {
        ShelfEmptyState(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            actionLabel = stringResource(R.string.home_refresh),
            onAction = onRefresh,
            modifier = content,
        )
    }
}

@Composable
private fun ShelfHeader(uiState: HomeUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // PRODUCT_SPEC AUTH-004 — the mark is shown, not just enforced. It sits above the shelf rather
        // than replacing it, because the cached content is still there and still playable; what has
        // stopped is new network work.
        if (uiState.profile?.requiresReauthentication == true) {
            Text(
                text = stringResource(R.string.home_needs_sign_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = uiState.syncStatusLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCard(library: Library, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val openLabel = pluralStringResource(R.plurals.home_library_books, library.bookCount, library.bookCount)
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
    -> {
        // The count follows what is on screen. In library mode that is the libraries' totals; on the
        // shelf it is the rows the user can actually see, which a search narrows.
        val total = if (showsLibraries) libraries.sumOf(Library::bookCount) else books.size
        pluralStringResource(R.plurals.home_library_books, total, total)
    }
}
