package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.EmbedRequest
import com.example.shelfplayer.core.model.library.MatchCandidate
import com.example.shelfplayer.core.model.library.MetadataProvider
import com.example.shelfplayer.domain.FakeAuthRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.profile
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.MetadataSaveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-001 plus section 3.3's bulk-metadata scope — one verified item-metadata PATCH per book.
 *
 * There is deliberately no bulk endpoint fake here. Audiobookshelf's captured contract is the item route,
 * and these tests make the use case prove that it composes that route rather than inventing another one.
 */
class BulkEditGenresUseCaseTest {

    @Test
    fun `renames every case-insensitive match and sends only the genres field`() = runTest {
        val books = listOf(
            genreBook("one", "Sci fi", "Classic"),
            genreBook("two", "science fiction", "SCI FI"),
            genreBook("other", "Fantasy"),
        )
        val fixture = Fixture(books)

        val result = fixture.useCase(TEST_PROFILE, "sCi FI", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(2, summary.matchedCount)
        assertEquals(2, summary.updatedCount)
        assertEquals(0, summary.failedCount)
        assertEquals(
            mapOf(
                "one" to listOf("Science Fiction", "Classic"),
                "two" to listOf("Science Fiction"),
            ),
            fixture.metadata.saves.associate { save -> save.bookId.value to save.edit.genres },
        )
        assertTrue(fixture.metadata.saves.all { it.changed == setOf(BookMetadataField.Genres) })
        assertEquals(listOf("reload:one", "save:one", "reload:two", "save:two"), fixture.metadata.events)
    }

    @Test
    fun `splits one genre and de-duplicates replacements without disturbing unrelated genres`() = runTest {
        val fixture = Fixture(
            listOf(
                genreBook(
                    "omnibus",
                    "Mystery",
                    "Science Fiction & Fantasy",
                    "Fantasy",
                    "science fiction",
                ),
            ),
        )

        val result = fixture.useCase(
            TEST_PROFILE,
            "science fiction & fantasy",
            " Science Fiction, Fantasy, science fiction ",
        )

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.updatedCount)
        assertEquals(
            listOf("Mystery", "Science Fiction", "Fantasy"),
            fixture.metadata.saves.single().edit.genres,
        )
    }

    @Test
    fun `a blank source or replacement is rejected before reading or writing books`() = runTest {
        val fixture = Fixture(listOf(genreBook("one", "Sci fi")))

        val blankSource = fixture.useCase(TEST_PROFILE, "  ", "Science Fiction")
        val blankTarget = fixture.useCase(TEST_PROFILE, "Sci fi", " , , ")

        assertIs<AppError.Validation>(assertIs<AppResult.Failure>(blankSource).error)
        assertIs<AppError.Validation>(assertIs<AppResult.Failure>(blankTarget).error)
        assertTrue(fixture.metadata.events.isEmpty())
    }

    @Test
    fun `missing update permission is refused before any item request`() = runTest {
        val fixture = Fixture(
            books = listOf(genreBook("one", "Sci fi")),
            canUpdate = false,
        )

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
        assertTrue(fixture.metadata.events.isEmpty())
    }

    @Test
    fun `offline editing is refused instead of queued`() = runTest {
        val fixture = Fixture(
            books = listOf(genreBook("one", "Sci fi")),
            isOnline = false,
        )

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertTrue(fixture.metadata.events.isEmpty())
    }

    @Test
    fun `the latest server item decides whether a cached match is still changed`() = runTest {
        val cached = genreBook("one", "Sci fi", "Classic")
        val fixture = Fixture(listOf(cached))
        fixture.metadata.reloadAs(cached.copy(genres = listOf("Classic")))

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.matchedCount)
        assertEquals(0, summary.updatedCount)
        assertEquals(1, summary.unchangedCount)
        assertTrue(fixture.metadata.saves.isEmpty())
        assertEquals(listOf("reload:one"), fixture.metadata.events)
    }

    @Test
    fun `reload and save failures are reported while independent books continue sequentially`() = runTest {
        val books = listOf(
            genreBook("saved", "Sci fi"),
            genreBook("reload-failed", "Sci fi"),
            genreBook("save-failed", "Sci fi"),
        )
        val fixture = Fixture(books)
        fixture.metadata.failReload(
            "reload-failed",
            AppError.ApiCompatibility(summary = "This item could not be decoded."),
        )
        fixture.metadata.failSave("save-failed", AppError.Validation(summary = "The item was rejected."))

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(3, summary.matchedCount)
        assertEquals(1, summary.updatedCount)
        assertEquals(2, summary.failedCount)
        assertEquals(0, summary.unprocessedCount)
        assertNull(summary.stopReason)
        assertEquals(
            listOf(BulkGenreEditStage.Reload, BulkGenreEditStage.Save),
            summary.failures.map { it.stage },
        )
        assertEquals(
            listOf(
                "reload:saved",
                "save:saved",
                "reload:reload-failed",
                "reload:save-failed",
                "save:save-failed",
            ),
            fixture.metadata.events,
        )
        assertEquals(
            listOf(LibraryItemId("save-failed")),
            fixture.metadata.savedDrafts.map { it.first },
            "a constructed edit that failed to save remains an explicit draft",
        )
    }

    @Test
    fun `an existing draft is reported as a conflict and left untouched while other books continue`() = runTest {
        val drafted = genreBook("drafted", "Sci fi")
        val other = genreBook("other", "Sci fi")
        val existingDraft = BookMetadataEdit.of(drafted).copy(title = "Private unfinished title")
        val fixture = Fixture(listOf(drafted, other))
        fixture.metadata.setDraft("drafted", existingDraft)

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.draftConflictCount)
        assertEquals(0, summary.unprocessedCount)
        assertNull(summary.stopReason)
        val conflict = summary.failures.single()
        assertEquals(LibraryItemId("drafted"), conflict.bookId)
        assertEquals(BulkGenreEditStage.Draft, conflict.stage)
        assertIs<AppError.Conflict>(conflict.error)
        assertEquals(existingDraft, fixture.metadata.draftFor("drafted"))
        assertEquals(listOf("reload:other", "save:other"), fixture.metadata.events)
        assertEquals(1, fixture.metadata.draftCheckCount("drafted"))
        assertEquals(2, fixture.metadata.draftCheckCount("other"))
    }

    @Test
    fun `a draft created while reload is in flight prevents the following patch`() = runTest {
        val current = genreBook("one", "Sci fi")
        val arrivingDraft = BookMetadataEdit.of(current).copy(title = "Work from the editor")
        val fixture = Fixture(listOf(current))
        fixture.metadata.afterReload = { fixture.metadata.setDraft("one", arrivingDraft) }

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(0, summary.updatedCount)
        assertEquals(1, summary.draftConflictCount)
        assertEquals(0, summary.unprocessedCount)
        assertEquals(BulkGenreEditStage.Draft, summary.failures.single().stage)
        assertEquals(arrivingDraft, fixture.metadata.draftFor("one"))
        assertEquals(2, fixture.metadata.draftCheckCount("one"))
        assertEquals(listOf("reload:one"), fixture.metadata.events)
        assertTrue(fixture.metadata.saves.isEmpty())
    }

    @Test
    fun `a draft created while a failing patch is in flight is not overwritten by recovery`() = runTest {
        val current = genreBook("one", "Sci fi")
        val arrivingDraft = BookMetadataEdit.of(current).copy(title = "New editor work")
        val fixture = Fixture(listOf(current))
        fixture.metadata.failSave("one", AppError.Network(summary = "The connection was lost."))
        fixture.metadata.afterSaveAttempt = { fixture.metadata.setDraft("one", arrivingDraft) }

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.failedCount)
        assertEquals(BulkGenreEditStage.Save, summary.failures.single().stage)
        assertEquals(arrivingDraft, fixture.metadata.draftFor("one"))
        assertEquals(3, fixture.metadata.draftCheckCount("one"))
        assertTrue(fixture.metadata.savedDrafts.isEmpty())
    }

    @Test
    fun `a profile switch during a failing patch does not persist an old profile recovery draft`() = runTest {
        val fixture = Fixture(listOf(genreBook("one", "Sci fi")))
        fixture.metadata.failSave("one", AppError.Network(summary = "The connection was lost."))
        fixture.metadata.afterSaveAttempt = {
            fixture.profiles.setActiveProfile(ProfileId("profile-2"))
        }

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.failedCount)
        assertTrue(fixture.metadata.savedDrafts.isEmpty())
        assertNull(fixture.metadata.draftFor("one"))
    }

    @Test
    fun `a systemic failure stops before later books are contacted or given drafts`() = runTest {
        val systemic = listOf<AppError>(
            AppError.Network(summary = "The connection was lost."),
            AppError.Timeout(summary = "The server stopped answering."),
            AppError.Server(summary = "The server is unavailable.", statusCode = 503),
            AppError.Server(summary = "The server asked the client to slow down.", statusCode = 429),
        )

        systemic.forEach { error ->
            val books = listOf(
                genreBook("saved", "Sci fi"),
                genreBook("failed", "Sci fi"),
                genreBook("not-attempted", "Sci fi"),
            )
            val fixture = Fixture(books)
            fixture.metadata.failSave("failed", error)

            val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

            val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
            assertEquals(1, summary.updatedCount)
            assertEquals(1, summary.failedCount)
            assertEquals(1, summary.unprocessedCount)
            assertEquals(error, summary.stopReason)
            assertEquals(
                listOf("reload:saved", "save:saved", "reload:failed", "save:failed"),
                fixture.metadata.events,
            )
            assertEquals(listOf(LibraryItemId("failed")), fixture.metadata.savedDrafts.map { it.first })
            assertNull(fixture.metadata.draftFor("not-attempted"))
        }
    }

    @Test
    fun `a forbidden save refreshes permissions once and stops sending writes`() = runTest {
        val books = listOf(
            genreBook("saved", "Sci fi"),
            genreBook("forbidden", "Sci fi"),
            genreBook("not-attempted", "Sci fi"),
        )
        val fixture = Fixture(books)
        fixture.metadata.failSave(
            "forbidden",
            AppError.Authorization(summary = "The account can no longer update metadata."),
        )

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.unprocessedCount)
        assertIs<AppError.Authorization>(summary.stopReason)
        assertEquals(listOf(TEST_PROFILE), fixture.auth.permissionRefreshes)
        assertEquals(
            listOf("reload:saved", "save:saved", "reload:forbidden", "save:forbidden"),
            fixture.metadata.events,
        )
    }

    @Test
    fun `a profile switch stops the remaining books at the profile boundary`() = runTest {
        val books = listOf(genreBook("one", "Sci fi"), genreBook("two", "Sci fi"))
        val fixture = Fixture(books)
        fixture.metadata.afterSave = { bookId ->
            if (bookId.value == "one") fixture.profiles.setActiveProfile(ProfileId("profile-2"))
        }

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.unprocessedCount)
        assertIs<AppError.Canceled>(summary.stopReason)
        assertEquals(listOf("reload:one", "save:one"), fixture.metadata.events)
    }

    @Test
    fun `a profile switch while an item reloads prevents that item from being written`() = runTest {
        val fixture = Fixture(listOf(genreBook("one", "Sci fi")))
        fixture.metadata.afterReload = { fixture.profiles.setActiveProfile(ProfileId("profile-2")) }

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(0, summary.updatedCount)
        assertEquals(1, summary.unprocessedCount)
        assertIs<AppError.Canceled>(summary.stopReason)
        assertEquals(listOf("reload:one"), fixture.metadata.events)
    }

    @Test
    fun `a landed save whose refresh failed is counted as updated and locally stale`() = runTest {
        val fixture = Fixture(listOf(genreBook("one", "Sci fi")))
        fixture.metadata.markSaveLocallyStale("one")

        val result = fixture.useCase(TEST_PROFILE, "Sci fi", "Science Fiction")

        val summary = assertIs<AppResult.Success<BulkGenreEditSummary>>(result).value
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.locallyStaleCount)
    }

    private fun genreBook(id: String, vararg genres: String): Book = book(id).copy(genres = genres.toList())

    private class Fixture(books: List<Book>, canUpdate: Boolean = true, isOnline: Boolean = true) {
        val profiles = FakeProfileRepository(profile().copy(canUpdate = canUpdate))
        val libraries = FakeLibraryRepository(books)
        val metadata = RecordingMetadataRepository(books)
        val auth = FakeAuthRepository()
        private val network = FakeNetworkMonitor(isOnline)

        val useCase = BulkEditGenresUseCase(
            profiles = profiles,
            libraries = libraries,
            metadata = metadata,
            auth = auth,
            network = network,
        )
    }

    private class FakeNetworkMonitor(isOnline: Boolean) : NetworkMonitor {
        override val isOnline = MutableStateFlow(isOnline)
        override val isUnmetered = MutableStateFlow(isOnline)
    }

    private data class SavedEdit(
        val bookId: LibraryItemId,
        val edit: BookMetadataEdit,
        val changed: Set<BookMetadataField>,
    )

    private class RecordingMetadataRepository(books: List<Book>) : MetadataRepository {
        private val latest = books.associateBy { it.id }.toMutableMap()
        private val reloadFailures = mutableMapOf<LibraryItemId, AppError>()
        private val saveFailures = mutableMapOf<LibraryItemId, AppError>()
        private val staleSaves = mutableSetOf<LibraryItemId>()
        private val drafts = mutableMapOf<LibraryItemId, BookMetadataEdit>()
        private val draftChecks = mutableMapOf<LibraryItemId, Int>()

        val events = mutableListOf<String>()
        val saves = mutableListOf<SavedEdit>()
        val savedDrafts = mutableListOf<Pair<LibraryItemId, BookMetadataEdit>>()
        var afterReload: suspend (LibraryItemId) -> Unit = {}
        var afterSaveAttempt: suspend (LibraryItemId) -> Unit = {}
        var afterSave: suspend (LibraryItemId) -> Unit = {}

        fun reloadAs(book: Book) {
            latest[book.id] = book
        }

        fun failReload(id: String, error: AppError) {
            reloadFailures[LibraryItemId(id)] = error
        }

        fun failSave(id: String, error: AppError) {
            saveFailures[LibraryItemId(id)] = error
        }

        fun markSaveLocallyStale(id: String) {
            staleSaves += LibraryItemId(id)
        }

        fun setDraft(id: String, draft: BookMetadataEdit) {
            drafts[LibraryItemId(id)] = draft
        }

        fun draftFor(id: String): BookMetadataEdit? = drafts[LibraryItemId(id)]

        fun draftCheckCount(id: String): Int = draftChecks[LibraryItemId(id)] ?: 0

        override fun observeDraft(profileId: ProfileId, bookId: LibraryItemId): Flow<BookMetadataEdit?> = flow {
            draftChecks[bookId] = (draftChecks[bookId] ?: 0) + 1
            emit(drafts[bookId])
        }

        override suspend fun saveDraft(profileId: ProfileId, bookId: LibraryItemId, edit: BookMetadataEdit) {
            drafts[bookId] = edit
            savedDrafts += bookId to edit
        }

        override suspend fun discardDraft(profileId: ProfileId, bookId: LibraryItemId) {
            drafts.remove(bookId)
        }

        override suspend fun reload(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book> {
            events += "reload:${bookId.value}"
            reloadFailures[bookId]?.let { return AppResult.Failure(it) }
            afterReload(bookId)
            return AppResult.Success(checkNotNull(latest[bookId]))
        }

        override suspend fun save(
            profileId: ProfileId,
            bookId: LibraryItemId,
            edit: BookMetadataEdit,
            changed: Set<BookMetadataField>,
        ): AppResult<MetadataSaveResult> {
            events += "save:${bookId.value}"
            saves += SavedEdit(bookId, edit, changed)
            afterSaveAttempt(bookId)
            saveFailures[bookId]?.let { return AppResult.Failure(it) }
            latest[bookId] = checkNotNull(latest[bookId]).copy(genres = edit.genres)
            drafts.remove(bookId)
            afterSave(bookId)
            return AppResult.Success(
                MetadataSaveResult(
                    book = latest[bookId].takeUnless { bookId in staleSaves },
                    isLocalCopyStale = bookId in staleSaves,
                ),
            )
        }

        override suspend fun uploadCover(
            profileId: ProfileId,
            bookId: LibraryItemId,
            bytes: ByteArray,
            mimeType: String,
        ): AppResult<Book> = notUsed()

        override suspend fun removeCover(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book> = notUsed()

        override suspend fun findCandidates(
            profileId: ProfileId,
            provider: String,
            title: String,
            author: String,
        ): AppResult<List<MatchCandidate>> = notUsed()

        override suspend fun metadataProviders(profileId: ProfileId): AppResult<List<MetadataProvider>> = notUsed()

        override suspend fun scanItem(profileId: ProfileId, bookId: LibraryItemId): AppResult<String> = notUsed()

        override suspend fun removeFromDatabase(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
            notUsed()

        override suspend fun embedMetadata(profileId: ProfileId, bookId: LibraryItemId): AppResult<EmbedRequest> =
            notUsed()

        private fun <T> notUsed(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "Not part of this test fake."))
    }
}
