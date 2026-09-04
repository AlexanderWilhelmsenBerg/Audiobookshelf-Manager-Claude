package com.example.shelfplayer.feature.home

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesShelf
import java.time.Instant
import kotlin.time.Duration

/*
 * The shelf's test fixtures, shared by the screen's own cases and by the gesture cases next door.
 *
 * They were private members of `HomeScreenTest` until that class crossed detekt's `LargeClass` limit and
 * the gestures moved into a file of their own. Shared rather than copied: two `state()` builders drifting
 * apart is how a test starts asserting against a screen the app does not have.
 */

/**
 * A signed-in profile with a book on the shelf.
 *
 * Populated on purpose: an empty shelf renders the *loading* state, which has none of the header, the
 * caption or the error on it. A test that forgot this would assert against the wrong screen and report the
 * app's wording as missing when it was simply somewhere else.
 */
internal fun state(
    isOffline: Boolean = false,
    syncStatus: SyncStatus = SyncStatus.Succeeded,
    serverStatus: ServerStatus = ServerStatus.Reachable,
    error: AppError? = null,
    scopedTo: Library? = null,
    profile: Profile = profile(),
    books: List<Book> = listOf(book()),
    booksView: BooksView = BooksView.List,
    shelves: HomeShelves = HomeShelves.Empty,
    axis: HomeAxis = HomeAxis.Books,
    series: List<SeriesShelf> = emptyList(),
    groups: List<BookGroup> = emptyList(),
    genreEdit: GenreEditUiState = GenreEditUiState.Hidden,
) = HomeUiState(
    isOffline = isOffline,
    // Without this the screen renders its one blocking state and none of the assertions below are
    // looking at the screen they name.
    isLoaded = true,
    profile = profile,
    books = books,
    booksView = booksView,
    shelves = shelves,
    axis = axis,
    series = series,
    groups = groups,
    genreEdit = genreEdit,
    syncStatus = syncStatus,
    serverStatus = serverStatus,
    error = error,
    scopedTo = scopedTo,
)

internal fun book() = Book(
    serverId = ServerId("srv_books"),
    libraryId = LibraryId("lib-1"),
    id = LibraryItemId("item-1"),
    title = "The Salt Harbour",
    subtitle = null,
    description = null,
    authors = emptyList(),
    narrators = emptyList(),
    seriesMemberships = emptyList(),
    genres = emptyList(),
    tags = emptyList(),
    publisher = null,
    publishedYear = null,
    language = null,
    isbn = null,
    asin = null,
    duration = Duration.ZERO,
    trackCount = 1,
    sizeBytes = 0,
    coverPath = null,
    addedAt = null,
    remoteUpdatedAt = null,
    lastFetchedAt = Instant.EPOCH,
    isExplicit = false,
    isAbridged = false,
    progress = null,
    localAvailability = LocalAvailability.NotDownloaded,
)

internal fun profile(canUpdate: Boolean = false) = Profile(
    id = ProfileId("prf_ada"),
    serverId = ServerId("srv_books"),
    username = "ada",
    displayName = "ada",
    role = ProfileRole.Listener,
    requiresReauthentication = false,
    lastUsedAt = Instant.EPOCH,
    isFixture = false,
    canUpdate = canUpdate,
)

internal fun genreGroup(label: String, count: Int = 1): BookGroup = BookGroup(
    kind = BookGroupKind.Genre,
    key = label.lowercase(),
    label = label,
    books = List(count) { index ->
        book().copy(
            id = LibraryItemId("genre-book-$index"),
            genres = listOf(label),
        )
    },
)

internal fun library(name: String) = Library(
    serverId = ServerId("srv_books"),
    id = LibraryId("lib-1"),
    name = name,
    kind = LibraryKind.Book,
    displayOrder = 0,
    bookCount = 3,
    remoteUpdatedAt = null,
    lastFetchedAt = Instant.EPOCH,
)

internal fun noActions() = HomeActions(
    onBookSelected = {},
    onBookPlaySelected = {},
    onSeriesSelected = {},
    onGroupSelected = { _: BookGroup -> },
    onGenreEditRequested = { _: BookGroup -> },
    onGenreEditReplacementChanged = {},
    onGenreEditConfirmed = {},
    onGenreEditDismissed = {},
    onQueryChanged = {},
    onSearchToggled = {},
    onAxisChanged = {},
    onBooksViewChanged = {},
    onOrderChanged = {},
    onFilterChanged = {},
    onFocusCleared = {},
    onRefresh = {},
    onProfilesSelected = {},
    onSettingsSelected = {},
    onSignInSelected = {},
)
