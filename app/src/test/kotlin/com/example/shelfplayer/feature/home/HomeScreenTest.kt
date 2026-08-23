package com.example.shelfplayer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.usecase.BulkGenreEditFailure
import com.example.shelfplayer.domain.usecase.BulkGenreEditStage
import com.example.shelfplayer.domain.usecase.BulkGenreEditSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 17.1 (UI tests: offline home, TalkBack semantics) — the shelf, rendered.
 *
 * ### What this covers that a screen reader would
 *
 * TalkBack does not read pixels; it reads the **semantics tree**. Every assertion below about a content
 * description, a live region or an enabled state is an assertion about what TalkBack would announce, and
 * it runs on every build rather than whenever somebody remembers to switch a screen reader on. What it
 * cannot judge is whether the announcement is *pleasant* to listen to, or the reading order on a real
 * device — those still need TC-52 and TC-53 and a person.
 *
 * ### The rule it exists to defend
 *
 * PRODUCT_SPEC 3.2 and 21: colour is never the only signal. The server dot is a coloured circle with no
 * text beside it, which is precisely the control that goes wrong — it is easy to add a state, give it a
 * colour, and forget the description that makes it perceivable at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * PRODUCT_SPEC LIB-002 / 6.3 — offline is its own state, and it says so on the screen.
     *
     * Distinct from an error, and distinct from "no books": a user in a lift has not hit a failure, and
     * telling them so is the difference between a bug report and a shrug.
     */
    @Test
    fun `offline with a cached shelf says it is cached, not that it failed`() {
        compose.setContent {
            HomeScreen(
                uiState = state(isOffline = true, syncStatus = SyncStatus.Succeeded),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("Offline", substring = true).assertExists()
        compose.onNodeWithText("failed", substring = true).assertDoesNotExist()
    }

    /** Offline with nothing cached is a different screen again — an explanation, not an empty list. */
    @Test
    fun `offline with nothing cached explains itself`() {
        compose.setContent {
            HomeScreen(
                uiState = state(isOffline = true, syncStatus = SyncStatus.NeverSynced, books = emptyList()),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("No connection").assertExists()
    }

    /**
     * PRODUCT_SPEC 3.2 — the server indicator is a coloured dot, so it carries a description.
     *
     * Every state, described. A dot that gained a colour and not a description would be invisible to a
     * screen reader and to anyone who cannot tell the two colours apart, and nothing else in the suite
     * would notice. `ServerStatus.entries` rather than a written-out list, so adding a state to the enum
     * and forgetting its wording fails here.
     */
    @Test
    fun `every server state the dot can show is described in words`() {
        val expected = mapOf(
            ServerStatus.Reachable to "Server reachable",
            ServerStatus.Unreachable to "Server not reachable",
            ServerStatus.Unknown to "Server status unknown",
        )
        assertEquals(ServerStatus.entries.toSet(), expected.keys, "a new server state needs a description")

        // One `setContent` — the rule is one per test — with the status driven by state, which is also
        // closer to what the screen does when a sync changes it under the user.
        var status by mutableStateOf(ServerStatus.Unknown)
        compose.setContent { HomeScreen(uiState = state(serverStatus = status), actions = noActions()) }

        expected.forEach { (value, description) ->
            status = value
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description).assertExists()
        }
    }

    /**
     * Offline outranks the server's own state, and says something different.
     *
     * With no network the app has learned nothing about the server, so a red dot would blame the wrong
     * thing. The description has to say that rather than reuse "not reachable".
     */
    @Test
    fun `offline describes the dot as unknown rather than as a failure`() {
        compose.setContent {
            HomeScreen(
                uiState = state(isOffline = true, serverStatus = ServerStatus.Unreachable),
                actions = noActions(),
            )
        }

        compose.onNodeWithContentDescription("Device offline, server status unknown").assertExists()
        compose.onNodeWithContentDescription("Server not reachable").assertDoesNotExist()
    }

    /** PRODUCT_SPEC 21 — an icon button with no label is a button a screen reader cannot name. */
    @Test
    fun `every icon button in the top bar is named`() {
        compose.setContent { HomeScreen(uiState = state(), actions = noActions()) }

        listOf("Search", "Refresh", "Profiles", "Settings").forEach { name ->
            compose.onNodeWithContentDescription(name).assertExists()
        }
    }

    /**
     * PRODUCT_SPEC LIB-001 — a sync in progress is announced, not only spun.
     *
     * The refresh button doubles as the indicator, so while it is running its description changes *and*
     * it becomes a polite live region. Both halves matter: the changed description is what is announced,
     * and the live region is what makes it announce without the user going looking.
     */
    @Test
    fun `a running sync announces itself and cannot be started twice`() {
        compose.setContent {
            HomeScreen(uiState = state(syncStatus = SyncStatus.Syncing), actions = noActions())
        }

        compose.onNodeWithContentDescription("Refreshing…").assertIsNotEnabled()
        // On the refresh control itself, not merely somewhere on the screen: a sync nobody is told
        // about is a spinner, not an announcement.
        compose.onNodeWithContentDescription("Refreshing…")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    /**
     * PRODUCT_SPEC LIB-001 / 14.4 — a failed refresh keeps the shelf and adds a caveat, not a banner.
     *
     * The books are still there and still correct as far as they go, so the screen says so in a line
     * rather than replacing them with an error. Both halves are asserted: a change that started hiding
     * the shelf, and a change that stopped mentioning the failure, are both regressions and neither
     * would be caught by the other assertion.
     */
    @Test
    fun `a failed refresh keeps the shelf and admits the failure`() {
        compose.setContent {
            HomeScreen(
                uiState = state(
                    syncStatus = SyncStatus.Failed,
                    error = AppError.Network(summary = "The server could not be reached."),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithText("The Salt Harbour").assertExists()
        compose.onNodeWithText("The last refresh failed", substring = true).assertExists()
    }

    /**
     * With nothing cached there is no shelf to caveat, so the error itself is what the user reads.
     *
     * This is the other side of the case above, and it is why the summary exists: "the last refresh
     * failed" over an empty screen explains nothing about what to do next.
     */
    @Test
    fun `a failure with nothing cached shows the error in plain language`() {
        val summary = "The server could not be reached."
        compose.setContent {
            HomeScreen(
                uiState = state(
                    syncStatus = SyncStatus.Failed,
                    error = AppError.Network(summary = summary),
                    books = emptyList(),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithText(summary, substring = true).assertExists()
    }

    /** PRODUCT_SPEC 6.1 step 9 — a shelf narrowed to one library is titled with it, not with "Library". */
    @Test
    fun `a shelf scoped to a library is titled with that library`() {
        compose.setContent {
            HomeScreen(uiState = state(scopedTo = library("Fiction")), actions = noActions())
        }

        compose.onNodeWithText("Fiction").assertExists()
    }

    /**
     * PRODUCT_SPEC LIB-001 — status describes whichever Room-backed browse axis is actually visible.
     *
     * Series, authors and genres deliberately do not collect the flat book flow. Their count therefore
     * comes from their own rows, de-duplicated because one book may belong to several series or groups.
     * Shelves keep the uncapped source count rather than mistaking their preview limit for the library.
     */
    @Test
    fun `sync status counts unique books on every populated browse shape`() {
        val first = book()
        val second = book().copy(id = LibraryItemId("item-2"), title = "Second book")
        var shown by mutableStateOf(
            state(
                books = emptyList(),
                booksView = BooksView.Shelves,
                shelves = HomeShelves(
                    continueListening = emptyList(),
                    continueSeries = emptyList(),
                    recentlyAdded = emptyList(),
                    discover = listOf(first),
                    listenAgain = emptyList(),
                    totalBookCount = 30,
                ),
            ),
        )
        compose.setContent { HomeScreen(uiState = shown, actions = noActions()) }

        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("30 books")

        shown = state(
            books = emptyList(),
            axis = HomeAxis.Series,
            series = listOf(
                SeriesShelf(
                    series = Series(ServerId("srv_books"), SeriesId("series-1"), "First series"),
                    books = listOf(first, second),
                ),
                SeriesShelf(
                    series = Series(ServerId("srv_books"), SeriesId("series-2"), "Second series"),
                    books = listOf(first),
                ),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("2 books")

        val groups = listOf(
            BookGroup(BookGroupKind.Author, "author-1", "First author", listOf(first, second)),
            BookGroup(BookGroupKind.Author, "author-2", "Second author", listOf(first)),
        )
        shown = state(books = emptyList(), axis = HomeAxis.Authors, groups = groups)
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("2 books")

        shown = state(
            books = emptyList(),
            axis = HomeAxis.Genres,
            groups = groups.map { it.copy(kind = BookGroupKind.Genre) },
        )
        compose.waitForIdle()
        compose.onNodeWithTag(HOME_SYNC_STATUS_TEST_TAG).assertTextEquals("2 books")
    }

    /** Selecting a new axis opens its beginning instead of inheriting the previous list's position. */
    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h732dp")
    fun `switching browse axes resets the list position`() {
        fun groups(kind: BookGroupKind, prefix: String) = List(20) { index ->
            genreGroup("$prefix ${index + 1}").copy(
                kind = kind,
                key = "$prefix-$index",
            )
        }
        var shown by mutableStateOf(
            state(
                books = emptyList(),
                axis = HomeAxis.Authors,
                groups = groups(BookGroupKind.Author, "Author"),
            ),
        )
        compose.setContent { HomeScreen(uiState = shown, actions = noActions()) }
        compose.onNodeWithTag(HOME_AXIS_LIST_TEST_TAG).performScrollToNode(hasText("Author 20"))
        compose.onNodeWithText("Author 20").assertIsDisplayed()

        shown = state(
            books = emptyList(),
            axis = HomeAxis.Genres,
            groups = groups(BookGroupKind.Genre, "Genre"),
        )
        compose.waitForIdle()

        compose.onNodeWithText("Genre 1").assertIsDisplayed()
    }

    @Test
    fun `the search button opens the field`() {
        var toggles = 0
        compose.setContent {
            HomeScreen(uiState = state(), actions = noActions().copy(onSearchToggled = { toggles++ }))
        }

        compose.onNodeWithContentDescription("Search").performClick()

        assertEquals(1, toggles)
    }

    /**
     * PRODUCT_SPEC PLAY-001 / LIB-002 — the shelf's play affordance starts the named book directly.
     *
     * The card itself still opens details. Keeping the two actions distinct is what makes the overlaid
     * play symbol honest: a control labelled "Play" must not merely navigate to another screen.
     */
    @Test
    fun `the shelf play button names and starts its book`() {
        val book = book()
        var played: LibraryItemId? = null
        compose.setContent {
            HomeScreen(
                uiState = state(
                    books = listOf(book),
                    booksView = BooksView.Shelves,
                    shelves = HomeShelves(
                        continueListening = emptyList(),
                        continueSeries = emptyList(),
                        recentlyAdded = emptyList(),
                        discover = listOf(book),
                        listenAgain = emptyList(),
                        totalBookCount = 1,
                    ),
                ),
                actions = noActions().copy(onBookPlaySelected = { played = it }),
            )
        }

        compose.onNodeWithContentDescription("Play The Salt Harbour").performClick()

        assertEquals(book.id, played)
    }

    /** PRODUCT_SPEC MGR-008 — only an update-capable Genres card owns the named secondary action. */
    @Test
    fun `genre edit is named and permission gated without changing author cards`() {
        val genre = genreGroup("Science Fiction", count = 2)
        var selected: BookGroup? = null
        var edited: BookGroup? = null
        var shown by mutableStateOf(
            state(
                profile = profile(canUpdate = true),
                axis = HomeAxis.Genres,
                groups = listOf(genre),
            ),
        )
        compose.setContent {
            HomeScreen(
                uiState = shown,
                actions = noActions().copy(
                    onGroupSelected = { selected = it },
                    onGenreEditRequested = { edited = it },
                ),
            )
        }

        // Inject a physical pointer tap. A semantics click calls the child action directly and did not
        // catch the former clickable Card consuming this exact tap on real hardware.
        compose.onNodeWithContentDescription("Edit genre Science Fiction").performTouchInput { click() }
        assertEquals(genre, edited)
        assertNull(selected, "the secondary action must not open the group")

        compose.onNodeWithText("Science Fiction").performTouchInput { click() }
        assertEquals(genre, selected, "the rest of the card must still open the group")

        shown = shown.copy(
            axis = HomeAxis.Authors,
            groups = listOf(genre.copy(kind = BookGroupKind.Author, key = "author-1", label = "Ada Writer")),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Edit genre Ada Writer").assertDoesNotExist()

        shown = shown.copy(
            profile = profile(canUpdate = false),
            axis = HomeAxis.Genres,
            groups = listOf(genre),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Edit genre Science Fiction").assertDoesNotExist()
    }

    /** An authorized profile can see why the operation is unavailable without trying a doomed write. */
    @Test
    fun `offline genre edit remains visible but disabled with an explanation`() {
        compose.setContent {
            HomeScreen(
                uiState = state(
                    isOffline = true,
                    profile = profile(canUpdate = true),
                    axis = HomeAxis.Genres,
                    groups = listOf(genreGroup("Science Fiction")),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithContentDescription("Edit genre Science Fiction")
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Connect to edit genres.",
                ),
            )
    }

    /** The replacement may change freely, but no domain callback fires until the named confirmation. */
    @Test
    fun `genre dialog confirms its source count and comma separated replacement explicitly`() {
        val request = GenreEditRequest(
            profileId = profile().id,
            sourceGenre = "Sci fi",
            cachedMatchCount = 3,
        )
        var confirms = 0
        var dismisses = 0
        var shown by mutableStateOf(
            state(genreEdit = GenreEditUiState.Confirming(request)),
        )
        compose.setContent {
            HomeScreen(
                uiState = shown,
                actions = noActions().copy(
                    onGenreEditReplacementChanged = { replacement ->
                        val current = shown.genreEdit as GenreEditUiState.Confirming
                        shown = shown.copy(
                            genreEdit = current.copy(
                                request = current.request.copy(replacementGenres = replacement),
                            ),
                        )
                    },
                    onGenreEditConfirmed = { confirms++ },
                    onGenreEditDismissed = { dismisses++ },
                ),
            )
        }

        compose.onNodeWithText("Source genre: Sci fi").assertExists()
        compose.onNodeWithText("3 cached books match").assertExists()
        compose.onNodeWithText("Update books").assertIsNotEnabled()
        compose.onNodeWithText("Replacement genres").performTextInput("Science Fiction, Fantasy")
        assertEquals(0, confirms)
        compose.onNodeWithText("Update books").assertIsEnabled().performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }

    /** Before confirmation, cancel is a true no-write dismissal. */
    @Test
    fun `canceling genre confirmation never invokes the update`() {
        var confirms = 0
        var dismisses = 0
        compose.setContent {
            HomeScreen(
                uiState = state(
                    genreEdit = GenreEditUiState.Confirming(
                        GenreEditRequest(
                            profileId = profile().id,
                            sourceGenre = "Sci fi",
                            cachedMatchCount = 1,
                            replacementGenres = "Science Fiction",
                        ),
                    ),
                ),
                actions = noActions().copy(
                    onGenreEditConfirmed = { confirms++ },
                    onGenreEditDismissed = { dismisses++ },
                ),
            )
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirms)
        assertEquals(1, dismisses)
    }

    /** A partial batch names every count separately; no earlier server write is implied to roll back. */
    @Test
    fun `genre result announces every partial success count and stop reason`() {
        val request = GenreEditRequest(
            profileId = profile().id,
            sourceGenre = "Sci fi",
            cachedMatchCount = 7,
            replacementGenres = "Science Fiction, Fantasy",
        )
        val networkError = AppError.Network(summary = "The connection was lost, so remaining books were not tried.")
        val result = BulkGenreEditSummary(
            matchedCount = 7,
            updatedCount = 2,
            unchangedCount = 1,
            locallyStaleCount = 1,
            failures = listOf(
                BulkGenreEditFailure(
                    bookId = LibraryItemId("draft"),
                    stage = BulkGenreEditStage.Draft,
                    error = AppError.Conflict(summary = "This book has an unsaved draft."),
                ),
                BulkGenreEditFailure(
                    bookId = LibraryItemId("failed"),
                    stage = BulkGenreEditStage.Save,
                    error = networkError,
                ),
            ),
            stopReason = networkError,
        )
        compose.setContent {
            HomeScreen(
                uiState = state(genreEdit = GenreEditUiState.Complete(request, result)),
                actions = noActions(),
            )
        }

        listOf(
            "Genre update partially complete",
            "Sci fi → Science Fiction, Fantasy",
            "Matched: 7",
            "Updated: 2",
            "Unchanged: 1",
            "Draft conflicts: 1",
            "Failed: 1",
            "Stale local copies: 1",
            "Unprocessed: 2",
            "Stop reason: The connection was lost, so remaining books were not tried.",
        ).forEach { text -> compose.onNodeWithText(text).assertExists() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    /** Progress is announced and deliberately has no cancel that could discard a partial result. */
    @Test
    fun `running genre update is an announced non dismissible progress state`() {
        compose.setContent {
            HomeScreen(
                uiState = state(
                    genreEdit = GenreEditUiState.Running(
                        GenreEditRequest(
                            profileId = profile().id,
                            sourceGenre = "Sci fi",
                            cachedMatchCount = 2,
                            replacementGenres = "Science Fiction",
                        ),
                    ),
                ),
                actions = noActions(),
            )
        }

        compose.onNodeWithContentDescription("Updating genre…")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    /**
     * PRODUCT_SPEC 3.1 — the layout survives the largest font setting a user can choose.
     *
     * Rendered at a 2x font scale, which is beyond Android's own slider. The assertion is only that the
     * screen still composes and the controls are still there: a layout that threw, or one whose top bar
     * dropped its buttons to make room, would fail here. It is a smoke test rather than a screenshot
     * comparison, and it is worth having because the failure it catches is a crash on somebody's phone.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 2.0f)
    fun `the compact top bar prioritises its title and actions at twice the font size`() {
        val longLibraryName = "The very long science fiction library"
        compose.setContent {
            HomeScreen(
                uiState = state(scopedTo = library(longLibraryName)),
                actions = noActions(),
            )
        }

        compose.onNodeWithText(longLibraryName).assertExists()
        compose.onNodeWithContentDescription("Settings").assertExists()
        compose.onNodeWithContentDescription("Refresh").assertExists()
        compose.onNodeWithTag(HOME_MARK_TEST_TAG).assertDoesNotExist()
    }

    /** The supplied brand mark remains visible when it does not compete with navigation controls. */
    @Test
    @Config(sdk = [34], qualifiers = "w600dp-h800dp")
    fun `the home brand mark is retained when the top bar has room`() {
        compose.setContent { HomeScreen(uiState = state(), actions = noActions()) }

        compose.onNodeWithTag(HOME_MARK_TEST_TAG).assertExists()
    }

    /**
     * A signed-in profile with a book on the shelf.
     *
     * Populated on purpose: an empty shelf renders the *loading* state, which has none of the header,
     * the caption or the error on it. A test that forgot this would assert against the wrong screen and
     * report the app's wording as missing when it was simply somewhere else.
     */
    private fun state(
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

    private fun book() = Book(
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

    private fun profile(canUpdate: Boolean = false) = Profile(
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

    private fun genreGroup(label: String, count: Int = 1): BookGroup = BookGroup(
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

    private fun library(name: String) = Library(
        serverId = ServerId("srv_books"),
        id = LibraryId("lib-1"),
        name = name,
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 3,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    private fun noActions() = HomeActions(
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
}
