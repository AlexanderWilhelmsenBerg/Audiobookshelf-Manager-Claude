package com.example.shelfplayer.feature.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.feature.library.BookCard
import com.example.shelfplayer.feature.library.BookSortRow

/**
 * PRODUCT_SPEC 16.4 — `*Route` wires navigation and state; `*Screen` is a pure function of its
 * arguments, which is what makes it previewable and screenshot-testable without Hilt.
 */
@Composable
fun HomeRoute(
    onBookSelected: (LibraryItemId) -> Unit,
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // PRODUCT_SPEC 6.1 step 9 — a shelf narrowed to one library is titled with it.
                        // Showing "Library" over a subset of the profile's books reads as books having
                        // gone missing, and the setting that caused it is two screens away.
                        Text(text = uiState.scopedTo?.name ?: stringResource(R.string.home_title))
                        ServerStatusDot(
                            status = uiState.serverStatus,
                            isOffline = uiState.isOffline,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    // PRODUCT_SPEC LIB-001 — sync is "visible but non-blocking", and one indicator is
                    // enough to say so. There used to be two: a bar across the top and the pull-to-refresh
                    // spinner, for a single operation. The button that starts the sync is the honest place
                    // to show it running.
                    val syncing = uiState.syncStatus == SyncStatus.Syncing
                    IconButton(onClick = actions.onRefresh, enabled = !syncing) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(
                                if (syncing) R.string.home_sync_running else R.string.home_refresh,
                            ),
                            modifier = Modifier
                                .rotate(refreshSpin(syncing))
                                .semantics { if (syncing) liveRegion = LiveRegionMode.Polite },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HomeContent(uiState = uiState, actions = actions)
        }
    }
}

/**
 * A continuous turn while a sync runs, and a still arrow when it does not.
 *
 * `infiniteRepeatable` rather than a progress value: the sync has no honest percentage to report. It is
 * an N+1 over a library whose size is only known after the first request, and inventing a fraction to
 * fill a bar with would be a more confident lie than a spinner.
 */
@Composable
private fun refreshSpin(syncing: Boolean): Float {
    if (!syncing) return 0f
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-rotation",
    )
    return angle
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

        else -> BookShelf(uiState = uiState, actions = actions, modifier = content)
    }
}

/**
 * PRODUCT_SPEC LIB-002 — the shelf: every accessible book, most recently played first.
 *
 * Search and sort appear once there is something to search. A search field above "No books yet" offers
 * the user a way to narrow nothing down.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

            // PRODUCT_SPEC LIB-001 — "pull-to-refresh refreshes the active library", in as many words.
            // The toolbar button stays: pull is a gesture some users never discover, and TalkBack has no
            // sensible way to perform one.
            // The gesture without the spinner. `PullToRefreshBox` draws its own indicator whenever
            // `isRefreshing` is true, which for an automatic sync meant a wheel appearing over the shelf
            // that the user never asked for — and a second one, since the refresh button already turns.
            // An empty indicator slot keeps the pull working and leaves the button to say so.
            else -> PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = actions.onRefresh,
                indicator = {},
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
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
}

/**
 * The empty and error states share a branch because they answer the same question — why is the shelf
 * bare — and the recorded error is the better answer whenever there is one.
 *
 * PRODUCT_SPEC LIB-002 puts offline ahead of both. A failure that happened with no network is not the
 * server refusing anything, and saying so sends the user to check a server that is fine.
 */
@Composable
private fun EmptyOrFailed(uiState: HomeUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val content = modifier.fillMaxSize()
    if (uiState.isOffline) {
        ShelfEmptyState(
            title = stringResource(R.string.home_offline_title),
            body = stringResource(R.string.home_offline_body),
            modifier = content,
        )
    } else if (uiState.error != null) {
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

/**
 * PRODUCT_SPEC LIB-002 / 14.4 — the reachability indicator, asked for from a device run.
 *
 * A dot rather than a word because it sits beside a title, and a `contentDescription` rather than only
 * a colour because PRODUCT_SPEC 21 requires the state to be available to TalkBack and to a user who
 * cannot distinguish red from green — a colour-only indicator says nothing to either.
 *
 * Offline outranks the server's own state: with no network the app has learned nothing about the
 * server, and showing it red would blame the wrong thing.
 */
@Composable
private fun ServerStatusDot(status: ServerStatus, isOffline: Boolean, modifier: Modifier = Modifier) {
    val effective = if (isOffline) ServerStatus.Unknown else status
    // Fixed colours rather than the theme's, and that is the point of them.
    //
    // `colorScheme.primary` is whatever the palette says — light blue in this app's dark scheme, a
    // muted green in light — and a status light has to mean the same thing in both. These two are
    // chosen to stay legible on either background: a device run reported the dark-mode dot reading as
    // blue and the light-mode one as too dark to call green.
    val colour = when (effective) {
        ServerStatus.Reachable -> if (isSystemInDarkTheme()) ReachableDark else ReachableLight
        ServerStatus.Unreachable -> if (isSystemInDarkTheme()) UnreachableDark else UnreachableLight
        ServerStatus.Unknown -> MaterialTheme.colorScheme.outlineVariant
    }
    val description = stringResource(
        when {
            isOffline -> R.string.home_server_offline
            effective == ServerStatus.Reachable -> R.string.home_server_reachable
            effective == ServerStatus.Unreachable -> R.string.home_server_unreachable
            else -> R.string.home_server_unknown
        },
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .background(color = colour, shape = CircleShape)
            .semantics { contentDescription = description },
    )
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
        // PRODUCT_SPEC LIB-002 / 6.3 — the shelf stays; only the caveat is added. The cached library is
        // complete as far as it goes, and hiding it because the radio is off would be the opposite of
        // "offline playback must be complete and reliable".
        Text(
            text = if (uiState.isOffline) {
                stringResource(R.string.home_offline_caption)
            } else {
                uiState.syncStatusLabel()
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeUiState.syncStatusLabel(): String = when (syncStatus) {
    SyncStatus.Syncing -> stringResource(R.string.home_sync_running)
    SyncStatus.Failed -> stringResource(R.string.home_sync_failed)
    SyncStatus.NeverSynced -> stringResource(R.string.home_sync_never)
    // The count follows what is on screen: the rows the user can actually see, which a search narrows.
    SyncStatus.Succeeded -> pluralStringResource(R.plurals.home_library_books, books.size, books.size)
    // PRODUCT_SPEC LIB-001 — a sync that could not reach some items says so rather than claiming a clean
    // run. The count is still what is on screen; the caveat is that it is not all of it.
    SyncStatus.PartiallySucceeded -> pluralStringResource(R.plurals.home_sync_partial, books.size, books.size)
}

/**
 * PRODUCT_SPEC 21 — a status light that means the same thing in both themes.
 *
 * Green and red at luminances that stay readable on a dark and on a light surface respectively. They
 * are not in the design-system palette on purpose: they are semaphore colours rather than brand ones,
 * and pulling them from `colorScheme` is what made the dot blue in dark mode.
 *
 * Colour is never the only signal — every state also carries a `contentDescription`.
 */
private val ReachableDark = Color(0xFF6EE7A0)
private val ReachableLight = Color(0xFF15803D)
private val UnreachableDark = Color(0xFFFF8A80)
private val UnreachableLight = Color(0xFFC62828)
