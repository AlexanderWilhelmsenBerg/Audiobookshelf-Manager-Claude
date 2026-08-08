package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 — the progress journal, against a real database.
 *
 * The two properties tested here are the ones product priority 2 depends on, and neither is visible by
 * reading the class: a journaled position is flagged unsynced so the next account sync cannot rewind
 * it, and a finished book cannot be un-finished by replaying its last minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultPlaybackRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultPlaybackRepository
    private lateinit var libraryRepository: DefaultLibraryRepository
    private val sink = RecordingLogSink()
    private val profileId = ProfileId("fixture-profile")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dispatcher = UnconfinedTestDispatcher()
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        val gateway = FakeAudiobookshelfGateway(
            loader = FixtureLibraryLoader(),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

        repository = DefaultPlaybackRepository(
            profileRepository = StubProfileRepository(profileId),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            gateway = gateway,
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
        libraryRepository = DefaultLibraryRepository(
            libraryDao = database.libraryDao(),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            syncStateDao = database.syncStateDao(),
            gateway = gateway,
            writer = LibrarySnapshotWriter(
                transaction = com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner(database),
                libraryWriteDao = database.libraryWriteDao(),
                progressDao = database.progressDao(),
            ),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

        seedProfile()
        libraryRepository.refresh(profileId)
    }

    /** The one profile these tests act as, and the server it belongs to. */
    private suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture-0",
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = profileId.value,
                serverId = SERVER,
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a journaled position is stored against the profile that was listening`() = runTest {
        repository.recordPosition(BOOK, position = 3.minutes, duration = 2.hours, isFinished = false)

        val stored = storedProgress()
        assertEquals(180_000L, stored.positionMillis)
        assertEquals(2.hours.inWholeMilliseconds, stored.durationMillis)
        assertEquals(profileId.value, stored.profileId)
    }

    /**
     * The flag that stops the server rewinding a book being listened to right now.
     *
     * `DefaultLibraryRepository.writeProgress` declines to overwrite an unsynced row. This is the write
     * that sets the flag, and the second half of the test is the rule it buys: the account sync arrives
     * carrying the server's older position and does not apply it.
     */
    @Test
    fun `a journaled position is not overwritten by the server's older one`() = runTest {
        repository.recordPosition(BOOK, position = 40.minutes, duration = 2.hours, isFinished = false)
        assertTrue(storedProgress().hasUnsyncedChanges)

        libraryRepository.writeProgress(
            profileId,
            listOf(
                AccountProgress(
                    bookId = BOOK,
                    position = 5.minutes,
                    duration = 2.hours,
                    isFinished = false,
                    updatedAt = Instant.ofEpochMilli(Long.MAX_VALUE / 2),
                ),
            ),
        )

        assertEquals(40.minutes.inWholeMilliseconds, storedProgress().positionMillis)
    }

    /**
     * ADR-0013 / product priority 2 — un-finishing is the user's decision, not a side effect.
     *
     * Replaying the last minute of a finished book puts the position back below the threshold. The book
     * stays finished.
     */
    @Test
    fun `a finished book is not un-finished by replaying the end`() = runTest {
        repository.recordPosition(BOOK, position = 2.hours, duration = 2.hours, isFinished = true)

        repository.recordPosition(BOOK, position = 1.hours, duration = 2.hours, isFinished = false)

        val stored = storedProgress()
        assertTrue(stored.isFinished, "still finished")
        assertEquals(1.hours.inWholeMilliseconds, stored.positionMillis, "and the position still moved")
    }

    /**
     * A duration the player does not know yet must not erase the one the library stored.
     *
     * ExoPlayer reports an unknown duration while a stream is still being prepared, and writing zero
     * would empty the book's progress bar for as long as it took to buffer.
     */
    @Test
    fun `an unknown duration keeps the stored one`() = runTest {
        repository.recordPosition(BOOK, position = 10.minutes, duration = 2.hours, isFinished = false)

        repository.recordPosition(BOOK, position = 11.minutes, duration = kotlin.time.Duration.ZERO, isFinished = false)

        assertEquals(2.hours.inWholeMilliseconds, storedProgress().durationMillis)
    }

    /** A negative position — a player reporting `-1` for "unset" — is stored as the start, not as -1. */
    @Test
    fun `a negative position is stored as the start`() = runTest {
        repository.recordPosition(BOOK, position = (-30).seconds, duration = 2.hours, isFinished = false)

        assertEquals(0L, storedProgress().positionMillis)
    }

    /** PRODUCT_SPEC 5.2 — with no active profile there is nothing to attribute a position to. */
    @Test
    fun `no active profile is an authentication failure rather than a stray row`() = runTest {
        val orphaned = DefaultPlaybackRepository(
            profileRepository = StubProfileRepository(activeProfileId = null),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            gateway = FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
                ioDispatcher = UnconfinedTestDispatcher(),
            ),
            clock = TestAppClock(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        // The fixture library already wrote the server's own positions, so "no stray row" is a count
        // that did not change rather than a count of zero.
        val before = database.progressDao().findProgressFor(profileId.value)

        val result = orphaned.recordPosition(BOOK, 5.minutes, 2.hours, isFinished = false)

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
        val after = database.progressDao().findProgressFor(profileId.value)
        assertEquals(before, after, "nothing was written")
    }

    /** PRODUCT_SPEC 14.5 — the finished-threshold log names no book. */
    @Test
    fun `reaching the finished threshold logs no media title`() = runTest {
        repository.recordPosition(BOOK, position = 2.hours, duration = 2.hours, isFinished = true)

        assertTrue(sink.text.contains("finished threshold"), "the event is logged: ${sink.text}")
        assertTrue(!sink.text.contains(BOOK.value), "and it names no book")
    }

    private suspend fun storedProgress(): MediaProgressEntity =
        requireNotNull(database.progressDao().findProgress(profileId.value, EntityKey.of(SERVER, BOOK.value))) {
            "no progress row was written"
        }

    /** The active profile, without a database of profiles behind it. */
    private class StubProfileRepository(private val activeProfileId: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = flowOf(null)

        override suspend fun activeProfileId(): ProfileId? = activeProfileId

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private companion object {
        const val SERVER = "fixture-server"

        /** A book the fixture library actually holds, so the row has something to attach to. */
        val BOOK = LibraryItemId("book-voyage-1")
    }
}
