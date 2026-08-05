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
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

internal val TEST_SERVER = ServerId("server-1")
internal val TEST_PROFILE = ProfileId("profile-1")
internal val TEST_LIBRARY = LibraryId("library-1")
internal val TEST_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

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
) = Book(
    serverId = TEST_SERVER,
    id = LibraryItemId(id),
    libraryId = TEST_LIBRARY,
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
    progress = null,
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

    override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = storedLibraries

    override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
        storedLibraries.map { libraries -> libraries.firstOrNull { it.id == libraryId } }

    override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
        storedBooks.map { all -> all.filter { it.libraryId == libraryId } }

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
