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

    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
