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

    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
