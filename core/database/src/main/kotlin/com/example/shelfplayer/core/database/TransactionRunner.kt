package com.example.shelfplayer.core.database

import androidx.room.withTransaction

/**
 * Runs [block] inside a single database transaction.
 *
 * Exposed from `:core:database` so that data modules can be transactional without taking a compile
 * dependency on Room itself — PRODUCT_SPEC 9.3 keeps Room inside this module, and a leaked
 * `androidx.room` import in `:data:*` is exactly how that boundary erodes.
 *
 * PRODUCT_SPEC LIB-001 depends on this: a sync either applies completely or not at all, so the UI
 * can never observe a library whose books were written but whose chapters were not.
 */
suspend fun <R> ShelfPlayerDatabase.runInTransaction(block: suspend () -> R): R = withTransaction(block)
