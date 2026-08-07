package com.example.shelfplayer.domain

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.LibraryAccess
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.sync.BackgroundSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

internal val TEST_SERVER = ServerId("server-1")
internal val TEST_PROFILE = ProfileId("profile-1")
internal val TEST_LIBRARY = LibraryId("library-1")
internal val TEST_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal val TEST_SERVER_ROW = Server(
    id = TEST_SERVER,
    displayName = "Demo server",
    baseUrl = "https://books.example",
    detectedVersion = "2.36.0",
    isFixture = true,
)

internal fun profile(id: ProfileId = TEST_PROFILE) = Profile(
    id = id,
    serverId = TEST_SERVER,
    username = "demo",
    displayName = "Demo listener",
    role = ProfileRole.Listener,
    requiresReauthentication = false,
    lastUsedAt = TEST_INSTANT,
    isFixture = true,
)

internal fun library(id: LibraryId = TEST_LIBRARY, bookCount: Int = 0) = Library(
    serverId = TEST_SERVER,
    id = id,
    name = "Fiction",
    kind = LibraryKind.Book,
    displayOrder = 0,
    bookCount = bookCount,
    remoteUpdatedAt = null,
    lastFetchedAt = TEST_INSTANT,
)

@Suppress("LongParameterList")
internal fun book(
    id: String,
    title: String = id,
    authorName: String = "Marisol Holt",
    narrators: List<String> = listOf("Ada Fenwick"),
    sequence: String? = null,
    tags: List<String> = emptyList(),
    updatedAt: Instant? = null,
    libraryId: LibraryId = TEST_LIBRARY,
    /** When this profile last listened. `null` is a book with no progress row at all. */
    playedAt: Instant? = null,
    isFinished: Boolean = false,
) = Book(
    serverId = TEST_SERVER,
    id = LibraryItemId(id),
    libraryId = libraryId,
    title = title,
    subtitle = null,
    authors = listOf(Author(TEST_SERVER, AuthorId("author-1"), authorName)),
    narrators = narrators,
    seriesMemberships = sequence?.let {
        listOf(
            SeriesMembership(
                series = Series(TEST_SERVER, SeriesId("series-1"), "The Long Voyage"),
                sequence = SeriesSequence.parse(it),
                isPrimary = true,
            ),
        )
    }.orEmpty(),
    duration = kotlin.time.Duration.ZERO,
    description = null,
    genres = emptyList(),
    tags = tags,
    publishedYear = null,
    publisher = null,
    language = null,
    isExplicit = false,
    isAbridged = false,
    coverPath = null,
    trackCount = 1,
    sizeBytes = 0,
    remoteUpdatedAt = updatedAt,
    lastFetchedAt = TEST_INSTANT,
    progress = playedAt?.let {
        MediaProgress(
            serverId = TEST_SERVER,
            profileId = TEST_PROFILE,
            bookId = LibraryItemId(id),
            position = 1.minutes,
            duration = 10.minutes,
            isFinished = isFinished,
            updatedAt = it,
            hasUnsyncedChanges = false,
        )
    },
    localAvailability = LocalAvailability.NotDownloaded,
)

/**
 * Fakes rather than mocks.
 *
 * A fake with real state catches the bugs that matter here — "does this flow re-emit when the
 * active profile changes?" — which a stubbed call-verification cannot.
 */
internal class FakeProfileRepository(active: Profile? = profile()) : ProfileRepository {
    private val activeProfile = MutableStateFlow(active)

    override fun observeProfiles(): Flow<List<Profile>> = activeProfile.map { listOfNotNull(it) }

    override fun observeServers(): Flow<List<Server>> = MutableStateFlow(listOf(TEST_SERVER_ROW))

    override fun observeActiveProfile(): Flow<Profile?> = activeProfile

    override suspend fun activeProfileId(): ProfileId? = activeProfile.first()?.id

    private var refuseSwitch = false

    override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
        if (refuseSwitch) {
            return AppResult.Failure(AppError.Validation(summary = "That profile is no longer saved."))
        }
        activeProfile.value = profile(profileId)
        return AppResult.Success(Unit)
    }

    /** What the real repository does for a profile id that no longer resolves to a saved row. */
    fun refuseSwitches() {
        refuseSwitch = true
    }

    fun signOut() {
        activeProfile.value = null
    }
}

internal class FakeLibraryRepository(books: List<Book> = emptyList(), libraries: List<Library> = listOf(library())) :
    LibraryRepository {
    private val storedBooks = MutableStateFlow(books)
    private val storedLibraries = MutableStateFlow(libraries)

    var refreshResult: AppResult<Int> = AppResult.Success(0)
    val refreshedProfiles = mutableListOf<ProfileId>()

    /** What a progress-only sync handed over, so a test can assert it happened without a library sweep. */
    val progressWritten = mutableListOf<List<AccountProgress>>()

    override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> {
        progressWritten += progress
        return AppResult.Success(progress.size)
    }

    override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = storedLibraries

    override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
        storedLibraries.map { libraries -> libraries.firstOrNull { it.id == libraryId } }

    override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
        storedBooks.map { all -> all.filter { it.libraryId == libraryId } }

    override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = storedBooks

    override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
        storedBooks.map { all -> all.firstOrNull { it.id == bookId } }

    override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
        MutableStateFlow(SyncState.idle(TEST_SERVER, profileId))

    /**
     * Answers are queued so a test can make the first refresh fail and the second succeed, which is the
     * only way to observe the AUTH-004 renew-and-retry policy rather than just its outcome.
     */
    private val queuedResults = ArrayDeque<AppResult<Int>>()

    override suspend fun refresh(profileId: ProfileId): AppResult<Int> {
        refreshedProfiles += profileId
        return queuedResults.removeFirstOrNull() ?: refreshResult
    }

    fun queueRefreshResults(vararg results: AppResult<Int>) {
        queuedResults.clear()
        queuedResults.addAll(results)
    }
}

/**
 * PRODUCT_SPEC AUTH-004 — a session layer whose renewal outcome the test dictates.
 *
 * It counts renewal attempts, because the requirement "the app never loops login requests" is a
 * statement about how many times this is called, not about what it returns.
 */
internal class FakeAuthRepository(
    private var renewal: AppResult<SessionStatus> = AppResult.Success(SessionStatus.Active),
) : AuthRepository {
    val renewedProfiles = mutableListOf<ProfileId>()

    fun willRenewInto(status: SessionStatus) {
        renewal = AppResult.Success(status)
    }

    fun willFailToRenew(error: AppError) {
        renewal = AppResult.Failure(error)
    }

    override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
        renewedProfiles += profileId
        return renewal
    }

    /**
     * PRODUCT_SPEC 5.2 — recorded rather than stubbed, because the requirements under test are about
     * *when* a permission refresh happens: after a `403`, and on a profile switch. Never, and once, are
     * both interesting answers.
     */
    val permissionRefreshes = mutableListOf<ProfileId>()

    private var permissions: AppResult<AccountState> = AppResult.Success(account(emptyList()))

    private fun account(progress: List<AccountProgress>) = AccountState(
        userId = null,
        username = "ada",
        role = ProfileRole.Listener,
        access = LibraryAccess.None,
        progress = progress,
    )

    fun willFailToRefreshPermissions(error: AppError) {
        permissions = AppResult.Failure(error)
    }

    override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> {
        permissionRefreshes += profileId
        return permissions
    }

    fun willReportProgress(progress: List<AccountProgress>) {
        permissions = AppResult.Success(account(progress))
    }

    private var signIn: AppResult<Profile> = AppResult.Success(profile())

    val signInCalls = mutableListOf<String>()

    fun willFailToSignIn(error: AppError) {
        signIn = AppResult.Failure(error)
    }

    override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = notUsed()

    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> {
        signInCalls += username
        return signIn
    }

    private var restore: AppResult<SessionStatus> = AppResult.Success(SessionStatus.Active)

    val restoredProfiles = mutableListOf<ProfileId>()

    fun willRestoreInto(status: SessionStatus) {
        restore = AppResult.Success(status)
    }

    override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> {
        restoredProfiles += profileId
        return restore
    }

    override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = notUsed()

    override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = notUsed()

    /** Fails loudly rather than returning an empty value, so a test cannot pass by accident. */
    private fun <T> notUsed(): AppResult<T> =
        AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
}

/**
 * PRODUCT_SPEC SYNC-003 — a scheduler that records rather than schedules.
 *
 * What the requirements say is *when* work is created and cancelled, not what WorkManager does with
 * it, so a recording fake is the whole of what a test needs here.
 */
internal class FakeBackgroundSync : BackgroundSync {
    val scheduled = mutableListOf<ProfileId>()
    val cancelled = mutableListOf<ProfileId>()

    override suspend fun schedule(profileId: ProfileId) {
        scheduled += profileId
    }

    override suspend fun cancel(profileId: ProfileId) {
        cancelled += profileId
    }
}
