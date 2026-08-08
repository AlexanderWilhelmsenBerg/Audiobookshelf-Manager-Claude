package com.example.shelfplayer.data.settings

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — the sleep timer's settings and its history, against real storage.
 *
 * A real DataStore file and a real Room database rather than fakes: the two properties worth testing
 * here are what the store does with an unset value and what the database does with a deleted profile,
 * and a fake would only reproduce whatever this file already believed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultSleepTimerRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultSleepTimerRepository
    private lateinit var settings: AppSettingsDataSource
    private lateinit var storeFile: File

    /**
     * The DataStore's own scope, which must outlive the `runTest` that built it.
     *
     * `runTest`'s scope closes when the test function returns, and `setUp` is a different function from
     * every `@Test` — a store scoped to it is already closed by the time anything reads it, which
     * surfaces as `ClosedSendChannelException` rather than as anything that names the cause.
     */
    private lateinit var storeScope: CoroutineScope
    private val sink = RecordingLogSink()
    private val clock = TestAppClock()
    private val profileId = ProfileId("profile-1")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File(context.cacheDir, "sleep-timer-test.pb").also(File::delete)
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
        repository = DefaultSleepTimerRepository(
            settings = settings,
            sleepTimerDao = database.sleepTimerDao(),
            profileDao = database.profileDao(),
            clock = clock,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
        settings.setActiveProfile(profileId)
    }

    private suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
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
                serverId = SERVER,
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
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        storeScope.cancel()
        storeFile.delete()
    }

    // --- Settings ----------------------------------------------------------------------------------

    /**
     * PRODUCT_SPEC PLAY-008 — "requires explicit opt-in".
     *
     * The default has to be off, and it has to be off for a store that has never been written rather
     * than only for one that was written with `false`.
     */
    @Test
    fun `shake to restart is off until it is turned on`() = runTest {
        assertFalse(repository.observeSettings().first().shakeToRestart)

        repository.setShakeToRestart(true)

        assertTrue(repository.observeSettings().first().shakeToRestart)
    }

    /** An unwritten store reads as the product defaults, not as zero minutes and no fade. */
    @Test
    fun `an unset store reads as the product defaults`() = runTest {
        assertEquals(SleepTimerSettings.Default, repository.observeSettings().first())
    }

    /**
     * PLAY-008 — "fade-out occurs over 5–30 seconds", and a value outside it is clamped rather than
     * stored.
     *
     * A fade longer than some timers, or one of zero, is not a state the UI offers; clamping is what
     * stops a value written by an older build or a corrupted file producing one.
     */
    @Test
    fun `durations outside the documented ranges are clamped`() = runTest {
        repository.setFadeLength(5.minutes)
        repository.setDefaultLength(1.seconds)

        val stored = repository.observeSettings().first()
        assertEquals(SleepTimerSettings.FadeRange.endInclusive, stored.fadeLength)
        assertEquals(SleepTimerSettings.LengthRange.start, stored.defaultLength)
    }

    // --- History -----------------------------------------------------------------------------------

    @Test
    fun `a started timer is recorded as running`() = runTest {
        val started = repository.recordStarted(BOOK, SleepTimerMode.Fixed(30.minutes))

        assertIs<AppResult.Success<String>>(started)
        val session = repository.observeRecentSessions().first().single()
        assertEquals(SleepTimerMode.Fixed(30.minutes), session.mode)
        assertTrue(session.isRunning)
        assertNull(session.outcome)
        assertEquals(BOOK, session.bookId)
    }

    @Test
    fun `ending a timer records when and why`() = runTest {
        val id = startedId(SleepTimerMode.EndOfChapter)
        clock.advanceBy(24.minutes)

        repository.recordEnded(id, SleepTimerOutcome.Expired)

        val session = repository.observeRecentSessions().first().single()
        assertFalse(session.isRunning)
        assertEquals(SleepTimerOutcome.Expired, session.outcome)
        assertEquals(
            24.minutes.inWholeMilliseconds,
            session.endedAt!!.toEpochMilli() - session.startedAt.toEpochMilli(),
        )
    }

    /**
     * The first answer is the true one.
     *
     * Expiry and teardown can both fire for one timer — the fade completes, and the service then stops
     * — and "it expired" says more than "the service went away afterwards".
     */
    @Test
    fun `a timer that already ended is not re-ended`() = runTest {
        val id = startedId(SleepTimerMode.Fixed(15.minutes))
        repository.recordEnded(id, SleepTimerOutcome.Expired)

        repository.recordEnded(id, SleepTimerOutcome.PlaybackStopped)

        assertEquals(SleepTimerOutcome.Expired, repository.observeRecentSessions().first().single().outcome)
    }

    /** A restart is counted on the running session rather than opening a second one. */
    @Test
    fun `restarts are counted on the session that was running`() = runTest {
        val id = startedId(SleepTimerMode.Fixed(15.minutes))

        repository.recordRestarted(id)
        repository.recordRestarted(id)

        val sessions = repository.observeRecentSessions().first()
        assertEquals(1, sessions.size, "one session, not three")
        assertEquals(2, sessions.single().restarts)
    }

    /**
     * PRODUCT_SPEC PLAY-008 — a timer running when the process died is closed rather than left open.
     *
     * Otherwise every later read shows a timer that is not running, and the history stops meaning
     * anything.
     */
    @Test
    fun `a timer left running by a dead process is closed as abandoned`() = runTest {
        startedId(SleepTimerMode.Fixed(15.minutes))

        val closed = repository.closeOrphanedSessions()

        assertEquals(1, assertIs<AppResult.Success<Int>>(closed).value)
        assertEquals(
            SleepTimerOutcome.Abandoned,
            repository.observeRecentSessions().first().single().outcome,
        )
    }

    @Test
    fun `closing orphans leaves already-ended timers alone`() = runTest {
        val id = startedId(SleepTimerMode.Fixed(15.minutes))
        repository.recordEnded(id, SleepTimerOutcome.Cancelled)

        repository.closeOrphanedSessions()

        assertEquals(
            SleepTimerOutcome.Cancelled,
            repository.observeRecentSessions().first().single().outcome,
        )
    }

    /** PRODUCT_SPEC 5.2 — with nobody signed in there is no history to read, and that is not an error. */
    @Test
    fun `no active profile reads as an empty history`() = runTest {
        startedId(SleepTimerMode.Fixed(15.minutes))
        settings.clearActiveProfile()

        assertEquals(emptyList(), repository.observeRecentSessions().first())
    }

    /** PRODUCT_SPEC 14.5 — the log line names the mode and the outcome, and never the book. */
    @Test
    fun `starting and ending a timer logs no book`() = runTest {
        val id = startedId(SleepTimerMode.Fixed(15.minutes))
        repository.recordEnded(id, SleepTimerOutcome.Expired)

        assertFalse(sink.text.contains(BOOK.value), "no book id: ${sink.text}")
        assertTrue(sink.text.contains("sleep timer"), "the events are logged: ${sink.text}")
    }

    private suspend fun startedId(mode: SleepTimerMode): String =
        assertIs<AppResult.Success<String>>(repository.recordStarted(BOOK, mode)).value

    private companion object {
        const val SERVER = "server-1"
        val BOOK = LibraryItemId("book-1")
    }
}
