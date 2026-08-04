package com.example.shelfplayer.core.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a block inside a single database transaction.
 *
 * PRODUCT_SPEC 9.3 keeps Room inside `:core:database`. This interface is what makes that boundary
 * real rather than aspirational: its signature names no Room type, so a data module can be
 * transactional while `androidx.room` stays off its compile classpath entirely.
 *
 * The earlier shape — an extension function on `ShelfPlayerDatabase` — did not achieve that.
 * Calling any member of `ShelfPlayerDatabase` requires the caller to resolve its supertype
 * `RoomDatabase`, so `:data:library` failed to compile with "Cannot access 'RoomDatabase' which is a
 * supertype of 'ShelfPlayerDatabase'". The boundary was correct; the seam was in the wrong place.
 *
 * PRODUCT_SPEC LIB-001 depends on this: a sync either applies completely or not at all, so the UI
 * can never observe a library whose books were written but whose chapters were not.
 */
interface DatabaseTransactionRunner {
    suspend operator fun <R> invoke(block: suspend () -> R): R
}

@Singleton
class RoomDatabaseTransactionRunner @Inject constructor(private val database: ShelfPlayerDatabase) :
    DatabaseTransactionRunner {
    override suspend fun <R> invoke(block: suspend () -> R): R = database.withTransaction(block)
}
