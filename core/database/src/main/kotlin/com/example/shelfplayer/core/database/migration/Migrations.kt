package com.example.shelfplayer.core.database.migration

import androidx.room.migration.Migration

/**
 * PRODUCT_SPEC 13.1 / 22.11 — every schema change ships a migration.
 *
 * The list is empty because version 1 is the first released schema, not because migrations are
 * optional. `fallbackToDestructiveMigration` must never be called: losing a user's local playback
 * position and their downloaded books to a schema change is exactly the failure PRODUCT_SPEC 2.2
 * ("never lose progress") forbids.
 *
 * A version bump adds its `Migration(n, n + 1)` here *and* a migration test under
 * `core/database/src/test/kotlin/.../migration/`, per PRODUCT_SPEC 18 ("database migrations include
 * old-to-new migration tests").
 */
object Migrations {
    val ALL: List<Migration> = emptyList()
}
