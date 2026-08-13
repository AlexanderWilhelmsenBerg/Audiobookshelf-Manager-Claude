package com.example.shelfplayer.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * PRODUCT_SPEC 13.1 / 22.11 — every schema change ships a migration.
 *
 * `fallbackToDestructiveMigration` must never be called: losing a user's local playback position and
 * their downloaded books to a schema change is exactly the failure PRODUCT_SPEC 2.2 ("never lose
 * progress") forbids.
 *
 * A version bump adds its `Migration(n, n + 1)` here *and* a migration test under
 * `core/database/src/test/kotlin/.../migration/`, per PRODUCT_SPEC 18 ("database migrations include
 * old-to-new migration tests").
 */
object Migrations {
    /**
     * Version 2 — what a real session needs that a fixture profile did not (PRODUCT_SPEC AUTH-002,
     * SYNC-001).
     *
     * Every statement is additive. `ALTER TABLE ADD COLUMN` preserves every existing row, so a device
     * that has the Phase 0 demo library keeps it, along with any progress recorded against it. No
     * table is recreated and no data is copied, which is what makes this migration safe to run on a
     * database whose contents are the user's only copy of their listening position.
     *
     * The two `NOT NULL` columns carry a `DEFAULT`, and they must: `ADD COLUMN ... NOT NULL` without
     * one is rejected by SQLite for a table that already has rows. The same defaults are declared on
     * [com.example.shelfplayer.core.database.entity.ServerEntity] via `@ColumnInfo(defaultValue = …)`,
     * because Room compares them and a mismatch fails validation rather than silently diverging.
     *
     * The two nullable columns deliberately carry *no* `DEFAULT` clause. A nullable column defaults to
     * `NULL` already, and writing `DEFAULT NULL` records a default in the SQLite schema that Room's
     * expected schema does not have — a validation failure produced entirely by redundant SQL.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN authMethodsJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE servers ADD COLUMN capabilitiesJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE servers ADD COLUMN capabilitiesDetectedAt INTEGER")
            db.execSQL("ALTER TABLE profiles ADD COLUMN remoteUserId TEXT")
        }
    }

    /**
     * Version 3 — the server's library grant, persisted (PRODUCT_SPEC 5.2).
     *
     * The grant lived only in the transient `AuthSession`, and a sync that has to honour it runs later,
     * after a process restart. Storing it is what lets the gateway drop an unauthorized library before a
     * row reaches Room, rather than the UI hiding one that is already there.
     *
     * ### Why existing rows are granted everything and new ones nothing
     *
     * `hasAllLibraryAccess` defaults to `0` for a **new** row: a profile whose grant has not been
     * recorded must not see a library, the same reason an unprobed capability is unsupported.
     *
     * An **existing** row is a different case, and the `UPDATE` below is deliberate. Such a profile was
     * created before the app recorded grants, and whatever it can see is already cached in Room and
     * browsable offline. Applying the restrictive default to it would blank a library the user is
     * currently reading — a schema change destroying access to their content, which is what
     * PRODUCT_SPEC 2.2 and AUTH-004 ("existing downloaded playback continues") forbid. The permissive
     * value survives only until that profile's next sign-in, which overwrites it with the server's
     * actual grant.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN accessibleLibrariesJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE profiles ADD COLUMN hasAllLibraryAccess INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE profiles SET hasAllLibraryAccess = 1")
        }
    }

    /**
     * Version 4 — whether the account also sees every *item* (PRODUCT_SPEC 5.2).
     *
     * Audiobookshelf restricts twice: by library, and by tag within a library. Reconciliation deletes
     * what a sync did not return, so an account served a filtered item list must never drive deletions —
     * a device run showed a restricted account's sync marking 302 of another account's 490 books removed.
     *
     * Defaults to `0` for every row, new and existing alike, and unlike `hasAllLibraryAccess` in
     * MIGRATION_2_3 there is no permissive `UPDATE` for existing rows. The two defaults point opposite
     * ways because the risks do: getting `hasAllLibraryAccess` wrong for an upgrading profile hides
     * content the user already has, while getting *this* wrong deletes it. An existing profile simply
     * stops reconciling until its next sign-in records the real value — it still syncs, and it still adds.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN hasAllTagAccess INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Version 5 — item-level visibility, recorded per profile (PRODUCT_SPEC 5.2).
     *
     * See [com.example.shelfplayer.core.database.entity.ProfileVisibleBookEntity] for why a table is
     * the only place this can live.
     *
     * ### Why every profile is reset to never-synced
     *
     * The new table starts empty, and an empty table means "this profile can see nothing" — which is
     * the point, but it would also blank the shelf of an upgrading user until they discovered
     * pull-to-refresh. There is no honest way to backfill it: the cache records which books were
     * *stored*, never which account was shown them, and attributing them to every profile is precisely
     * the bug this migration exists to fix.
     *
     * So instead of inventing visibility, the migration removes the claim that a sync has happened.
     * `NeverSynced` is already the state that makes the home screen sync on its own, so the shelf
     * refills without the user doing anything, and it refills with each profile's real visibility. The
     * books, their tracks, their chapters and — the part that matters — every profile's progress are
     * untouched; only the assertion "this profile is up to date" is withdrawn, because after this
     * migration it is no longer true.
     *
     * Nothing is dropped and no table is recreated, so this remains an additive migration.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profile_visible_books` (
                    `profileId` TEXT NOT NULL,
                    `bookKey` TEXT NOT NULL,
                    `libraryKey` TEXT NOT NULL,
                    PRIMARY KEY(`profileId`, `bookKey`),
                    FOREIGN KEY(`profileId`) REFERENCES `profiles`(`profileId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_profile_visible_books_bookKey` " +
                    "ON `profile_visible_books` (`bookKey`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_profile_visible_books_profileId_libraryKey` " +
                    "ON `profile_visible_books` (`profileId`, `libraryKey`)",
            )
            db.execSQL("UPDATE sync_state SET status = 'NeverSynced', lastSuccessfulSyncAt = NULL")
        }
    }

    /**
     * Version 6 — the two identifiers LIB-002 says search must match (PRODUCT_SPEC LIB-002).
     *
     * Nullable columns with no default, which is the honest state for an upgrading cache: the rows
     * already stored were fetched before the app read these fields, so it does not know them. They
     * fill in on the next sync, and until then a search for an ISBN simply does not match a book whose
     * ISBN was never fetched — the same answer as a book that has none, and not a wrong one.
     *
     * No reset to `NeverSynced` here, unlike version 5. That migration withdrew a claim that had become
     * false; this one adds two fields nobody has searched by yet, and blanking every shelf to backfill
     * an identifier almost no self-hosted item carries is not a trade worth making.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN isbn TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN asin TEXT")
        }
    }

    /**
     * Version 7 — the server's own "added" timestamp (PRODUCT_SPEC LIB-002).
     *
     * `lastFetchedAt` was already stored and is not a substitute: it is when *this cache* read the item,
     * so a first sync stamps every book with the same instant and a "recently added" shelf built on it
     * would list the whole library in fetch order. The distinction is the only reason the column exists.
     *
     * Nullable and additive, like version 6. Rows fetched before this build do not know their added
     * date; they sort last in that order until the next sync fills it in, which is the honest place for
     * "unknown" and does not cost the user their offline library to correct.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN addedAt INTEGER")
        }
    }

    /**
     * Version 8 — the sleep-timer history (PRODUCT_SPEC PLAY-008, SET-002).
     *
     * A new table and nothing else. No existing table is touched, so there is no data to migrate and
     * nothing a device can lose by installing this build.
     *
     * ### Getting a hand-written `CREATE TABLE` to match Room's
     *
     * Room compares this statement's result against the schema its compiler exported, column for column
     * and index for index, and fails validation on any difference — including ones SQLite itself would
     * not care about. Three of them are worth naming, because each one has cost somebody an afternoon:
     *
     *  - every `NOT NULL` column is declared `NOT NULL` here, and every nullable one is *not*;
     *  - the foreign key clause has to match the entity's `onDelete`, spelled `ON DELETE CASCADE`;
     *  - the indices are created separately and their **names** must match Room's generated ones,
     *    which are `index_<table>_<column>`.
     *
     * The migration test opens version 7's exported schema, runs this, and lets Room validate the
     * result — so a mismatch fails in CI rather than on a device mid-upgrade.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sleep_timer_sessions` (
                    `sessionId` TEXT NOT NULL,
                    `profileId` TEXT NOT NULL,
                    `bookKey` TEXT NOT NULL,
                    `mode` TEXT NOT NULL,
                    `modeLength` INTEGER NOT NULL,
                    `startedAt` INTEGER NOT NULL,
                    `endedAt` INTEGER,
                    `outcome` TEXT,
                    `restarts` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`),
                    FOREIGN KEY(`profileId`) REFERENCES `profiles`(`profileId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sleep_timer_sessions_profileId` " +
                    "ON `sleep_timer_sessions` (`profileId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sleep_timer_sessions_startedAt` " +
                    "ON `sleep_timer_sessions` (`startedAt`)",
            )
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 / PLAY-005 — adds `playback_sessions`, the session outbox.
     *
     * Additive, like every migration here: a new table and its three indices, and nothing touched. An
     * upgrade from version 8 keeps every book, position and timer it had.
     *
     * The column list is written out rather than generated, and `wasProgressApplied` is the one worth
     * reading twice: it is `INTEGER` and **nullable**, because `null` ("the server has not answered") and
     * `0` ("the server declined this position as older than its own") are different facts and only one of
     * them means the row is still queued.
     */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playback_sessions` (
                    `sessionId` TEXT NOT NULL,
                    `profileId` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `bookKey` TEXT NOT NULL,
                    `remoteBookId` TEXT NOT NULL,
                    `remoteSessionId` TEXT,
                    `title` TEXT NOT NULL,
                    `author` TEXT,
                    `state` TEXT NOT NULL,
                    `positionMillis` INTEGER NOT NULL,
                    `durationMillis` INTEGER NOT NULL,
                    `timeListenedMillis` INTEGER NOT NULL,
                    `startedAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `syncedAt` INTEGER,
                    `wasProgressApplied` INTEGER,
                    `attempts` INTEGER NOT NULL,
                    `lastErrorCode` TEXT,
                    PRIMARY KEY(`sessionId`),
                    FOREIGN KEY(`profileId`) REFERENCES `profiles`(`profileId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_sessions_profileId` " +
                    "ON `playback_sessions` (`profileId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_sessions_state` ON `playback_sessions` (`state`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_sessions_updatedAt` " +
                    "ON `playback_sessions` (`updatedAt`)",
            )
        }
    }

    /**
     * PRODUCT_SPEC PLAY-007 — adds `book_playback_settings`, the per-book speed override.
     *
     * Additive, like every migration here. One table, one index, nothing touched.
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_playback_settings` (
                    `settingsKey` TEXT NOT NULL,
                    `profileId` TEXT NOT NULL,
                    `bookKey` TEXT NOT NULL,
                    `speedHundredths` INTEGER NOT NULL,
                    PRIMARY KEY(`settingsKey`),
                    FOREIGN KEY(`profileId`) REFERENCES `profiles`(`profileId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_book_playback_settings_profileId` " +
                    "ON `book_playback_settings` (`profileId`)",
            )
        }
    }

    val ALL: List<Migration> = listOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )
}
