package com.example.shelfplayer.data.library

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.common.time.ServerClock
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal open class SessionSyncRepositoryFixture {
    protected val fixtureServerId = "server-1"
    protected val fixtureBookTitle = "The Salt Harbour"
    protected val fixtureBookAuthor = "Marisol Holt"
    protected val fixtureBookKey = EntityKey.of(fixtureServerId, "book-1")
    protected val fixtureLibraryKey = EntityKey.of(fixtureServerId, "lib-1")

    protected lateinit var database: ShelfPlayerDatabase
    protected lateinit var repository: DefaultSessionSyncRepository
    protected lateinit var settings: AppSettingsDataSource
    protected lateinit var storeScope: CoroutineScope
    protected lateinit var storeFile: File
    protected val sink = RecordingLogSink()
    protected val clock = TestAppClock()
    protected val gateway = SessionSyncRecordingGateway()
    protected val auth = RecordingAuthRepository()
    protected val profileId = ProfileId("profile-1")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File(context.cacheDir, "session-sync-test.pb").also(File::delete)
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        settings = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = storeScope,
                produceFile = { storeFile },
            ),
            logger = logger,
        )
        repository = DefaultSessionSyncRepository(
            settings = settings,
            profileDao = database.profileDao(),
            outbox = database.sessionOutboxDao(),
            progressDao = database.progressDao(),
            requests = SessionSyncRequests(gateway, auth),
            serverClock = ServerClock(logger),
            clock = clock,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
        settings.setActiveProfile(profileId)
    }

    @After
    fun tearDown() {
        database.close()
        storeScope.cancel()
        storeFile.delete()
    }

    /** The row `recordPosition` would have written: a local position nothing has uploaded yet. */
    protected suspend fun seedUnsyncedProgress(recordedAt: Long, position: Duration = 40.minutes) {
        seedBook()
        database.progressDao().upsertProgress(
            listOf(
                MediaProgressEntity(
                    progressKey = EntityKey.scoped(profileId.value, fixtureBookKey),
                    profileId = profileId.value,
                    bookKey = fixtureBookKey,
                    serverId = fixtureServerId,
                    positionMillis = position.inWholeMilliseconds,
                    durationMillis = 6.hours.inWholeMilliseconds,
                    isFinished = false,
                    updatedAt = recordedAt,
                    hasUnsyncedChanges = true,
                ),
            ),
        )
    }

    /**
     * The library and book the progress row hangs off.
     *
     * `media_progress` is keyed through both, so a position for a book this device has never heard of is
     * not a state the app can reach — and not one a test should be able to fabricate either.
     */
    protected suspend fun seedBook() {
        database.libraryWriteDao().upsertLibraries(
            listOf(
                LibraryEntity(
                    libraryKey = fixtureLibraryKey,
                    serverId = fixtureServerId,
                    remoteId = "lib-1",
                    name = "Fiction",
                    kind = "Book",
                    displayOrder = 0,
                    remoteUpdatedAt = null,
                    lastFetchedAt = 0,
                    isDeleted = false,
                    finishedTimeRemainingSeconds = null,
                ),
            ),
        )
        database.libraryWriteDao().upsertBooks(
            listOf(
                BookEntity(
                    bookKey = fixtureBookKey,
                    serverId = fixtureServerId,
                    remoteId = "book-1",
                    libraryKey = fixtureLibraryKey,
                    title = fixtureBookTitle,
                    subtitle = null,
                    narratorsJson = "[]",
                    genresJson = "[]",
                    tagsJson = "[]",
                    durationMillis = 6.hours.inWholeMilliseconds,
                    description = null,
                    publishedYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    isExplicit = false,
                    isAbridged = false,
                    coverPath = null,
                    trackCount = 1,
                    sizeBytes = 0,
                    remoteUpdatedAt = null,
                    addedAt = null,
                    lastFetchedAt = 0,
                    isDeleted = false,
                    localAvailability = "NotDownloaded",
                ),
            ),
        )
    }

    protected suspend fun storedProgress(): MediaProgressEntity =
        requireNotNull(database.progressDao().findProgress(profileId.value, fixtureBookKey)) {
            "no progress row was written"
        }

    protected suspend fun open(remoteSessionId: String? = "remote-1") = repository.openSession(
        session = PlaybackSession(
            id = remoteSessionId.orEmpty(),
            profileId = profileId,
            bookId = LibraryItemId("book-1"),
            title = fixtureBookTitle,
            author = fixtureBookAuthor,
            coverUrl = null,
            startAt = 0.seconds,
            duration = 6.hours,
            tracks = emptyList(),
            chapters = emptyList(),
        ),
        startedAt = clock.now(),
    )

    protected suspend fun openId(remoteSessionId: String? = "remote-1") =
        assertIs<AppResult.Success<String>>(open(remoteSessionId)).value

    protected suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = fixtureServerId,
                displayName = "Demo",
                baseUrl = "https://books.example",
                detectedVersion = "2.36.0",
                isFixture = false,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = profileId.value,
                serverId = fixtureServerId,
                remoteUserId = null,
                username = "kari",
                displayName = "Kari",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    /**
     * A gateway that records what it was asked to send.
     *
     * A hand-written fake rather than a mock (PRODUCT_SPEC 17.1), and it records *arguments* because most of
     * what these tests assert is about what went on the wire: that a rewind arrived as a rewind, and that a
     * retry carried the same id.
     */
    protected class RecordingAuthRepository : AuthRepository {
        var renewal: AppResult<SessionStatus> = AppResult.Success(SessionStatus.Active)
        var renewedProfile: ProfileId? = null
        var reauthenticationProfile: ProfileId? = null
        var onRenew: suspend () -> Unit = {}

        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> {
            renewedProfile = profileId
            onRenew()
            return renewal
        }

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = unsupported()
        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            unsupported()
        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = unsupported()
        override suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit> {
            reauthenticationProfile = profileId
            return AppResult.Success(Unit)
        }
        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = unsupported()
        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = unsupported()
        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = unsupported()

        private fun <T> unsupported(): AppResult<T> = AppResult.Failure(
            AppError.ApiCompatibility(summary = "not part of this fake"),
        )
    }
}
