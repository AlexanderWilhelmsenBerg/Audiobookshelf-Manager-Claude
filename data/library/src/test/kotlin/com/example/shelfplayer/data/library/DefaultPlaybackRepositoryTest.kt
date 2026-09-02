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
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.download.SmartDownload
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
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

    /** PRODUCT_SPEC 6.5 — mutable, so a test can switch the app to another account mid-journal. */
    private val profiles = StubProfileRepository(profileId)

    /** PRODUCT_SPEC DL-005 / 6.5 — records the halfway triggers a write produced, so absence is assertable. */
    private val smartDownloads = mutableListOf<LibraryItemId>()

    /** PRODUCT_SPEC SYNC-002 — the scripted half of the gateway, set up in [setUp]. */
    private lateinit var serverProgress: ScriptedProgressGateway

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dispatcher = UnconfinedTestDispatcher()
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        val gateway = ScriptedProgressGateway(
            FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = logger,
                ioDispatcher = dispatcher,
            ),
        )
        serverProgress = gateway

        repository = DefaultPlaybackRepository(
            profileRepository = profiles,
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            libraryDao = database.libraryDao(),
            gateway = gateway,
            downloads = DownloadSupport(
                sessions = OfflineSessionBuilder(
                    libraryDao = database.libraryDao(),
                    progressDao = database.progressDao(),
                    downloads = NoDownloads,
                ),
                // PRODUCT_SPEC DL-005 — recorded rather than performed. These cases are about the journal,
                // and a trigger that fetched a book would make every one of them depend on a catalogue; but
                // 6.5 needs to assert that a write for a departed account produces *no* trigger, and
                // `SmartDownload.Disabled` cannot tell "not called" from "called and did nothing".
                smartDownload = SmartDownload { bookId, _, _, _ -> smartDownloads += bookId },
            ),
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
                historyDao = database.playbackHistoryDao(),
            ),
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

        seedProfile()
        libraryRepository.refresh(profileId)
    }

    /**
     * A profile row, and the server it belongs to.
     *
     * Parameterised since PRODUCT_SPEC 6.5: a test about a write landing on the wrong account needs a
     * second account for it to land on, and `recordPosition` reads the row to find the server key.
     */
    private suspend fun seedProfile(id: ProfileId = profileId) {
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
                profileId = id.value,
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
                canDownload = false,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a journaled position is stored against the profile that was listening`() = runTest {
        repository.recordPosition(BOOK, position = 3.minutes, duration = 2.hours)

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
        repository.recordPosition(BOOK, position = 40.minutes, duration = 2.hours)
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
        repository.recordPosition(BOOK, position = 2.hours, duration = 2.hours)

        repository.recordPosition(BOOK, position = 1.hours, duration = 2.hours)

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
        repository.recordPosition(BOOK, position = 10.minutes, duration = 2.hours)

        repository.recordPosition(BOOK, position = 11.minutes, duration = kotlin.time.Duration.ZERO)

        assertEquals(2.hours.inWholeMilliseconds, storedProgress().durationMillis)
    }

    /** A negative position — a player reporting `-1` for "unset" — is stored as the start, not as -1. */
    @Test
    fun `a negative position is stored as the start`() = runTest {
        repository.recordPosition(BOOK, position = (-30).seconds, duration = 2.hours)

        assertEquals(0L, storedProgress().positionMillis)
    }

    /** PRODUCT_SPEC 5.2 — with no active profile there is nothing to attribute a position to. */
    @Test
    fun `no active profile is an authentication failure rather than a stray row`() = runTest {
        val orphaned = DefaultPlaybackRepository(
            profileRepository = StubProfileRepository(active = null),
            profileDao = database.profileDao(),
            progressDao = database.progressDao(),
            libraryDao = database.libraryDao(),
            gateway = FakeAudiobookshelfGateway(
                loader = FixtureLibraryLoader(),
                clock = TestAppClock(),
                logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
                ioDispatcher = UnconfinedTestDispatcher(),
            ),
            downloads = DownloadSupport(
                sessions = OfflineSessionBuilder(
                    libraryDao = database.libraryDao(),
                    progressDao = database.progressDao(),
                    downloads = NoDownloads,
                ),
                // PRODUCT_SPEC DL-005 — off, which is its default. These cases are about the journal, and
                // a trigger that fetched a book would make every one of them depend on a catalogue.
                smartDownload = SmartDownload.Disabled,
            ),
            clock = TestAppClock(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        // The fixture library already wrote the server's own positions, so "no stray row" is a count
        // that did not change rather than a count of zero.
        val before = database.progressDao().findProgressFor(profileId.value)

        val result = orphaned.recordPosition(BOOK, 5.minutes, 2.hours)

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
        val after = database.progressDao().findProgressFor(profileId.value)
        assertEquals(before, after, "nothing was written")
    }

    // --- The finished rule, both halves (PLAY-004 / ADR-0013) ---------------------------------------

    /**
     * The library's rule wins, which is what *"inherit from the web interface"* means.
     *
     * The fixture libraries carry the capture server's own `markAsFinishedTimeRemaining: 10` against a
     * default setting of 30. So a book with twenty seconds left is **not** finished here — because the
     * Audiobookshelf web interface would not call it finished either. An earlier build took the `max` and
     * finished it, which is the disagreement this replaced.
     */
    @Test
    fun `the library's own rule is what decides`() = runTest {
        repository.recordPosition(BOOK, position = 2.hours - 20.seconds, duration = 2.hours)
        assertFalse(storedProgress().isFinished, "twenty seconds left, against the library's ten")

        repository.recordPosition(BOOK, position = 2.hours - 8.seconds, duration = 2.hours)
        assertTrue(storedProgress().isFinished, "eight seconds left is inside the library's rule")
    }

    /** A library that asks for longer is honoured just as literally, through the same join. */
    @Test
    fun `a library asking for longer finishes the book earlier`() = runTest {
        libraryRule(90.seconds)

        repository.recordPosition(BOOK, position = 2.hours - 60.seconds, duration = 2.hours)

        assertTrue(storedProgress().isFinished, "a minute left, against a library asking for ninety seconds")
    }

    /**
     * Where the library has said nothing, the server's own default of ten seconds applies (ADR-0013).
     *
     * `null` on the library row has to mean "no rule" rather than "zero seconds", or a library the app has not
     * synced since before database version 14 would finish nothing at all.
     */
    @Test
    fun `a library with no rule falls back to the server's own default`() = runTest {
        libraryRule(null)

        repository.recordPosition(BOOK, position = 2.hours - 20.seconds, duration = 2.hours)
        assertFalse(storedProgress().isFinished, "twenty seconds left is outside the fallback")

        repository.recordPosition(BOOK, position = 2.hours - 5.seconds, duration = 2.hours)
        assertTrue(storedProgress().isFinished, "five seconds left is inside it")
    }

    /**
     * The rule comes through the join, and the join is by book.
     *
     * A rule set on the *other* library must not decide anything about this book. A query that read "any
     * library on this server" would pass every other test in this file.
     */
    @Test
    fun `another library's rule does not apply`() = runTest {
        libraryRule(null)
        libraryRule(90.seconds, libraryId = "lib-nonfiction")

        repository.recordPosition(BOOK, position = 2.hours - 60.seconds, duration = 2.hours)

        assertFalse(storedProgress().isFinished, "this book's own library sets nothing, so the fallback applies")
    }

    /** Writes a library's own finished rule — or clears it — the way a sync writes one. */
    private suspend fun libraryRule(timeRemaining: kotlin.time.Duration?, libraryId: String = "lib-fiction") {
        val existing = requireNotNull(
            database.libraryDao().observeLibrary(EntityKey.of(SERVER, libraryId)).first(),
        ) { "the fixture refresh wrote no $libraryId row" }
        database.libraryWriteDao().upsertLibraries(
            listOf(existing.copy(finishedTimeRemainingSeconds = timeRemaining?.inWholeSeconds)),
        )
    }

    /** PRODUCT_SPEC 14.5 — the finished-threshold log names no book. */
    @Test
    fun `reaching the finished threshold logs no media title`() = runTest {
        repository.recordPosition(BOOK, position = 2.hours, duration = 2.hours)

        assertTrue(sink.text.contains("finished threshold"), "the event is logged: ${sink.text}")
        assertTrue(!sink.text.contains(BOOK.value), "and it names no book")
    }

    // --- Positions that arrived from somewhere else (PLAY-004 / SYNC-002) --------------------------

    /**
     * The device report: *"The history should also show the latest changes from the server."*
     *
     * A position that moved without this device moving it goes into the same history the seeks go into,
     * with **this device's** position as the "from" so the row is an undo, and with the **server's**
     * timestamp so it sorts where it happened rather than where the refresh noticed.
     *
     * The "before" is the fixture's own position for this book, which is what a real second device would be
     * arriving on top of.
     */
    @Test
    fun `a position that moved on another device becomes a history entry`() = runTest {
        remoteProgress(position = 50.minutes, updatedAt = LATER)

        val entry = historyEntries(BOOK).single()
        assertEquals(PlaybackEvent.RemoteProgress.name, entry.reason)
        assertEquals(FIXTURE_POSITION.inWholeMilliseconds, entry.fromMillis, "tapping it goes back to where we were")
        assertEquals(50.minutes.inWholeMilliseconds, entry.toMillis)
        assertEquals(LATER.toEpochMilli(), entry.at, "the server's moment, not the refresh's")
    }

    /**
     * The first position for a book is not news.
     *
     * The first sync of a library writes a position for every book the account has ever played. Recording
     * those would fill a pane that has never been opened with rows describing nothing that happened.
     */
    @Test
    fun `the first position for a book writes no history`() = runTest {
        remoteProgress(bookId = UNPLAYED, position = 10.minutes, updatedAt = LATER)

        assertEquals(emptyList(), historyEntries(UNPLAYED))
    }

    /**
     * This device's own position, echoed back by the server, is not another device.
     *
     * The journal writes every five seconds and the sync every thirty, so the copy the server returns can
     * legitimately be half a minute behind the local one while being the same listening. The tolerance is
     * what stops an evening of ordinary playback writing a "moved on another device" row every refresh.
     */
    @Test
    fun `a position that barely moved writes no history`() = runTest {
        remoteProgress(position = FIXTURE_POSITION + 20.seconds, updatedAt = LATER)

        assertEquals(emptyList(), historyEntries(BOOK))
    }

    /**
     * The finished flag is exempt from that tolerance, because it is not a position change at all.
     *
     * A book turning up finished when you did not finish it is the most surprising thing the server can do
     * to a listener, and it is exactly what a history exists to explain.
     */
    @Test
    fun `a book finished elsewhere is recorded even though the position hardly moved`() = runTest {
        remoteProgress(position = FIXTURE_POSITION, updatedAt = LATER, isFinished = true)

        assertEquals(PlaybackEvent.RemoteFinished.name, historyEntries(BOOK).single().reason)
    }

    private suspend fun remoteProgress(
        position: kotlin.time.Duration,
        updatedAt: Instant,
        bookId: LibraryItemId = BOOK,
        isFinished: Boolean = false,
    ) {
        libraryRepository.writeProgress(
            profileId,
            listOf(
                AccountProgress(
                    bookId = bookId,
                    position = position,
                    duration = 11.hours,
                    isFinished = isFinished,
                    updatedAt = updatedAt,
                ),
            ),
        )
    }

    private suspend fun historyEntries(bookId: LibraryItemId) = database.playbackHistoryDao()
        .observe(profileId.value, EntityKey.of(SERVER, bookId.value), limit = 50)
        .first()

    // ------------------------------------------- PRODUCT_SPEC SYNC-002, the check before an in-app Play

    /*
     * These are about one question — may this resume trust the position it is holding — and the three
     * answers it has. What each answer *costs* matters as much as which one it is: the first version of
     * this check read the account's whole listening history page by page and a device reported the Play
     * taking seconds. `requests` is asserted for that reason, not for tidiness.
     */

    /** The server was written after this device and holds a different position: the one case that moves. */
    @Test
    fun `a newer server position is reported as ahead`() = runTest {
        syncedProgress(position = 10.minutes, at = 1_000L)
        serverProgress.answer = AppResult.Success(
            ServerProgress(position = 61.minutes, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        val outcome = repository.checkServerPosition(BOOK, localPosition = 10.minutes)

        assertEquals(ExternalSessionCheck.Ahead(61.minutes), outcome)
        assertEquals(1, serverProgress.requests, "one request for one book, never a sweep of the account")
    }

    /**
     * **A server position that is *behind* is still ahead in the sense that matters.**
     *
     * Somebody rewinding on another device is newer activity even though the number is smaller. This is the
     * case a position-magnitude comparison gets backwards, and honouring it is why the timestamp decides.
     */
    @Test
    fun `an earlier server position from a later write is still adopted`() = runTest {
        syncedProgress(position = 60.minutes, at = 1_000L)
        serverProgress.answer = AppResult.Success(
            ServerProgress(position = 20.minutes, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        assertEquals(
            ExternalSessionCheck.Ahead(20.minutes),
            repository.checkServerPosition(BOOK, localPosition = 60.minutes),
        )
    }

    /** Our own upload bumps the server's `lastUpdate`, so a newer timestamp alone must not move anything. */
    @Test
    fun `a newer timestamp at the same position keeps the local one`() = runTest {
        syncedProgress(position = 30.minutes, at = 1_000L)
        serverProgress.answer = AppResult.Success(
            ServerProgress(position = 30.minutes, updatedAt = Instant.ofEpochMilli(9_000L), isFinished = false),
        )

        assertEquals(
            ExternalSessionCheck.Current,
            repository.checkServerPosition(BOOK, localPosition = 30.minutes),
        )
    }

    /** A second of drift is rounding between a fractional wire value and a stored millisecond, not a device. */
    @Test
    fun `a difference inside the tolerance keeps the local position`() = runTest {
        syncedProgress(position = 30.minutes, at = 1_000L)
        serverProgress.answer = AppResult.Success(
            ServerProgress(
                position = 30.minutes + 1.seconds,
                updatedAt = Instant.ofEpochMilli(2_000L),
                isFinished = false,
            ),
        )

        assertEquals(
            ExternalSessionCheck.Current,
            repository.checkServerPosition(BOOK, localPosition = 30.minutes),
        )
    }

    /**
     * **Unsynced local progress answers without asking the server at all.**
     *
     * The local row is the truth in that state by definition, so the request would be latency spent on an
     * answer that could not be acted on. It is also the common case for one person on one device, which is
     * why `requests` being zero here is the assertion that matters most for the reported delay.
     */
    @Test
    fun `unsynced local progress skips the request`() = runTest {
        repository.recordPosition(BOOK, position = 40.minutes, duration = 2.hours)
        assertTrue(storedProgress().hasUnsyncedChanges)

        assertEquals(
            ExternalSessionCheck.Current,
            repository.checkServerPosition(BOOK, localPosition = 40.minutes),
        )
        assertEquals(0, serverProgress.requests, "nothing to reconcile against, so nothing to ask")
    }

    /**
     * **A book with no local row is asked about, not assumed.**
     *
     * It used to skip the request, on the reasoning that a first play had just loaded from the server's own
     * position. That holds only while the loaded position is fresh — a book opened here and then played
     * elsewhere before Play is pressed has neither a fresh position nor a row to notice it with, which is
     * the case a device run hit.
     */
    @Test
    fun `a book with no local progress still asks the server`() = runTest {
        serverProgress.answer = AppResult.Success(
            ServerProgress(position = 25.minutes, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        assertEquals(
            ExternalSessionCheck.Ahead(25.minutes),
            repository.checkServerPosition(UNPLAYED, localPosition = Duration.ZERO),
        )
        assertEquals(1, serverProgress.requests)
    }

    /** And a genuine first play, where the player already sits on the server's position, does not seek. */
    @Test
    fun `a first play at the server's own position stays put`() = runTest {
        serverProgress.answer = AppResult.Success(
            ServerProgress(position = 25.minutes, updatedAt = Instant.ofEpochMilli(2_000L), isFinished = false),
        )

        assertEquals(
            ExternalSessionCheck.Current,
            repository.checkServerPosition(UNPLAYED, localPosition = 25.minutes),
        )
    }

    /** A failed read is the outcome that earns the struck-through cloud: resumed, but unverified. */
    @Test
    fun `a failed read reports the server was not reached`() = runTest {
        syncedProgress(position = 10.minutes, at = 1_000L)
        serverProgress.answer = AppResult.Failure(AppError.Network(summary = "No connection."))

        assertEquals(
            ExternalSessionCheck.Unavailable,
            repository.checkServerPosition(BOOK, localPosition = 10.minutes),
        )
    }

    /**
     * A stored row the server has already accepted, at a chosen moment.
     *
     * [MediaProgressEntity.updatedAt] is the *local* side of the freshness comparison and
     * [MediaProgressEntity.hasUnsyncedChanges] is what short-circuits it, so both have to be set
     * deliberately rather than left to whatever `recordPosition` produces.
     */
    private suspend fun syncedProgress(position: Duration, at: Long) {
        repository.recordPosition(BOOK, position = position, duration = 2.hours)
        database.progressDao().upsertProgress(
            listOf(storedProgress().copy(updatedAt = at, hasUnsyncedChanges = false)),
        )
    }

    private suspend fun storedProgress(): MediaProgressEntity =
        requireNotNull(database.progressDao().findProgress(profileId.value, EntityKey.of(SERVER, BOOK.value))) {
            "no progress row was written"
        }

    /**
     * PRODUCT_SPEC SYNC-002 — the fixture gateway, with `serverProgress` scripted and counted.
     *
     * Delegation rather than a hand-written stub: every other call goes to [FakeAudiobookshelfGateway], so
     * this file's other twenty-odd cases are unaffected and cannot start passing for a new reason.
     * [requests] exists because the defect being fixed was a cost, not a wrong answer — a version that
     * returned the right outcome after four round trips would pass every assertion above it.
     */
    private class ScriptedProgressGateway(private val delegate: AudiobookshelfGateway) :
        AudiobookshelfGateway by delegate,
        PlaybackApi by delegate.playback {
        var answer: AppResult<ServerProgress> = AppResult.Failure(AppError.Network(summary = "not scripted"))
        var requests: Int = 0
            private set

        override val playback: PlaybackApi get() = this

        override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> {
            requests += 1
            return answer
        }
    }

    /** The active profile, without a database of profiles behind it. */
    // ------------------------------------------- PRODUCT_SPEC 6.5, whose row a write lands on

    /**
     * **The write goes where the listener was, not where the app is now.**
     *
     * This is the profile-switch race in one test. The journal writes every five seconds and a switch takes
     * microseconds, so a tick can begin under one account and reach Room under another. Before the owner
     * existed the position resolved `activeProfileId()` *here*, at the far end of that gap, and a book
     * Ada was listening to wrote its position onto Grace's row — a stranger's progress bar moving on its
     * own, which is product priority 4 rather than a cosmetic defect.
     *
     * The switch is performed between the read and the write to make the gap the thing under test.
     */
    @Test
    fun `a position named for the outgoing profile lands on that profile after a switch`() = runTest {
        seedProfile(OTHER_PROFILE)
        profiles.setActiveProfile(OTHER_PROFILE)

        repository.recordPosition(BOOK, position = 42.minutes, duration = 2.hours, owner = profileId)

        val theirs = database.progressDao().findProgress(profileId.value, EntityKey.of(SERVER, BOOK.value))
        assertEquals(42.minutes.inWholeMilliseconds, theirs?.positionMillis, "the listener's own row moved")
        val strangers = database.progressDao().findProgress(OTHER_PROFILE.value, EntityKey.of(SERVER, BOOK.value))
        assertNull(strangers, "the account switched *to* must not gain a row it never listened to")
    }

    /**
     * A caller with no session to name still gets the old behaviour.
     *
     * `owner` is nullable because some caller may genuinely have no book loaded to ask, and the fallback has
     * to be a position stored somewhere rather than a position dropped — product priority 2 outranks the
     * precision here.
     */
    @Test
    fun `a position with no owner follows the active profile`() = runTest {
        repository.recordPosition(BOOK, position = 9.minutes, duration = 2.hours)

        val stored = database.progressDao().findProgress(profileId.value, EntityKey.of(SERVER, BOOK.value))
        assertEquals(9.minutes.inWholeMilliseconds, stored?.positionMillis)
    }

    /**
     * PRODUCT_SPEC DL-005 / 6.5 — a departed account's listening does not spend the arriving account's data.
     *
     * `SmartDownloadUseCase` resolves the **active** profile to decide what to fetch, so a late journal tick
     * from the outgoing listener would queue the next book in *their* series against the incoming
     * listener's storage and cellular allowance. The position is still written — that belongs to whoever
     * listened — but the download it would have triggered is not.
     */
    @Test
    fun `a position for a departed profile triggers no smart download`() = runTest {
        seedProfile(OTHER_PROFILE)
        profiles.setActiveProfile(OTHER_PROFILE)

        repository.recordPosition(BOOK, position = 42.minutes, duration = 2.hours, owner = profileId)

        assertTrue(smartDownloads.isEmpty(), "the outgoing account's listening must not download for the incoming one")
    }

    /** And the ordinary case still triggers it, so the guard above is a guard rather than a removal. */
    @Test
    fun `a position for the account in use still considers a smart download`() = runTest {
        repository.recordPosition(BOOK, position = 42.minutes, duration = 2.hours, owner = profileId)

        assertEquals(listOf(BOOK), smartDownloads)
    }

    private class StubProfileRepository(private var active: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = flowOf(null)

        override suspend fun activeProfileId(): ProfileId? = active

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active = profileId
            return AppResult.Success(Unit)
        }
    }

    /**
     * PRODUCT_SPEC DL-001 — a device with nothing downloaded, which is what every case in this file assumes.
     *
     * The offline path has its own tests. Here the point is that a repository with no local copy behaves
     * exactly as it did before downloads existed, and a stub that answers `null` is what asserts that.
     */
    private object NoDownloads : DownloadRepository {
        override fun observeAll(): Flow<List<OfflineBook>> = flowOf(emptyList())
        override fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?> = flowOf(null)
        override fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>> = flowOf(emptySet())
        override fun observeTotalBytes(): Flow<Long> = flowOf(0)
        override suspend fun freeBytes(): Long = Long.MAX_VALUE
        override suspend fun request(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
            files: List<OfflineFile>,
        ): AppResult<OfflineBook> = unsupported()

        override suspend fun updateFile(
            serverId: ServerId,
            itemId: LibraryItemId,
            file: OfflineFile,
        ): AppResult<Unit> = unsupported()

        override suspend fun markComplete(
            serverId: ServerId,
            itemId: LibraryItemId,
            coverUri: String?,
        ): AppResult<OfflineBook> = unsupported()

        override suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit> =
            unsupported()

        override suspend fun markPaused(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

        override suspend fun markQueued(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

        override suspend fun setPinned(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
            isPinned: Boolean,
        ): AppResult<Unit> = unsupported()

        override suspend fun release(
            serverId: ServerId,
            itemId: LibraryItemId,
            profileId: ProfileId,
        ): AppResult<Boolean> = unsupported()

        override suspend fun unreferenced(): AppResult<List<OfflineBook>> = unsupported()
        override suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit> = unsupported()

        private fun <T> unsupported(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this test"))
    }

    private companion object {
        const val SERVER = "fixture-server"

        /** PRODUCT_SPEC 6.5 — the account a switch moves *to*, which must not inherit the other's writes. */
        val OTHER_PROFILE = ProfileId("other-profile")

        /** A book the fixture library actually holds, so the row has something to attach to. */
        val BOOK = LibraryItemId("book-voyage-1")

        /** The position `demo-library.json` gives [BOOK]: the "before" a remote change lands on top of. */
        val FIXTURE_POSITION = 12_480.seconds

        /** A book in the same library that the fixture gives no progress at all. */
        val UNPLAYED = LibraryItemId("book-voyage-2")

        /**
         * Later than every timestamp in the fixture.
         *
         * `writeProgress` refuses a position older than the one it holds, which is the rule that stops a
         * stale read rewinding a book — so a test about *newer* server data has to actually be newer.
         */
        val LATER: Instant = Instant.ofEpochMilli(1_800_000_000_000)
    }
}
