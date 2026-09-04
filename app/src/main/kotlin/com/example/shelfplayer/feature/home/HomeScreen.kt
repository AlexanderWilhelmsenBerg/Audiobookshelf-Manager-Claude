package com.example.shelfplayer.feature.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfErrorState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.feature.browse.BookCard
import com.example.shelfplayer.feature.browse.BookSortRow
import com.example.shelfplayer.feature.browse.GroupCard
import com.example.shelfplayer.feature.browse.GroupCardEditAction
import com.example.shelfplayer.feature.browse.SeriesCard
import com.example.shelfplayer.ui.gesture.offscreenPage
import com.example.shelfplayer.ui.gesture.rememberEdgeOverspill
import com.example.shelfplayer.ui.glass.LocalPlayerChromeBottomInset
import com.example.shelfplayer.ui.glass.frostedGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * PRODUCT_SPEC 16.4 — `*Route` wires navigation and state; `*Screen` is a pure function of its
 * arguments, which is what makes it previewable and screenshot-testable without Hilt.
 */
@Suppress("LongParameterList") // Route boundary keeps navigation callbacks explicit and type-safe.
@Composable
fun HomeRoute(
    onBookSelected: (LibraryItemId) -> Unit,
    onBookPlaySelected: (LibraryItemId) -> Unit,
    onSeriesSelected: (SeriesId) -> Unit,
    onProfilesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onSignInSelected: () -> Unit,
    playbackMessage: String?,
    onPlaybackMessageShown: () -> Unit,
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
            onBookPlaySelected = onBookPlaySelected,
            onSeriesSelected = onSeriesSelected,
            onGroupSelected = viewModel::onGroupSelected,
            onGenreEditRequested = viewModel::onGenreEditRequested,
            onGenreEditReplacementChanged = viewModel::onGenreEditReplacementChanged,
            onGenreEditConfirmed = viewModel::onGenreEditConfirmed,
            onGenreEditDismissed = viewModel::onGenreEditDismissed,
            onQueryChanged = viewModel::onQueryChanged,
            onSearchToggled = viewModel::onSearchToggled,
            onAxisChanged = viewModel::onAxisChanged,
            onBooksViewChanged = viewModel::onBooksViewChanged,
            onOrderChanged = viewModel::onOrderChanged,
            onFilterChanged = viewModel::onFilterChanged,
            onFocusCleared = viewModel::onFocusCleared,
            onRefresh = viewModel::refresh,
            onProfilesSelected = onProfilesSelected,
            onSettingsSelected = onSettingsSelected,
            onSignInSelected = onSignInSelected,
        ),
        onSwipeStarted = viewModel::onSwipeStarted,
        playbackMessage = playbackMessage,
        onPlaybackMessageShown = onPlaybackMessageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    /** Told the moment a swipe begins, so the neighbouring axes are worth collecting. */
    onSwipeStarted: () -> Unit = {},
    playbackMessage: String? = null,
    onPlaybackMessageShown: () -> Unit = {},
) {
    /*
     * PRODUCT_SPEC 17.3 — *"cached library screen interactive under 1 second"*, made measurable.
     *
     * A cold start reports two times: time to *initial* display, which is the first frame of anything,
     * and time to *full* display, which the application has to declare because only it knows when the
     * screen is worth looking at. Without this call the second is never reported, and 17.3's threshold
     * could only be measured against the first — a frame showing a top bar over an empty list, which is
     * not what "interactive" means to the person holding the phone.
     *
     * `profile` is the condition because it is null until the first real emission: `HomeViewModel`
     * assembles the profile and the content in one `combine`, so a non-null profile means the shelves or
     * the list already have their books. An empty library still reports — the state arrives, it just
     * carries nothing — which matters because a benchmark that waits forever on a device with no books
     * would be worse than no benchmark.
     */
    ReportDrawnWhen { uiState.profile != null }
    val snackbars = remember { SnackbarHostState() }
    val axisPages = HomeAxis.entries
    val pagerState = rememberAxisPager(
        axis = uiState.axis,
        onAxisChanged = actions.onAxisChanged,
        onSwipeStarted = onSwipeStarted,
    )
    // PRODUCT_SPEC 16.2 — a little give at Books and at Genres, with resistance and a spring back.
    val overspill = rememberEdgeOverspill()
    val axisBarMotion = rememberHomeAxisBarMotion()
    /*
     * The capsule's own blur source, and the reason it is not `LocalGlassHazeState`.
     *
     * That state's source is the whole navigation graph — which *contains* this screen, and therefore
     * contains the capsule. An effect asked to blur a source it is part of is asked to blur itself, and
     * what it drew was nothing at all: the frosted pill the design called for simply was not there, while
     * the mini player's glass worked, because the mini player is a sibling of the graph rather than a
     * descendant of it.
     *
     * A nearer state fixes the relationship instead of the symptom. Its source is Home's content, the
     * capsule floats over that content, and the two are siblings in the `Scaffold`.
     */
    val axisBarHaze = remember { HazeState() }
    val systemBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val playerInset = LocalPlayerChromeBottomInset.current.coerceAtLeast(0.dp)
    LaunchedEffect(playbackMessage) {
        val message = playbackMessage ?: return@LaunchedEffect
        snackbars.showSnackbar(message)
        onPlaybackMessageShown()
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .captureHomeAxisBarMotion(axisBarMotion),
        snackbarHost = { SnackbarHost(snackbars) },
        // PRODUCT_SPEC LIB-002 — frosted, like the capsule at the other end of the screen, and sharing its
        // blur source. Both are siblings of the body in the `Scaffold`, so both may read a state whose
        // source is that body; an effect that is a descendant of its own source draws nothing.
        topBar = {
            Column(
                modifier = Modifier.frostedGlass(
                    state = axisBarHaze,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                TopAppBar(
                    // Transparent so the glass is what is seen. `TopAppBar` paints its own container over the
                    // modifier's background otherwise, and the blur would be hidden beneath it.
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        BoxWithConstraints {
                            // Five 48 dp actions can leave less than a logo's width on a compact phone. The
                            // title and status are information; the adjacent, duplicate brand mark is not.
                            val showBrandMark = maxWidth >= HOME_MARK_MIN_TITLE_WIDTH &&
                                LocalDensity.current.fontScale <= HOME_MARK_MAX_FONT_SCALE
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (showBrandMark) {
                                    // Decorative: the adjacent title already names the app once for TalkBack.
                                    // A dedicated small WebP avoids decoding the launcher master.
                                    Image(
                                        painter = painterResource(R.drawable.bookwave_logo_header),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .testTag(HOME_MARK_TEST_TAG)
                                            .size(HOME_MARK_SIZE)
                                            .padding(end = 8.dp),
                                    )
                                }
                                // PRODUCT_SPEC 6.1 step 9 — a shelf narrowed to one library is titled with it.
                                // Showing "Library" over a subset of the profile's books reads as books having
                                // gone missing, and the setting that caused it is two screens away.
                                Text(
                                    text = uiState.scopedTo?.name ?: stringResource(R.string.home_title),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ServerStatusDot(
                                    status = uiState.serverStatus,
                                    isOffline = uiState.isOffline,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    },
                    actions = {
                        // PRODUCT_SPEC LIB-002 / 21 — search is a button. A permanent field costs a row of
                        // vertical space on every screen to serve the one visit in ten that is a search, and
                        // a device run asked for it back.
                        if (uiState.profile != null) {
                            val searchLabel = if (uiState.isSearching) {
                                R.string.home_search_close
                            } else {
                                R.string.home_search_open
                            }
                            IconButton(onClick = actions.onSearchToggled) {
                                Icon(
                                    imageVector = if (uiState.isSearching) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = stringResource(searchLabel),
                                )
                            }
                        }
                        if (uiState.axis == HomeAxis.Books && uiState.profile != null) {
                            HomeViewToggle(current = uiState.booksView, onChanged = actions.onBooksViewChanged)
                        }
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
                HomeFilters(uiState = uiState, actions = actions)
            }
        },
        // PRODUCT_SPEC LIB-002 — the axes live in a bottom bar rather than in tabs on a second screen.
        // Hidden while there is no profile: four destinations over "No server connected" are four ways
        // to reach the same empty screen.
        bottomBar = {
            if (uiState.profile != null) {
                HomeAxisBar(
                    current = uiState.axis,
                    // PRODUCT_SPEC 16.2 — where the pill is, as a continuous position rather than an
                    // index. *"On the navigation bar the selection should move to the next button when
                    // pulling over"* — so the pill is at 1.4 when the drag is 40% of the way from Series
                    // to Authors, and it comes back to 1.0 by itself if the drag springs back.
                    position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                    onAxisChanged = actions.onAxisChanged,
                    motion = axisBarMotion,
                    haze = axisBarHaze,
                    bottomInset = systemBarInset + playerInset,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // No top padding: the shelf runs under the bar, which is what gives the glass something
                // to refract. The bar's measured height reaches the list as content padding instead —
                // `Scaffold` measures its top bar before the body, the same property the capsule relies
                // on at the other end.
                // Outside the offset below, so it reads the drag rather than the drag's result.
                .nestedScroll(overspill.connection)
                .hazeSource(state = axisBarHaze),
        ) {
            /*
             * The chrome's height reaches the list as **content** padding, not as layout padding.
             *
             * The difference is the whole behaviour the owner asked for. Layout padding shrinks the
             * viewport, so a mini player appearing would shove the shelf upwards under the reader's
             * thumb. Content padding leaves every item where it is and lengthens the scroll range
             * instead: the list simply gains somewhere further to go, and the last card can now come to
             * rest just above the capsule rather than behind it.
             */
            /*
             * `innerPadding`'s bottom **is** the capsule, measured.
             *
             * This was `AXIS_BAR_OCCUPIED_HEIGHT + systemBarInset + playerInset`, with a comment claiming
             * the list needs the number before the capsule has been laid out. That was wrong: Material3's
             * `Scaffold` subcomposes and measures its bottom bar *before* the body — `Scaffold.kt` sets
             * `bottom = bottomBarHeight.toDp()` — so the real height is available on the first pass, and
             * it already contains the capsule's own margins, the system bar and the mini player.
             *
             * Which is what lets the capsule grow: at a large font scale its tabs stack and it gets
             * taller, and the shelf's scroll range follows with nothing to keep in step.
             */
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(x = overspill.offsetPx.roundToInt(), y = 0) },
                // The platform's stretch would consume the delta at the edges before `overspill` could see
                // it, and it distorts pixels rather than moving the page. This owns both ends instead.
                overscrollEffect = null,
                // One page composed either side, so the reveal has something in it from the first
                // pixel of the drag rather than after a recomposition the finger has already outrun.
                beyondViewportPageCount = 1,
                key = { page -> axisPages[page].name },
            ) { page ->
                val axis = axisPages[page]
                Box(modifier = Modifier.offscreenPage(isCurrent = page == pagerState.currentPage)) {
                    HomeContent(
                        uiState = uiState.forAxis(axis),
                        actions = actions,
                        // Every page runs under the glass, not just the visible one — a page revealed
                        // mid-drag has to already be laid out the way it will look when it lands.
                        topInset = innerPadding.calculateTopPadding(),
                        bottomInset = innerPadding.calculateBottomPadding(),
                    )
                }
            }
        }
    }
    GenreEditDialog(state = uiState.genreEdit, actions = actions)
}

/**
 * PRODUCT_SPEC MGR-008 — a deliberately explicit, non-transactional genre consolidation flow.
 *
 * The dialog cannot be dismissed while writes are running. Earlier books may already be committed, so
 * making the progress UI disappear would also discard the only honest place to report partial success.
 */
@Composable
private fun GenreEditDialog(state: GenreEditUiState, actions: HomeActions) {
    when (state) {
        GenreEditUiState.Hidden -> Unit
        is GenreEditUiState.Confirming -> GenreEditConfirmationDialog(state.request, actions)
        is GenreEditUiState.Running -> GenreEditProgressDialog(state.request)
        is GenreEditUiState.Complete -> GenreEditSummaryDialog(state, actions.onGenreEditDismissed)
        is GenreEditUiState.Failed -> GenreEditFailureDialog(state, actions.onGenreEditDismissed)
    }
}

@Composable
private fun GenreEditConfirmationDialog(request: GenreEditRequest, actions: HomeActions) {
    AlertDialog(
        onDismissRequest = actions.onGenreEditDismissed,
        title = { Text(text = stringResource(R.string.genre_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GenreEditRequestSummary(request)
                Text(text = stringResource(R.string.genre_edit_explanation))
                OutlinedTextField(
                    value = request.replacementGenres,
                    onValueChange = actions.onGenreEditReplacementChanged,
                    label = { Text(text = stringResource(R.string.genre_edit_replacements_label)) },
                    supportingText = { Text(text = stringResource(R.string.genre_edit_replacements_supporting)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onGenreEditConfirmed,
                enabled = request.hasReplacementGenres,
            ) {
                Text(text = stringResource(R.string.genre_edit_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onGenreEditDismissed) {
                Text(text = stringResource(R.string.genre_edit_cancel))
            }
        },
    )
}

@Composable
private fun GenreEditProgressDialog(request: GenreEditRequest) {
    val progressDescription = stringResource(R.string.genre_edit_progress)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = stringResource(R.string.genre_edit_progress_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GenreEditRequestSummary(request)
                GenreEditChangeSummary(request)
                Row(
                    modifier = Modifier.semantics {
                        contentDescription = progressDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(text = progressDescription)
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun GenreEditSummaryDialog(state: GenreEditUiState.Complete, onDismiss: () -> Unit) {
    val summary = state.summary
    val partial = summary.draftConflictCount > 0 ||
        summary.failedCount > summary.draftConflictCount ||
        summary.locallyStaleCount > 0 ||
        summary.unprocessedCount > 0 ||
        summary.stopReason != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (partial) R.string.genre_edit_result_partial else R.string.genre_edit_result_complete,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.genre_edit_result_change,
                        state.request.sourceGenre,
                        state.request.replacementSummary,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(R.string.genre_edit_summary_matched, summary.matchedCount))
                Text(text = stringResource(R.string.genre_edit_summary_updated, summary.updatedCount))
                Text(text = stringResource(R.string.genre_edit_summary_unchanged, summary.unchangedCount))
                Text(
                    text = stringResource(
                        R.string.genre_edit_summary_draft_conflicts,
                        summary.draftConflictCount,
                    ),
                )
                Text(
                    text = stringResource(
                        R.string.genre_edit_summary_failed,
                        (summary.failedCount - summary.draftConflictCount).coerceAtLeast(0),
                    ),
                )
                Text(text = stringResource(R.string.genre_edit_summary_stale, summary.locallyStaleCount))
                Text(text = stringResource(R.string.genre_edit_summary_unprocessed, summary.unprocessedCount))
                Text(
                    text = stringResource(
                        R.string.genre_edit_summary_stop_reason,
                        summary.stopReason?.summary ?: stringResource(R.string.genre_edit_summary_no_stop),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.genre_edit_close))
            }
        },
    )
}

@Composable
private fun GenreEditFailureDialog(state: GenreEditUiState.Failed, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.genre_edit_result_not_started)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenreEditRequestSummary(state.request)
                GenreEditChangeSummary(state.request)
                Text(text = state.error.summary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.genre_edit_close))
            }
        },
    )
}

@Composable
private fun GenreEditRequestSummary(request: GenreEditRequest) {
    Text(text = stringResource(R.string.genre_edit_source, request.sourceGenre))
    Text(
        text = pluralStringResource(
            R.plurals.genre_edit_cached_count,
            request.cachedMatchCount,
            request.cachedMatchCount,
        ),
    )
}

@Composable
private fun GenreEditChangeSummary(request: GenreEditRequest) {
    if (request.replacementSummary.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.genre_edit_result_change,
                request.sourceGenre,
                request.replacementSummary,
            ),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** PRODUCT_SPEC LIB-002 — the four browse axes, one tap apart. */
/**
 * PRODUCT_SPEC 16.2 — the axes as pages, kept in step with the axis the view model holds.
 *
 * ### Why a pager rather than the threshold gesture it replaces
 *
 * The first version watched a horizontal drag and, on release past 56dp, called `onAxisChanged`. The page
 * changed but nothing moved with the finger: no reveal, no snap back, and the capsule's pill jumped a
 * quarter of the bar in one frame. Reported from a device as *"pulling at one side should reveal the next
 * view and releasing should animate it over"*, and *"pull a little and release and it won't switch page,
 * just snap back"*.
 *
 * A `HorizontalPager` is all of that and none of it is written here: the page follows the finger, the
 * fling decides between settling forward and springing back, and `currentPageOffsetFraction` is the
 * continuous position the capsule's pill needs.
 *
 * ### The two directions, and why they do not chase each other
 *
 * A **tap** on the capsule changes the axis in the view model, and the effect below animates the pager to
 * it. A **settled swipe** reports its axis back. Each is a no-op for the other's cause — the tap's effect
 * finds the pager already there once the state arrives, and the swipe's report finds the axis already
 * changed — so there is no loop to break.
 *
 * `settledPage` and not `currentPage`: the latter flips at the half-way point of a drag that may still
 * spring back, which would change the axis for a gesture the user abandoned.
 *
 * Extracted from `HomeScreen` because that composable was already at detekt's cyclomatic limit — #74 said
 * so in as many words, and this is the change that would have tripped it.
 */
@Composable
private fun rememberAxisPager(
    axis: HomeAxis,
    onAxisChanged: (HomeAxis) -> Unit,
    onSwipeStarted: () -> Unit,
): PagerState {
    val pages = HomeAxis.entries
    val state = rememberPagerState(
        initialPage = pages.indexOf(axis).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    LaunchedEffect(axis) {
        val target = pages.indexOf(axis).coerceAtLeast(0)
        if (target != state.currentPage) state.animateScrollToPage(target)
    }
    LaunchedEffect(state, onAxisChanged) {
        snapshotFlow { state.settledPage }
            .map { page -> pages[page.coerceIn(pages.indices)] }
            .filter { settled -> settled != axis }
            .collect(onAxisChanged)
    }
    // The neighbouring axes are worth collecting from the first drag onward — see
    // `HomeViewModel.swipePreview` for why it latches rather than following the gesture.
    LaunchedEffect(state, onSwipeStarted) {
        snapshotFlow { state.isScrollInProgress }.filter { it }.collect { onSwipeStarted() }
    }
    return state
}

@Composable
private fun HomeAxisBar(
    current: HomeAxis,
    /**
     * PRODUCT_SPEC 16.2 — where the highlight is, as a **fractional** index into the four places.
     *
     * `1.4` means the drag is 40% of the way from the second place to the third, and the pill is drawn
     * there. It is the pager's own position rather than a copy of it, so releasing mid-drag brings the
     * pill back with the page and no second animation has to be kept in step with the first.
     */
    position: Float,
    onAxisChanged: (HomeAxis) -> Unit,
    motion: HomeAxisBarMotionState,
    haze: HazeState,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    /*
     * Above this font scale the label stops fitting beside its icon and starts ellipsising.
     *
     * Four tabs across a 360dp phone give each about 73dp; the icon and its gap take 22dp of that, so the
     * label has roughly 51dp. At 200% it wants nearer 90dp, and what the reader saw was an icon and a
     * single letter. Stacking is what Material's own `NavigationBarItem` does, and it buys the label the
     * whole width of its tab.
     *
     * A threshold rather than always stacking: at the default scale the side-by-side arrangement is the
     * one the design asked for, and it should stay.
     */
    val isStacked = LocalDensity.current.fontScale >= STACK_LABEL_FONT_SCALE
    BoxWithConstraints(
        modifier = modifier
            .followHomeAxisBarMotion(motion)
            .fillMaxWidth()
            .padding(horizontal = AXIS_BAR_SIDE_MARGIN)
            // The system navigation bar and the mini player both sit below the capsule, and both have to
            // be cleared here. `NavigationBar` used to apply the first of those itself; a hand-rolled
            // capsule inherits nothing, so an unpadded one lands under the gesture handle.
            .padding(top = AXIS_BAR_VERTICAL_MARGIN, bottom = AXIS_BAR_VERTICAL_MARGIN + bottomInset)
            // A floor, not a fixed height. A stacked tab at a large font scale is taller than 46dp, and a
            // capsule that refused to grow would clip the label it just moved to make room for.
            .heightIn(min = AXIS_BAR_HEIGHT)
            .clip(CircleShape)
            .frostedGlass(
                state = haze,
                backgroundColor = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            ),
    ) {
        // One geometry for the pill and the tabs. They were computed separately — the pill from the full
        // width, the tabs from the width less the row's own padding — so the highlight drifted a little
        // further right of its label with every tab. Both now measure the same quarter.
        val tabWidth = maxWidth / HomeAxis.entries.size.toFloat()
        /*
         * Read straight from the drag, with no animation of its own.
         *
         * It was `animateDpAsState` over the selected *index*, which is right for a tap and wrong for a
         * drag: a spring chasing a finger lags it, and on release two animations — the page settling and
         * the pill springing — would have to agree about where they were going. The pager already
         * animates [position] on both settle and spring-back, so following it exactly is both simpler
         * and the only way the pill and the page cannot disagree.
         *
         * A tap still animates, because `animateScrollToPage` is what the tap does.
         */
        val selectionOffset = tabWidth * position.coerceIn(0f, (HomeAxis.entries.size - 1).toFloat())
        // `matchParentSize` rather than `fillMaxHeight`, and the distinction is load-bearing now that the
        // capsule has no fixed height. A child that fills the height *participates in measuring* its
        // parent, so it resolves against the incoming maximum — which in a `Scaffold`'s bottom-bar slot is
        // the whole window. `matchParentSize` sizes to the parent **after** the parent is measured, so the
        // pill follows the capsule instead of stretching it.
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(x = selectionOffset.roundToPx(), y = 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .padding(AXIS_SELECTION_INSET)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = AXIS_SELECTION_ALPHA),
                        shape = CircleShape,
                    ),
            )
        }
        // `IntrinsicSize.Min` is what makes the capsule content-sized. The row asks its tabs how tall they
        // need to be and takes the largest; the tabs then fill *that*. Without it, `fillMaxHeight` on a tab
        // resolves against the window and the capsule grows to cover the screen — which is exactly what
        // happened when this bar's fixed height became a floor, and it swallowed every tap on the shelf.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = AXIS_SELECTION_INSET)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeAxis.entries.forEach { axis ->
                val selected = axis == current
                Surface(
                    selected = selected,
                    onClick = { onAxisChanged(axis) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    HomeAxisTab(axis = axis, isStacked = isStacked)
                }
            }
        }
    }
}

/**
 * PRODUCT_SPEC 16.2 — this state as the page for [axis] should render it.
 *
 * The visible axis is the state itself. Any other axis is the same state with that axis's rows copied
 * over it, so the revealed page goes through exactly the code path the visible one does — the same
 * header, the same empty states, the same cards — rather than a second, thinner rendering that would
 * drift from it.
 *
 * An axis missing from [preview] has not loaded yet, and `isLoaded = false` is how the page says so. That
 * is the distinction the map's *absence* carries: without it the first frame of a reveal would say "no
 * series" about an axis nobody had asked the database about.
 */
private fun HomeUiState.forAxis(axis: HomeAxis): HomeUiState {
    if (axis == this.axis) return this
    val rows = axisRows[axis] ?: return copy(axis = axis, isLoaded = false)
    return copy(
        axis = axis,
        isLoaded = true,
        books = rows.books,
        shelves = rows.shelves,
        series = rows.series,
        groups = rows.groups,
    )
}

/**
 * One tab: an icon and its label, side by side or stacked.
 *
 * `Center` in both arrangements, so the highlight pill behind it lands on the content either way.
 */
@Composable
private fun HomeAxisTab(axis: HomeAxis, isStacked: Boolean, modifier: Modifier = Modifier) {
    val icon: @Composable () -> Unit = {
        Icon(imageVector = axis.icon(), contentDescription = null, modifier = Modifier.size(AXIS_ICON_SIZE))
    }
    val label: @Composable () -> Unit = {
        Text(
            text = stringResource(axis.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            // Two lines when stacked: a long label at a large scale is the case stacking exists for, and
            // one line would ellipsise it again after all that room was made.
            maxLines = if (isStacked) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
    if (isStacked) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            label()
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Box(modifier = Modifier.padding(start = 4.dp)) { label() }
        }
    }
}

private val AXIS_BAR_HEIGHT = 46.dp
private val AXIS_ICON_SIZE = 18.dp

/**
 * Where side-by-side gives up.
 *
 * 1.3 rather than 1.0 because the arrangement survives a modest increase, and rather than 2.0 because by
 * then the label has been an ellipsis for a while. Chosen from the arithmetic above, not measured on a
 * device — the APK is what settles it.
 */
private const val STACK_LABEL_FONT_SCALE = 1.3f
private val AXIS_BAR_SIDE_MARGIN = 14.dp
private val AXIS_BAR_VERTICAL_MARGIN = 4.dp
private val AXIS_SELECTION_INSET = 3.dp

private const val AXIS_SELECTION_ALPHA = 0.34f

private fun HomeAxis.icon(): ImageVector = when (this) {
    HomeAxis.Books -> Icons.AutoMirrored.Filled.MenuBook
    HomeAxis.Series -> Icons.AutoMirrored.Filled.List
    HomeAxis.Authors -> Icons.Filled.Person
    HomeAxis.Genres -> Icons.Filled.Category
}

private fun HomeAxis.labelRes(): Int = when (this) {
    HomeAxis.Books -> R.string.library_tab_books
    HomeAxis.Series -> R.string.library_tab_series
    HomeAxis.Authors -> R.string.library_tab_authors
    HomeAxis.Genres -> R.string.library_tab_genres
}

/**
 * Switches the Books axis between the shelves and the flat list.
 *
 * One icon with two states rather than a pair of tabs: the shelves and the list show the same books,
 * so this is a change of view and not a change of place.
 */
@Composable
private fun HomeViewToggle(current: BooksView, onChanged: (BooksView) -> Unit, modifier: Modifier = Modifier) {
    val toShelves = current == BooksView.List
    IconButton(
        onClick = { onChanged(if (toShelves) BooksView.Shelves else BooksView.List) },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (toShelves) Icons.Filled.ViewAgenda else Icons.AutoMirrored.Filled.FormatListBulleted,
            contentDescription = stringResource(
                if (toShelves) R.string.home_view_shelves else R.string.home_view_list,
            ),
        )
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
private fun HomeContent(uiState: HomeUiState, actions: HomeActions, topInset: Dp, bottomInset: Dp) {
    // The two states below centre their wording in whatever they are given, and what they are given is now
    // the whole window — the body no longer stops below the bar. Without the inset a short screen centres
    // "No server connected" behind the frosted header.
    val content = Modifier
        .fillMaxSize()
        .padding(top = topInset)

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

        // Not [content]: the shelf takes the inset as content padding instead, so its cards can scroll
        // under the header rather than stopping below it.
        else -> BookShelf(
            uiState = uiState,
            actions = actions,
            topInset = topInset,
            bottomInset = bottomInset,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * PRODUCT_SPEC LIB-002 — the browse surface: shelves, books, series, authors or genres.
 *
 * Search and the chip rows appear once there is something to narrow. A search field above "No books
 * yet" offers the user a way to filter nothing down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookShelf(
    uiState: HomeUiState,
    actions: HomeActions,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    // PRODUCT_SPEC LIB-001 — "pull-to-refresh refreshes the active library", in as many words. The
    // toolbar button stays: pull is a gesture some users never discover, and TalkBack has no
    // sensible way to perform one.
    //
    // The gesture without the spinner. `PullToRefreshBox` draws its own indicator whenever
    // `isRefreshing` is true, which for an automatic sync meant a wheel appearing over the shelf
    // the user never asked for — and a second one, since the refresh button already turns. An empty
    // indicator slot keeps the pull working and leaves the button to say so.
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = actions.onRefresh,
        indicator = {},
        modifier = modifier.fillMaxSize(),
    ) {
        AxisContent(uiState = uiState, actions = actions, topInset = topInset, bottomInset = bottomInset)
    }
}

/**
 * PRODUCT_SPEC LIB-002 — the pinned half of the shelf: search, and the chips that narrow it.
 *
 * ### Why these are in the header rather than above the list
 *
 * They were the first children of the body's `Column`, under a `Spacer` the height of the bar — and a
 * sibling `Spacer` in a `Column` does not let anything scroll underneath it. The list simply began below
 * the bar, exactly as it had before the bar was frosted, and the blur sampled empty background: the
 * feature drew a frosted surface over nothing. Measured, not guessed — the shelf's first card started
 * 160dp down a 740dp window.
 *
 * In the header they are part of what `Scaffold` measures, so `innerPadding`'s top covers the whole
 * assembly and the list can take it as *content* padding and keep the full window. Which is the same
 * arrangement the settings screen uses for its tab row, and for the same reason.
 *
 * They stay pinned rather than scrolling away with the list: a filter that emptied the shelf would
 * otherwise hide the only control that can clear it.
 */
@Composable
private fun HomeFilters(uiState: HomeUiState, actions: HomeActions) {
    if (uiState.isSearching) {
        SearchField(query = uiState.query, onQueryChanged = actions.onQueryChanged)
    }

    // The chip rows belong to the flat book list. A shelf of series, authors or genres is ordered
    // by name and has nothing to filter, and the three horizontal shelves carry their own order.
    if (uiState.axis == HomeAxis.Books && uiState.booksView == BooksView.List) {
        uiState.focus?.let { focus -> FocusChip(label = focus.label, onCleared = actions.onFocusCleared) }
        BookFilterRow(selected = uiState.filter, onFilterChanged = actions.onFilterChanged)
        BookSortRow(selected = uiState.order, onOrderChanged = actions.onOrderChanged)
    }
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    // Opened by a button press, so the keyboard is what the user asked for. Without this the field
    // appears and the user has to tap it a second time to type in it.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        label = { Text(text = stringResource(R.string.home_search_hint)) },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
    )
}

/**
 * PRODUCT_SPEC LIB-002 / 21 — the book list narrowed to one author or genre says so, and says how to
 * leave.
 *
 * A narrowed list the user cannot see the reason for reads as books having gone missing, and one they
 * cannot widen again is a trap.
 */
@Composable
private fun FocusChip(label: String, onCleared: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        InputChip(
            selected = true,
            onClick = onCleared,
            label = { Text(text = label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.library_focus_clear, label),
                )
            },
        )
    }
}

/** PRODUCT_SPEC LIB-002 — continue listening and downloaded, as filters over the list rather than axes. */
@Composable
private fun BookFilterRow(selected: BookFilter, onFilterChanged: (BookFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = BookFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onFilterChanged(filter) },
                label = { Text(text = stringResource(filter.labelRes())) },
            )
        }
    }
}

private fun BookFilter.labelRes(): Int = when (this) {
    BookFilter.All -> R.string.library_filter_all
    BookFilter.ContinueListening -> R.string.library_filter_continue
    BookFilter.Downloaded -> R.string.library_filter_downloaded
}

@Composable
private fun AxisContent(
    uiState: HomeUiState,
    actions: HomeActions,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    if (uiState.isAxisEmpty) {
        // Layout padding here, not content padding: there is nothing to scroll, so the only question is
        // whether the wording sits under the frosted header. It must not.
        AxisEmptyState(uiState = uiState, actions = actions, modifier = modifier.padding(top = topInset))
        return
    }
    // Each axis is a different list. Reusing one remembered LazyColumn position made a newly selected
    // axis open halfway down when the previous one had been scrolled.
    key(uiState.axis, uiState.booksView, uiState.focus) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag(HOME_AXIS_LIST_TEST_TAG),
            // **Content** padding at both ends, never layout padding. It lengthens the scroll range and
            // leaves the list its whole window, which is what lets a card pass under the frosted header on
            // its way out and under the capsule at the other end — and what gives the glass something to
            // refract in the first place.
            contentPadding = PaddingValues(top = 16.dp + topInset, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShelfHeader(uiState, modifier = Modifier.padding(horizontal = 16.dp)) }
            when (uiState.axis) {
                HomeAxis.Books -> if (uiState.booksView == BooksView.Shelves) {
                    homeShelves(
                        shelves = uiState.shelves,
                        onBookSelected = actions.onBookSelected,
                        onBookPlaySelected = actions.onBookPlaySelected,
                        onSeriesSelected = actions.onSeriesSelected,
                    )
                } else {
                    items(items = uiState.books, key = { it.id.value }) { book ->
                        BookCard(
                            book = book,
                            onClick = { actions.onBookSelected(book.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                HomeAxis.Series -> items(items = uiState.series, key = { it.series.id.value }) { shelf ->
                    SeriesCard(
                        shelf = shelf,
                        onClick = { actions.onSeriesSelected(shelf.series.id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                HomeAxis.Authors ->
                    items(items = uiState.groups, key = { it.key }) { group ->
                        GroupCard(
                            group = group,
                            onClick = { actions.onGroupSelected(group) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                HomeAxis.Genres -> items(items = uiState.groups, key = { it.key }) { group ->
                    val profile = uiState.profile
                    val disabledReason = when {
                        uiState.isOffline -> stringResource(R.string.genre_edit_offline)
                        profile?.requiresReauthentication == true ->
                            stringResource(R.string.genre_edit_reauthentication)
                        else -> null
                    }
                    GroupCard(
                        group = group,
                        onClick = { actions.onGroupSelected(group) },
                        editAction = profile
                            ?.takeIf { it.canUpdate }
                            ?.let {
                                GroupCardEditAction(
                                    label = stringResource(R.string.genre_edit_action),
                                    contentDescription = stringResource(
                                        R.string.genre_edit_action_description,
                                        group.label,
                                    ),
                                    enabled = disabledReason == null,
                                    disabledReason = disabledReason,
                                    onClick = { actions.onGenreEditRequested(group) },
                                )
                            },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Why the current axis is bare.
 *
 * A search that matched nothing is the most likely answer and is checked first — it is the one the
 * user can act on. Everything else falls through to the shared empty/offline/error branch, which is
 * about the library as a whole rather than about this axis.
 */
@Composable
private fun AxisEmptyState(uiState: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val content = modifier.fillMaxSize()
    when {
        uiState.query.isNotBlank() || uiState.focus != null || uiState.filter != BookFilter.All ->
            ShelfEmptyState(
                title = stringResource(R.string.home_no_matches_title),
                body = stringResource(R.string.home_no_matches_body),
                modifier = content,
            )

        uiState.axis != HomeAxis.Books -> ShelfEmptyState(
            title = stringResource(uiState.axis.emptyTitleRes()),
            body = stringResource(R.string.library_groups_empty_body),
            modifier = content,
        )

        else -> EmptyOrFailed(uiState = uiState, onRefresh = actions.onRefresh, modifier = modifier)
    }
}

private fun HomeAxis.emptyTitleRes(): Int = when (this) {
    HomeAxis.Books -> R.string.home_empty_title
    HomeAxis.Series -> R.string.series_empty_title
    HomeAxis.Authors -> R.string.library_authors_empty_title
    HomeAxis.Genres -> R.string.library_genres_empty_title
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
            modifier = Modifier.testTag(HOME_SYNC_STATUS_TEST_TAG),
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
    // The count follows the active browse scope. A search narrows it; a shelf preview retains the
    // uncapped Room source count rather than claiming its card limit is the whole library.
    SyncStatus.Succeeded ->
        pluralStringResource(R.plurals.home_library_books, visibleBookCount, visibleBookCount)
    // PRODUCT_SPEC LIB-001 — a sync that could not reach some items says so rather than claiming a clean
    // run. The count is still what is on screen; the caveat is that it is not all of it.
    SyncStatus.PartiallySucceeded ->
        pluralStringResource(R.plurals.home_sync_partial, visibleBookCount, visibleBookCount)
}

/** Number of distinct books represented by the active browse shape, not the number of cards. */
private val HomeUiState.visibleBookCount: Int
    get() = when (axis) {
        HomeAxis.Books -> if (booksView == BooksView.Shelves) {
            shelves.totalBookCount
        } else {
            books.asSequence().distinctBookCount()
        }

        HomeAxis.Series -> series.asSequence().flatMap { it.books.asSequence() }.distinctBookCount()
        HomeAxis.Authors, HomeAxis.Genres ->
            groups.asSequence().flatMap { it.books.asSequence() }.distinctBookCount()
    }

private fun Sequence<Book>.distinctBookCount(): Int = map { it.id }.distinct().count()

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

/** The visual mark stays compact and gives way before the title or top-bar actions do. */
private val HOME_MARK_SIZE = 40.dp
private val HOME_MARK_MIN_TITLE_WIDTH = 132.dp
private const val HOME_MARK_MAX_FONT_SCALE = 1.3f
internal const val HOME_MARK_TEST_TAG = "home-brand-mark"
internal const val HOME_AXIS_LIST_TEST_TAG = "home-axis-list"
internal const val HOME_SYNC_STATUS_TEST_TAG = "home-sync-status"
