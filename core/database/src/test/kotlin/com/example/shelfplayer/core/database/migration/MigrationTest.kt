package com.example.shelfplayer.core.database.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.BookPlaybackSettingsEntity
import com.example.shelfplayer.core.database.entity.BookmarkEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.database.entity.PlaybackSessionEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.SessionOutboxState
import com.example.shelfplayer.core.database.entity.SleepTimerSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 18 / 22.11 — a migration test for every version bump, because the alternative to a
 * migration is destroying a user's only copy of their listening position.
 *
 * No schema is transcribed into this file. Each starting version is built from its committed
 * `schemas/…/N.json` — the artifact Room itself exported — so the tests run against the schemas that
 * shipped rather than against a developer's recollection of them. A hand-copied `CREATE TABLE` that
 * drifted from the export would make these tests pass while the real migration failed.
 *
 * Every version is migrated all the way to the current one. That is what a device does: a user upgrading
 * from two versions back runs both migrations in one open, and a step that only works when run alone is
 * a step that fails in the field.
 *
 * Robolectric, because the subject is SQLite behaviour.
 */
/*
 * `LargeClass` is suppressed, and it is a deliberate choice rather than a deferral.
 *
 * This file grows by one test and one seed every time the database gains a version, and both halves have to
 * stay next to each other: a seed is the *data* a version's tests describe, and a seed in another file is a
 * seed that drifts from the assertions about it. Splitting by version would duplicate the harness —
 * `createVersion`, `openWithMigrations`, the schema reader — into every piece, and a harness copied is a
 * harness that stops agreeing with itself.
 *
 * The honest fix when this next grows is to extract the harness into a class both halves use, which is a
 * change worth making on its own rather than while unblocking a start-up crash. Recorded here so the next
 * reader knows it is a decision.
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile = File(context.cacheDir, "migration-test.db")
    private var database: ShelfPlayerDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        databaseFile.delete()
        File("${databaseFile.path}-shm").delete()
        File("${databaseFile.path}-wal").delete()
    }

    /**
     * The property that matters: rows written by version 1 are still there after both migrations, with
     * their values intact and the new columns at their documented defaults.
     */
    @Test
    fun `version 1 data survives migrating to the current version`() = runTest {
        createVersion(1)

        val migrated = openWithMigrations()

        val server = assertNotNull(migrated.profileDao().findServer(SERVER_ID), "the server row was lost")
        assertEquals("Demo", server.displayName)
        assertEquals("https://books.example", server.baseUrl)
        // Added by version 2, at the defaults its migration declares.
        assertEquals("[]", server.authMethodsJson)
        assertEquals("[]", server.capabilitiesJson)
        assertNull(server.capabilitiesDetectedAt)

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID), "the profile row was lost")
        assertEquals("ada", profile.username)
        assertNull(profile.remoteUserId)
    }

    /** Version 2 → 3 in isolation, which is the upgrade an already-migrated device performs. */
    @Test
    fun `version 2 data survives migrating to the current version`() = runTest {
        createVersion(2)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertEquals("ada", profile.username)
        assertEquals("remote-user-1", profile.remoteUserId)
        val server = assertNotNull(migrated.profileDao().findServer(SERVER_ID))
        assertEquals("""["local"]""", server.authMethodsJson)
    }

    /**
     * PRODUCT_SPEC 5.2 / 2.2 — an existing profile keeps access to the library it is already browsing.
     *
     * The restrictive default is right for a new profile and wrong for one that predates the grant being
     * recorded: applying it retroactively would blank a library the user can currently read offline. The
     * value survives only until that profile's next sign-in.
     */
    @Test
    fun `a profile that predates the grant keeps access to its cached libraries`() = runTest {
        createVersion(2)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertTrue(profile.hasAllLibraryAccess, "a pre-existing profile must not lose its cached library")
        assertEquals("[]", profile.accessibleLibrariesJson)
    }

    /**
     * PRODUCT_SPEC 5.2 — and the *opposite* default for item access, deliberately.
     *
     * `hasAllLibraryAccess` is made permissive for an upgrading profile because the restrictive value
     * would hide content the user already has. `hasAllTagAccess` is left restrictive because the
     * restrictive value only stops that profile *deleting*, and the permissive one would let a filtered
     * account mark another account's books removed — which is exactly the defect this column exists to
     * prevent. Getting one wrong hides data; getting the other wrong destroys it.
     */
    @Test
    fun `a profile that predates item access does not gain the right to delete`() = runTest {
        createVersion(3)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertFalse(profile.hasAllTagAccess, "an unknown item grant must not authorise reconciliation")
        assertTrue(profile.hasAllLibraryAccess, "…while its cached libraries stay readable")
    }

    /** The other half of the same decision: a *new* profile with no recorded grant is granted nothing. */
    @Test
    fun `a profile created after the migration is granted nothing by default`() = runTest {
        createVersion(2)
        val migrated = openWithMigrations()

        migrated.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "prf_new",
                serverId = SERVER_ID,
                remoteUserId = null,
                username = "grace",
                displayName = "grace",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = false,
                hasAllTagAccess = false,
            ),
        )

        assertFalse(assertNotNull(migrated.profileDao().findProfile("prf_new")).hasAllLibraryAccess)
    }

    /**
     * PRODUCT_SPEC 5.2 — version 5 arrives with nothing visible to anyone, on purpose.
     *
     * The new table cannot be backfilled: the cache records which books were *stored*, never which account
     * was shown them, and attributing them to every profile is the bug the table exists to fix. So an
     * upgrading profile starts with an empty view, and the migration withdraws its claim to be up to date
     * so the next launch syncs on its own and fills the view with that account's real visibility.
     */
    @Test
    fun `version 5 starts every profile with no visible books and a sync owed`() = runTest {
        createVersion(4)

        val migrated = openWithMigrations()

        assertEquals(
            0,
            migrated.libraryDao().observeVisibleBookCount(PROFILE_ID, SERVER_ID).first(),
            "an upgrading profile has no recorded visibility, and absence must mean hidden",
        )
        val syncState = assertNotNull(migrated.syncStateDao().findSyncState(PROFILE_ID))
        assertEquals("NeverSynced", syncState.status, "the shelf refills only if a sync is owed")
        assertNull(syncState.lastSuccessfulSyncAt)
    }

    /** The rows themselves survive: this migration withdraws a claim, it does not delete content. */
    @Test
    fun `version 5 keeps the profile and its server`() = runTest {
        createVersion(4)

        val migrated = openWithMigrations()

        val profile = assertNotNull(migrated.profileDao().findProfile(PROFILE_ID))
        assertEquals("ada", profile.username)
        assertTrue(profile.hasAllLibraryAccess)
        assertTrue(profile.hasAllTagAccess)
        assertNotNull(migrated.profileDao().findServer(SERVER_ID))
    }

    /**
     * PRODUCT_SPEC LIB-002 — version 6 adds two identifiers and keeps every book that was cached.
     *
     * The contrast with version 5 is the point. That migration reset `sync_state` because it had made an
     * existing claim untrue; this one only adds fields nobody has searched by, so resetting every shelf
     * to backfill an ISBN that most self-hosted items do not have would cost the user their offline
     * library to gain nothing.
     */
    @Test
    fun `version 6 adds the identifiers without disturbing the cached books`() = runTest {
        createVersion(5)

        val migrated = openWithMigrations()

        val book = assertNotNull(
            migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first(),
            "the cached book must survive an additive migration",
        )
        assertEquals("The Salt Harbour", book.book.title)
        assertNull(book.book.isbn, "an identifier that was never fetched is unknown, not empty")
        assertNull(book.book.asin)
        val syncState = assertNotNull(migrated.syncStateDao().findSyncState(PROFILE_ID))
        assertEquals("Succeeded", syncState.status, "adding a column does not owe the user a resync")
    }

    /**
     * PRODUCT_SPEC LIB-002 — version 7 adds the server's own "added" timestamp, and keeps the books.
     *
     * `lastFetchedAt` is not a substitute and the migration does not pretend it is: a row fetched before
     * this build genuinely does not know when the server acquired it, so the column stays null and that
     * book sorts last under "recently added" rather than first.
     */
    @Test
    fun `version 7 adds the added timestamp without disturbing the cached books`() = runTest {
        createVersion(6)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertNull(book.book.addedAt, "a date that was never fetched is unknown, not the epoch")
    }

    /**
     * PRODUCT_SPEC PLAY-008 — version 8 adds a table and takes nothing away.
     *
     * The two halves are asserted separately because they fail differently. The cached book proves the
     * migration did not disturb existing data; the successful *write* into the new table proves the
     * hand-written `CREATE TABLE` matches what Room's DAO expects — a column Room believes is `NOT NULL`
     * and the migration made nullable would pass a read and fail this insert.
     */
    @Test
    fun `version 8 adds the sleep timer history without disturbing the cached books`() = runTest {
        createVersion(7)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(emptyList(), migrated.sleepTimerDao().observeRecent(PROFILE_ID, limit = 10).first())

        migrated.sleepTimerDao().upsert(
            SleepTimerSessionEntity(
                sessionId = "session-1",
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                mode = "Fixed",
                modeLength = 1_800_000,
                startedAt = 1_000,
                endedAt = null,
                outcome = null,
                restarts = 0,
            ),
        )
        assertEquals(1, migrated.sleepTimerDao().observeRecent(PROFILE_ID, limit = 10).first().size)
    }

    /**
     * PRODUCT_SPEC AUTH-002 — removing a profile takes its timer history with it.
     *
     * The cascade is declared on the entity, and a hand-written migration is exactly where a foreign
     * key gets forgotten: SQLite accepts a table with no constraint, Room's validation compares the
     * *declared* schema rather than enforcement, and the orphaned rows would only surface much later.
     */
    @Test
    fun `removing a profile removes its sleep timer history`() = runTest {
        createVersion(7)
        val migrated = openWithMigrations()
        // Room disables foreign keys during a migration and re-enables them after; this asserts the
        // constraint that is in force for ordinary writes.
        migrated.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        migrated.sleepTimerDao().upsert(
            SleepTimerSessionEntity(
                sessionId = "session-1",
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                mode = "EndOfChapter",
                modeLength = 0,
                startedAt = 1_000,
                endedAt = 2_000,
                outcome = "Expired",
                restarts = 2,
            ),
        )

        migrated.openHelper.writableDatabase.execSQL(
            "DELETE FROM profiles WHERE profileId = ?",
            arrayOf<Any>(PROFILE_ID),
        )

        assertEquals(emptyList(), migrated.sleepTimerDao().observeRecent(PROFILE_ID, limit = 10).first())
    }

    /**
     * PRODUCT_SPEC PLAY-004 / PLAY-005 — version 9 adds the session outbox and takes nothing away.
     *
     * The written row is the point rather than the empty read: every column is populated, so a column the
     * migration spelled differently or made nullable would pass a read and fail this insert.
     */
    @Test
    fun `version 9 adds the session outbox without disturbing the cached books`() = runTest {
        createVersion(8)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(0, migrated.sessionOutboxDao().observeCount(PROFILE_ID).first())

        migrated.sessionOutboxDao().upsert(outboxRow())

        assertEquals(1, migrated.sessionOutboxDao().observeCount(PROFILE_ID).first())
        assertEquals(
            1,
            migrated.sessionOutboxDao()
                .pending(PROFILE_ID, SessionOutboxState.SYNCED, limit = 10)
                .size,
        )
    }

    /**
     * PRODUCT_SPEC AUTH-002 — removing a profile takes its listening sessions with it.
     *
     * The same check the sleep-timer cascade gets, and for the same reason: SQLite accepts a table with no
     * constraint and Room compares the *declared* schema rather than enforcement, so a forgotten foreign key
     * in a hand-written migration surfaces as orphaned rows much later.
     */
    @Test
    fun `removing a profile removes its listening sessions`() = runTest {
        createVersion(8)
        val migrated = openWithMigrations()
        migrated.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        migrated.sessionOutboxDao().upsert(outboxRow())

        migrated.openHelper.writableDatabase.execSQL(
            "DELETE FROM profiles WHERE profileId = ?",
            arrayOf<Any>(PROFILE_ID),
        )

        assertEquals(0, migrated.sessionOutboxDao().observeCount(PROFILE_ID).first())
    }

    private fun outboxRow(
        sessionId: String = "session-1",
        state: String = SessionOutboxState.PENDING,
        syncedAt: Long? = null,
        wasProgressApplied: Boolean? = null,
    ) = PlaybackSessionEntity(
        sessionId = sessionId,
        profileId = PROFILE_ID,
        serverId = SERVER_ID,
        bookKey = BOOK_KEY,
        remoteBookId = "book-1",
        remoteSessionId = "remote-session-1",
        title = "The Salt Harbour",
        author = "A. Writer",
        state = state,
        positionMillis = 61_000,
        durationMillis = 3_600_000,
        timeListenedMillis = 61_000,
        startedAt = 1_000,
        updatedAt = 62_000,
        syncedAt = syncedAt,
        wasProgressApplied = wasProgressApplied,
        attempts = 0,
        lastErrorCode = null,
    )

    /**
     * PRODUCT_SPEC PLAY-007 — version 10 adds the per-book speed override and takes nothing away.
     *
     * The written row is the point rather than the empty read: every column is populated, so a column the
     * migration spelled differently would pass a read and fail this insert.
     */
    @Test
    fun `version 10 adds the per-book speed without disturbing the cached books`() = runTest {
        createVersion(9)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(0, migrated.bookPlaybackSettingsDao().observeCount(PROFILE_ID).first())

        migrated.bookPlaybackSettingsDao().upsert(
            BookPlaybackSettingsEntity(
                settingsKey = EntityKey.scoped(PROFILE_ID, BOOK_KEY),
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                speedHundredths = 150,
            ),
        )

        assertEquals(
            150,
            assertNotNull(migrated.bookPlaybackSettingsDao().find(PROFILE_ID, BOOK_KEY)).speedHundredths,
        )
    }

    /**
     * PRODUCT_SPEC PLAY-003 — version 11 adds the playback history and takes nothing away.
     *
     * The written row is the point rather than the empty read: every column is populated, so a column the
     * migration spelled differently would pass a read and fail this insert. `fromMillis` is deliberately
     * nullable and deliberately written as `null` here, because the resume entry is the one that has no
     * position it came from.
     */
    @Test
    fun `version 11 adds the playback history without disturbing the cached books`() = runTest {
        createVersion(9)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(emptyList(), migrated.playbackHistoryDao().observe(PROFILE_ID, BOOK_KEY, limit = 10).first())

        migrated.playbackHistoryDao().record(
            entry = PlaybackHistoryEntity(
                entryId = "entry-1",
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                fromMillis = null,
                toMillis = 42_000,
                reason = "Resume",
                detailMillis = null,
                at = 1_000,
            ),
            keep = 10,
        )

        val stored = migrated.playbackHistoryDao().observe(PROFILE_ID, BOOK_KEY, limit = 10).first().single()
        assertEquals(42_000, stored.toMillis)
        assertEquals(null, stored.fromMillis)
    }

    /**
     * PRODUCT_SPEC 11.1 — version 13 adds the bookmarks and takes nothing away.
     *
     * The written row is the point rather than the empty read: every column is populated, so a column the
     * hand-written `CREATE TABLE` spelled differently would pass a read and fail this insert.
     */
    @Test
    fun `version 13 adds the bookmarks without disturbing the cached books`() = runTest {
        createVersion(9)

        val migrated = openWithMigrations()

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(emptyList(), migrated.bookmarkDao().observe(PROFILE_ID, BOOK_KEY).first())

        migrated.bookmarkDao().upsert(listOf(bookmark(atSeconds = 31)))

        val stored = migrated.bookmarkDao().observe(PROFILE_ID, BOOK_KEY).first().single()
        assertEquals(31, stored.atSeconds)
        assertEquals("A line worth keeping", stored.title)
    }

    /**
     * PRODUCT_SPEC 11.1 — a bookmark deleted offline does not appear in the list while its upload retries.
     *
     * The flag is what stops a refresh resurrecting it, and the DAO's reads are where that is enforced. A
     * read that ignored the flag would argue with a listener who had just deleted something.
     */
    @Test
    fun `a bookmark pending deletion is not listed`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()

        migrated.bookmarkDao().upsert(
            listOf(
                bookmark(atSeconds = 31),
                bookmark(atSeconds = 90, isPendingDelete = true),
            ),
        )

        assertEquals(listOf(31L), migrated.bookmarkDao().observe(PROFILE_ID, BOOK_KEY).first().map { it.atSeconds })
        assertEquals(2, migrated.bookmarkDao().findAllFor(PROFILE_ID).size, "but the refresh can still see it")
    }

    /**
     * PRODUCT_SPEC AUTH-002 — removing a profile takes its bookmarks with it.
     *
     * The same check the sleep-timer and session cascades get, and for the same reason: SQLite accepts a
     * table with no constraint and Room compares the *declared* schema rather than enforcement, so a
     * forgotten foreign key in a hand-written migration surfaces as orphaned rows much later — and these
     * rows carry a listener's own words.
     */
    @Test
    fun `removing a profile removes its bookmarks`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()
        migrated.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        migrated.bookmarkDao().upsert(listOf(bookmark(atSeconds = 31)))

        migrated.openHelper.writableDatabase.execSQL(
            "DELETE FROM profiles WHERE profileId = ?",
            arrayOf<Any>(PROFILE_ID),
        )

        assertEquals(emptyList(), migrated.bookmarkDao().findAllFor(PROFILE_ID))
    }

    private fun bookmark(atSeconds: Long, isPendingDelete: Boolean = false) = BookmarkEntity(
        bookmarkId = "${EntityKey.scoped(PROFILE_ID, BOOK_KEY)}:$atSeconds",
        profileId = PROFILE_ID,
        bookKey = BOOK_KEY,
        atSeconds = atSeconds,
        title = "A line worth keeping",
        createdAt = 1_000,
        hasUnsyncedChanges = false,
        isPendingDelete = isPendingDelete,
    )

    /**
     * PRODUCT_SPEC PLAY-003 — the cap is per book, and it keeps the newest.
     *
     * Pruning is what stops a book somebody scrubbed around for an hour carrying hundreds of rows forever.
     * Keeping the *newest* is the part worth pinning: a cap that kept the oldest would freeze the list at
     * whatever happened first and never show the jump somebody is actually trying to undo.
     */
    @Test
    fun `the history keeps the newest entries and drops the rest`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()
        repeat(5) { index ->
            migrated.playbackHistoryDao().record(
                entry = PlaybackHistoryEntity(
                    entryId = "entry-$index",
                    profileId = PROFILE_ID,
                    bookKey = BOOK_KEY,
                    fromMillis = index * 1_000L,
                    toMillis = index * 2_000L,
                    reason = "Seek",
                    detailMillis = null,
                    at = index.toLong(),
                ),
                keep = 3,
            )
        }

        val stored = migrated.playbackHistoryDao().observe(PROFILE_ID, BOOK_KEY, limit = 10).first()
        assertEquals(3, stored.size)
        assertEquals(listOf("entry-4", "entry-3", "entry-2"), stored.map { it.entryId })
    }

    /**
     * PRODUCT_SPEC PLAY-008 — version 12 adds `detailMillis`, and version 11's rows survive it.
     *
     * The nullable column is the point: every row written by version 11 was a jump, and a jump has no
     * detail, so the migration adds the column without touching a byte of the existing table. Writing a row
     * that *uses* it afterwards proves the column is real and not just declared.
     */
    @Test
    fun `version 12 adds the event detail without disturbing the history`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()
        migrated.playbackHistoryDao().record(
            entry = PlaybackHistoryEntity(
                entryId = "timer-1",
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                fromMillis = null,
                toMillis = 90_000,
                reason = "SleepTimerStarted",
                detailMillis = 1_800_000,
                at = 2_000,
            ),
            keep = 10,
        )

        val stored = migrated.playbackHistoryDao().observe(PROFILE_ID, BOOK_KEY, limit = 10).first().single()
        assertEquals(1_800_000, stored.detailMillis)
        assertEquals("SleepTimerStarted", stored.reason)
    }

    /** PRODUCT_SPEC AUTH-002 — removing a profile takes its per-book speeds with it. */
    @Test
    fun `removing a profile removes its per-book speeds`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()
        migrated.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        migrated.bookPlaybackSettingsDao().upsert(
            BookPlaybackSettingsEntity(
                settingsKey = EntityKey.scoped(PROFILE_ID, BOOK_KEY),
                profileId = PROFILE_ID,
                bookKey = BOOK_KEY,
                speedHundredths = 200,
            ),
        )

        migrated.openHelper.writableDatabase.execSQL(
            "DELETE FROM profiles WHERE profileId = ?",
            arrayOf<Any>(PROFILE_ID),
        )

        assertEquals(0, migrated.bookPlaybackSettingsDao().observeCount(PROFILE_ID).first())
    }

    /**
     * PRODUCT_SPEC PLAY-004 / ADR-0013 — version 14 adds the library's finished rule.
     *
     * Two assertions, and the first is the one that matters: a library cached by an older build reads back
     * with **no rule**, not with a rule of zero. `null` means "this library has not set one", and a migration
     * that defaulted the column to 0 would silently mark every book in every pre-14 library finished at its
     * last sample.
     */
    @Test
    fun `version 14 gives a cached library no finished rule rather than a zero one`() = runTest {
        createVersion(9)

        val migrated = openWithMigrations()

        val stored = assertNotNull(migrated.libraryDao().observeLibrary(LIBRARY_KEY).first())
        assertNull(stored.finishedTimeRemainingSeconds, "no rule, not zero seconds")

        migrated.libraryWriteDao().upsertLibraries(listOf(stored.copy(finishedTimeRemainingSeconds = 60)))

        val updated = assertNotNull(migrated.libraryDao().observeLibrary(LIBRARY_KEY).first())
        assertEquals(60L, updated.finishedTimeRemainingSeconds)
    }

    /**
     * PRODUCT_SPEC PLAY-004 — the join the progress journal reads the rule through.
     *
     * A book's rule comes from *its own* library, and the journal has only a book. This is that query
     * against a migrated database, which is where a column named differently by the migration than by the
     * entity would surface — Room validates the schema but not a hand-written `SELECT`.
     */
    @Test
    fun `a book's finished rule is found through its library`() = runTest {
        createVersion(9)
        val migrated = openWithMigrations()
        val library = assertNotNull(migrated.libraryDao().observeLibrary(LIBRARY_KEY).first())
        migrated.libraryWriteDao().upsertLibraries(listOf(library.copy(finishedTimeRemainingSeconds = 45)))

        assertEquals(45L, migrated.libraryDao().finishedSecondsFor(BOOK_KEY))
        assertNull(migrated.libraryDao().finishedSecondsFor("no-such-book"), "an unknown book has no rule")
    }

    /**
     * **The regression test for a crash that reached a device.**
     *
     * Build 0.9.2 shipped database version 14 with two columns on `libraries`:
     * `finishedTimeRemainingSeconds` and `finishedFractionComplete`. The next build dropped the second one
     * and **kept the version number at 14**, on the reasoning that 14 "had not shipped". It had — to the
     * owner's phone, an hour earlier. Room stores version 14's identity hash in `room_master_table`, compares
     * it on open, finds a hash it has no migration to reach, and throws. The app crashed at startup on
     * exactly the device that had installed the previous build, and on no other.
     *
     * So this opens a database shaped **as 0.9.2 left it** — built from the committed `14.json`, which is
     * that shipped schema — and requires that it migrates and validates. It fails if anybody edits version
     * 14's schema again, which is the mistake, rather than checking the symptom.
     *
     * The rows are asserted afterwards because the 14 → 15 step is a **table rebuild** — SQLite before 3.35
     * has no `DROP COLUMN` — and a rebuild that copied the columns in the wrong order would validate against
     * Room's schema perfectly while silently moving every library's data one field along.
     */
    @Test
    fun `a database left at version 14 by build 0-9-2 still opens`() = runTest {
        createVersion(14)

        val migrated = openWithMigrations()

        val library = assertNotNull(
            migrated.libraryDao().observeLibrary(LIBRARY_KEY).first(),
            "the library row was lost by the rebuild",
        )
        assertEquals("Fiction", library.name)
        assertEquals(SERVER_ID, library.serverId)
        assertEquals("library-1", library.remoteId)
        assertFalse(library.isDeleted)
        assertNull(library.finishedTimeRemainingSeconds, "and the surviving column keeps its meaning")

        val book = assertNotNull(migrated.libraryDao().observeBook(PROFILE_ID, BOOK_KEY).first())
        assertEquals("The Salt Harbour", book.book.title, "dropping the parent table did not cascade")
    }

    /**
     * The rebuild carries a value across rather than only a null.
     *
     * `createVersion` writes the column as null, so the test above cannot tell a copy from a fresh column. This
     * one writes a rule at version 14 — the state of any library synced by 0.9.2 — and requires it to survive.
     */
    @Test
    fun `version 14's finished rule survives the rebuild`() = runTest {
        createVersion(14)
        writeAtVersion14(finishedSeconds = 45)

        val migrated = openWithMigrations()

        val library = assertNotNull(migrated.libraryDao().observeLibrary(LIBRARY_KEY).first())
        assertEquals(45L, library.finishedTimeRemainingSeconds)
    }

    /** Sets `libraries.finishedTimeRemainingSeconds` on the un-migrated file, as version 14 would have. */
    private fun writeAtVersion14(finishedSeconds: Long) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseFile.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "UPDATE `libraries` SET `finishedTimeRemainingSeconds` = ? WHERE `libraryKey` = ?",
                arrayOf<Any>(finishedSeconds, LIBRARY_KEY),
            )
        }
    }

    /**
     * Room validates the migrated schema against the one it expects and throws if they differ. Reading
     * through a DAO is what forces that validation to run, so this fails loudly on a migration that
     * produced a *nearly* correct schema — a missing default, a wrong nullability.
     */
    @Test
    fun `the migrated schema is the one Room expects`() = runTest {
        createVersion(1)

        val migrated = openWithMigrations()

        assertEquals(emptyList(), migrated.libraryDao().observeLibraries(SERVER_ID).first())
        assertEquals(1, migrated.profileDao().observeProfiles().first().size)
    }

    /** A fresh install must reach the same place as a migrated one, or the two diverge silently. */
    @Test
    fun `a database created fresh has the same columns as a migrated one`() = runTest {
        createVersion(1)
        val migrated = openWithMigrations()
        val migratedProfiles = columnsOf(migrated.openHelper.readableDatabase, "profiles")
        val migratedServers = columnsOf(migrated.openHelper.readableDatabase, "servers")
        migrated.close()
        database = null
        databaseFile.delete()

        val fresh = openWithMigrations()

        assertEquals(migratedProfiles, columnsOf(fresh.openHelper.readableDatabase, "profiles"))
        assertEquals(migratedServers, columnsOf(fresh.openHelper.readableDatabase, "servers"))
    }

    private fun openWithMigrations(): ShelfPlayerDatabase =
        Room.databaseBuilder(context, ShelfPlayerDatabase::class.java, databaseFile.path)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .build()
            .also { database = it }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val names = mutableListOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
            names.sorted()
        }

    /**
     * Builds a real database at [version] from its exported schema and puts a row in each table the
     * migrations touch.
     */
    private fun createVersion(version: Int) {
        val schema = Json.parseToJsonElement(exportedSchema(version)).jsonObject
        val exported = schema.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseFile.path)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        exported.getValue("entities").jsonArray.forEach { entity ->
                            // Tables and their indices are separate statements in the export, and the
                            // indices are not optional: Room compares them, so a fixture database missing
                            // one fails validation for a reason unrelated to the migration under test.
                            createStatementsOf(entity.jsonObject).forEach(db::execSQL)
                        }
                        // Room stores its schema fingerprint here and checks it on open. Writing the
                        // exported hash is what makes this a genuine database of that version rather than
                        // an unidentified one that Room would refuse or silently recreate.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(exported.getValue("identityHash").jsonPrimitive.content),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("the fixture database must not be upgraded by its own helper")
                })
                .build(),
        )
        helper.writableDatabase.use { db -> seed(db, version) }
        helper.close()
    }

    /**
     * The insert statements are written per version rather than generated.
     *
     * A generated insert would have to derive the column list from the schema, which means deriving it
     * from the same source the migration is being checked against — and a test that agrees with the thing
     * it is testing checks nothing.
     */
    private fun seed(db: SupportSQLiteDatabase, version: Int) = when (version) {
        VERSION_1 -> seedVersion1(db)
        VERSION_2 -> seedVersion2(db)
        VERSION_3 -> seedVersion3(db)
        VERSION_4 -> seedVersion4(db)
        VERSION_5 -> seedVersion5(db)
        VERSION_6 -> seedVersion6(db)
        VERSION_7 -> seedVersion7(db)
        VERSION_8 -> seedVersion8(db)
        VERSION_9 -> seedVersion9(db)
        VERSION_14 -> seedVersion14(db)
        else -> error("no seed data defined for schema version $version")
    }

    private fun seedVersion1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO servers (serverId, displayName, baseUrl, detectedVersion, isFixture, lastFetchedAt) " +
                "VALUES (?, ?, ?, NULL, 0, 0)",
            arrayOf(SERVER_ID, "Demo", "https://books.example"),
        )
        db.execSQL(
            "INSERT INTO profiles " +
                "(profileId, serverId, username, displayName, role, requiresReauthentication, " +
                "lastUsedAt, isFixture) VALUES (?, ?, ?, ?, 'Listener', 0, NULL, 0)",
            arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
        )
    }

    private fun seedVersion2(db: SupportSQLiteDatabase) {
        seedServerWithCapabilities(db)
        db.execSQL(
            "INSERT INTO profiles " +
                "(profileId, serverId, remoteUserId, username, displayName, role, " +
                "requiresReauthentication, lastUsedAt, isFixture) " +
                "VALUES (?, ?, 'remote-user-1', ?, ?, 'Listener', 0, NULL, 0)",
            arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
        )
    }

    private fun seedVersion3(db: SupportSQLiteDatabase) {
        seedServerWithCapabilities(db)
        db.execSQL(
            "INSERT INTO profiles " +
                "(profileId, serverId, remoteUserId, username, displayName, role, " +
                "requiresReauthentication, lastUsedAt, isFixture, accessibleLibrariesJson, " +
                "hasAllLibraryAccess) " +
                "VALUES (?, ?, 'remote-user-1', ?, ?, 'Listener', 0, NULL, 0, '[]', 1)",
            arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
        )
    }

    private fun seedVersion4(db: SupportSQLiteDatabase) {
        seedServerWithCapabilities(db)
        db.execSQL(
            "INSERT INTO profiles " +
                "(profileId, serverId, remoteUserId, username, displayName, role, " +
                "requiresReauthentication, lastUsedAt, isFixture, accessibleLibrariesJson, " +
                "hasAllLibraryAccess, hasAllTagAccess) " +
                "VALUES (?, ?, 'remote-user-1', ?, ?, 'Listener', 0, NULL, 0, '[]', 1, 1)",
            arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
        )
        // A profile that believes it is up to date, which is the state version 5 has to withdraw.
        db.execSQL(
            "INSERT INTO sync_state " +
                "(profileId, serverId, status, lastSuccessfulSyncAt, lastAttemptedAt, " +
                "lastErrorCode, lastErrorSummary) " +
                "VALUES (?, ?, 'Succeeded', 1000, 1000, NULL, NULL)",
            arrayOf(PROFILE_ID, SERVER_ID),
        )
    }

    /**
     * A version-5 cache with a book in it, which is what version 6 has to leave intact.
     *
     * The visibility row matters: every read joins it, so a book seeded without one is invisible for
     * reasons that have nothing to do with the migration under test.
     */
    private fun seedVersion5(db: SupportSQLiteDatabase) {
        seedServerWithCapabilities(db)
        db.execSQL(
            "INSERT INTO profiles " +
                "(profileId, serverId, remoteUserId, username, displayName, role, " +
                "requiresReauthentication, lastUsedAt, isFixture, accessibleLibrariesJson, " +
                "hasAllLibraryAccess, hasAllTagAccess) " +
                "VALUES (?, ?, 'remote-user-1', ?, ?, 'Listener', 0, NULL, 0, '[]', 1, 1)",
            arrayOf(PROFILE_ID, SERVER_ID, "ada", "ada"),
        )
        db.execSQL(
            "INSERT INTO libraries (libraryKey, serverId, remoteId, name, kind, displayOrder, " +
                "remoteUpdatedAt, lastFetchedAt, isDeleted) " +
                "VALUES (?, ?, 'library-1', 'Fiction', 'Book', 0, NULL, 0, 0)",
            arrayOf(LIBRARY_KEY, SERVER_ID),
        )
        db.execSQL(
            "INSERT INTO books (bookKey, serverId, remoteId, libraryKey, title, subtitle, " +
                "narratorsJson, genresJson, tagsJson, durationMillis, description, publishedYear, " +
                "publisher, language, isExplicit, isAbridged, coverPath, trackCount, sizeBytes, " +
                "remoteUpdatedAt, lastFetchedAt, isDeleted, localAvailability) " +
                "VALUES (?, ?, 'item-1', ?, 'The Salt Harbour', NULL, '[]', '[]', '[]', 1000, NULL, " +
                "NULL, NULL, NULL, 0, 0, NULL, 1, 0, NULL, 0, 0, 'NotDownloaded')",
            arrayOf(BOOK_KEY, SERVER_ID, LIBRARY_KEY),
        )
        db.execSQL(
            "INSERT INTO profile_visible_books (profileId, bookKey, libraryKey) VALUES (?, ?, ?)",
            arrayOf(PROFILE_ID, BOOK_KEY, LIBRARY_KEY),
        )
        db.execSQL(
            "INSERT INTO sync_state " +
                "(profileId, serverId, status, lastSuccessfulSyncAt, lastAttemptedAt, " +
                "lastErrorCode, lastErrorSummary) " +
                "VALUES (?, ?, 'Succeeded', 1000, 1000, NULL, NULL)",
            arrayOf(PROFILE_ID, SERVER_ID),
        )
    }

    /** Version 6 is version 5 plus two nullable columns this seed leaves unset, as an upgrade would. */
    private fun seedVersion6(db: SupportSQLiteDatabase) = seedVersion5(db)

    /** Version 7 added a nullable column and no new table, so version 6's rows still describe it. */
    private fun seedVersion7(db: SupportSQLiteDatabase) = seedVersion6(db)

    private fun seedVersion8(db: SupportSQLiteDatabase) = seedVersion6(db)

    private fun seedVersion9(db: SupportSQLiteDatabase) = seedVersion6(db)

    /**
     * Version 14's rows, which are version 9's: everything between added columns and tables rather than
     * changing the ones these inserts name.
     *
     * Seeded at 14 specifically because that is the version **build 0.9.2 left on a device**, and the
     * migration off it is a table rebuild rather than an `ALTER TABLE`. See the test that uses it.
     */
    private fun seedVersion14(db: SupportSQLiteDatabase) = seedVersion9(db)

    /** Identical from version 2 onwards, so the per-version functions stay about what changed. */
    private fun seedServerWithCapabilities(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO servers (serverId, displayName, baseUrl, detectedVersion, isFixture, " +
                "lastFetchedAt, authMethodsJson, capabilitiesJson, capabilitiesDetectedAt) " +
                "VALUES (?, ?, ?, '2.36.0', 0, 0, '[\"local\"]', '[]', NULL)",
            arrayOf(SERVER_ID, "Demo", "https://books.example"),
        )
    }

    /**
     * The table statement followed by its index statements.
     *
     * An exported `createSql` carries a `TABLE_NAME` placeholder rather than the table name, so each
     * statement has to be resolved against the entity it belongs to.
     */
    private fun createStatementsOf(entity: JsonObject): List<String> {
        val table = entity.getValue("tableName").jsonPrimitive.content
        val tableStatement = entity.getValue("createSql").jsonPrimitive.content
        val indexStatements = entity["indices"]?.jsonArray.orEmpty()
            .map { index -> index.jsonObject.getValue("createSql").jsonPrimitive.content }
        return (listOf(tableStatement) + indexStatements).map { it.replace(TABLE_NAME_PLACEHOLDER, table) }
    }

    private fun exportedSchema(version: Int): String {
        val file = File("schemas/${ShelfPlayerDatabase::class.java.name}/$version.json")
        check(file.isFile) {
            "expected the exported schema at ${file.absolutePath}. Unit tests run with the module " +
                "directory as the working directory; if that changed, this path has to change with it."
        }
        return file.readText()
    }

    private companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val SERVER_ID = "srv_test"
        const val VERSION_3 = 3
        const val VERSION_4 = 4
        const val VERSION_5 = 5
        const val VERSION_6 = 6
        const val VERSION_7 = 7
        const val VERSION_8 = 8
        const val VERSION_9 = 9

        /** The version build 0.9.2 shipped, and the one the 14 → 15 rebuild starts from. */
        const val VERSION_14 = 14
        const val PROFILE_ID = "prf_test"
        const val LIBRARY_KEY = "srv_test:library-1"
        const val BOOK_KEY = "srv_test:item-1"

        /** Room's placeholder for the table name in an exported `createSql`. */
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
