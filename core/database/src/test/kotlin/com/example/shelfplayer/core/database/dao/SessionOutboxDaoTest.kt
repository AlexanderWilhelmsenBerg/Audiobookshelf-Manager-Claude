package com.example.shelfplayer.core.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackSessionEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.database.entity.SessionOutboxState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — the outbox's SQL, against a real database.
 *
 * Every rule this table is supposed to enforce is a query rather than a comment, so these are the tests that
 * decide whether progress can be lost: a compaction predicate that matched the wrong column would silently
 * delete listening that never reached the server, and no amount of higher-level testing would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOutboxDaoTest {

    private lateinit var database: ShelfPlayerDatabase
    private val outbox get() = database.sessionOutboxDao()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedProfiles()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** PRODUCT_SPEC 5.2 — one account's listening never reaches another's queue, because the SQL filters. */
    @Test
    fun `the queue is scoped to its profile`() = runTest {
        outbox.upsert(row("ours", profileId = PROFILE_ID))
        outbox.upsert(row("theirs", profileId = OTHER_PROFILE_ID))

        val queued = outbox.pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10)

        assertEquals(listOf("ours"), queued.map { it.sessionId })
    }

    /**
     * Oldest first, because the server resolves conflicts on `updatedAt`.
     *
     * A newest-first batch would have the server apply the newest position and then decline every older one in
     * the same request — correct, but recorded as a run of declines rather than one apply.
     */
    @Test
    fun `the queue is ordered oldest first`() = runTest {
        outbox.upsert(row("late", updatedAt = 5_000))
        outbox.upsert(row("early", updatedAt = 1_000))
        outbox.upsert(row("middle", updatedAt = 3_000))

        val queued = outbox.pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10)

        assertEquals(listOf("early", "middle", "late"), queued.map { it.sessionId })
    }

    /**
     * An open session is in the queue.
     *
     * A session interrupted by a process death is left `Open` with nobody coming back to it, and a queue that
     * excluded open rows is how it would sit in the table forever.
     */
    @Test
    fun `an open session is queued and a synced one is not`() = runTest {
        outbox.upsert(row("open", state = SessionOutboxState.OPEN))
        outbox.upsert(row("pending", state = SessionOutboxState.PENDING))
        outbox.upsert(row("synced", state = SessionOutboxState.SYNCED, syncedAt = 9_000))

        val queued = outbox.pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10).map { it.sessionId }

        assertEquals(setOf("open", "pending"), queued.toSet())
    }

    @Test
    fun `closing open sessions leaves other profiles alone`() = runTest {
        outbox.upsert(row("ours", profileId = PROFILE_ID, state = SessionOutboxState.OPEN))
        outbox.upsert(row("theirs", profileId = OTHER_PROFILE_ID, state = SessionOutboxState.OPEN))

        val closed = outbox.closeOpen(PROFILE_ID, SessionOutboxState.OPEN, SessionOutboxState.PENDING)

        assertEquals(1, closed)
        assertEquals(SessionOutboxState.PENDING, assertNotNull(outbox.find("ours")).state)
        assertEquals(SessionOutboxState.OPEN, assertNotNull(outbox.find("theirs")).state)
    }

    /**
     * PRODUCT_SPEC PLAY-004 — an accepted session whose progress the server declined is *done*.
     *
     * `wasProgressApplied = false` is the conflict rule working: the server held something newer. It is
     * recorded and the row leaves the queue, because retrying it would be the app arguing with a rule it is
     * required to respect.
     */
    @Test
    fun `a session accepted with its progress declined leaves the queue`() = runTest {
        outbox.upsert(row("declined"))

        outbox.markSynced("declined", SessionOutboxState.SYNCED, syncedAt = 7_000, wasProgressApplied = false)

        val stored = assertNotNull(outbox.find("declined"))
        assertEquals(SessionOutboxState.SYNCED, stored.state)
        assertEquals(false, stored.wasProgressApplied)
        assertEquals(emptyList(), outbox.pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10))
        assertEquals(1, outbox.observeProgressDeclined(PROFILE_ID).first())
    }

    /** A successful sync clears the last failure, or a stale error code would outlive the problem. */
    @Test
    fun `marking synced clears the recorded error`() = runTest {
        outbox.upsert(row("retried"))
        outbox.markAttempted(listOf("retried"), "network")

        outbox.markSynced("retried", SessionOutboxState.SYNCED, syncedAt = 7_000, wasProgressApplied = true)

        assertNull(assertNotNull(outbox.find("retried")).lastErrorCode)
    }

    /**
     * A failed attempt increments and stays.
     *
     * There is no attempt ceiling on purpose (product priority 2): an outbox that gave up would discard the
     * listening in the row. The count climbing is the honest report of a server that keeps refusing.
     */
    @Test
    fun `a failed attempt is counted and the row stays queued`() = runTest {
        outbox.upsert(row("stuck"))

        outbox.markAttempted(listOf("stuck"), "timeout")
        outbox.markAttempted(listOf("stuck"), "timeout")

        val stored = assertNotNull(outbox.find("stuck"))
        assertEquals(2, stored.attempts)
        assertEquals("timeout", stored.lastErrorCode)
        assertEquals(
            listOf("stuck"),
            outbox.pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10).map {
                it.sessionId
            },
        )
    }

    /**
     * PRODUCT_SPEC PLAY-005 — compaction removes uploaded rows past the retention and nothing else.
     *
     * The queued row in this test is *older* than the cutoff by `updatedAt`, which is exactly the row a
     * compaction written against the wrong column would delete — and it is the one row in the table whose
     * listening has never reached the server.
     */
    @Test
    fun `compaction removes only uploaded rows past the retention`() = runTest {
        outbox.upsert(row("ancient-and-queued", updatedAt = 1_000))
        outbox.upsert(row("old-and-synced", state = SessionOutboxState.SYNCED, updatedAt = 1_000, syncedAt = 1_000))
        outbox.upsert(row("recently-synced", state = SessionOutboxState.SYNCED, updatedAt = 9_000, syncedAt = 9_000))

        val removed = outbox.compact(SessionOutboxState.SYNCED, before = 5_000)

        assertEquals(1, removed)
        assertNotNull(outbox.find("ancient-and-queued"))
        assertNull(outbox.find("old-and-synced"))
        assertNotNull(outbox.find("recently-synced"))
    }

    @Test
    fun `the last synced row is the most recently accepted one`() = runTest {
        outbox.upsert(row("first", state = SessionOutboxState.SYNCED, syncedAt = 1_000))
        outbox.upsert(row("second", state = SessionOutboxState.SYNCED, syncedAt = 4_000))

        assertEquals("second", assertNotNull(outbox.observeLastSynced(PROFILE_ID).first()).sessionId)
    }

    /**
     * The reported failure is the newest *queued* one.
     *
     * The question a diagnostics screen answers is "is the thing I just listened to stuck", not "which row has
     * failed most often since install" — so a row that has since been accepted must not be reported.
     */
    @Test
    fun `the last failure ignores rows that have since been accepted`() = runTest {
        outbox.upsert(row("recovered", updatedAt = 9_000))
        outbox.upsert(row("still-stuck", updatedAt = 4_000))
        outbox.markAttempted(listOf("recovered", "still-stuck"), "network")
        outbox.markSynced("recovered", SessionOutboxState.SYNCED, syncedAt = 9_500, wasProgressApplied = true)

        val failure = assertNotNull(outbox.observeLastFailure(PROFILE_ID, SessionOutboxState.SYNCED).first())

        assertEquals("still-stuck", failure.sessionId)
    }

    private suspend fun seedProfiles() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER_ID,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = null,
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        listOf(PROFILE_ID, OTHER_PROFILE_ID).forEach { profileId ->
            database.profileDao().upsertProfile(
                ProfileEntity(
                    profileId = profileId,
                    serverId = SERVER_ID,
                    remoteUserId = null,
                    username = profileId,
                    displayName = profileId,
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
    }

    private fun row(
        sessionId: String,
        profileId: String = PROFILE_ID,
        state: String = SessionOutboxState.PENDING,
        updatedAt: Long = 2_000,
        syncedAt: Long? = null,
    ) = PlaybackSessionEntity(
        sessionId = sessionId,
        profileId = profileId,
        serverId = SERVER_ID,
        bookKey = BOOK_KEY,
        remoteBookId = "book-1",
        remoteSessionId = "remote-1",
        title = "The Salt Harbour",
        author = "Marisol Holt",
        state = state,
        positionMillis = 61_000,
        durationMillis = 3_600_000,
        timeListenedMillis = 61_000,
        startedAt = 1_000,
        updatedAt = updatedAt,
        syncedAt = syncedAt,
        wasProgressApplied = null,
        attempts = 0,
        lastErrorCode = null,
    )

    private companion object {
        const val SERVER_ID = "server-1"
        const val PROFILE_ID = "profile-1"
        const val OTHER_PROFILE_ID = "profile-2"
        val BOOK_KEY = EntityKey.of(SERVER_ID, "book-1")
    }
}
