package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.StorageDiagnostics
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — what this device has stored, in numbers.
 *
 * A [Flow] rather than a one-shot read: several of the checks it exists for are *deltas*. "A sync did not
 * add a row for a library this account cannot see" and "signing out deleted a credential" are both a
 * before-and-after, and a screen that updates as the sync runs shows them without anyone having to
 * remember what the number was a minute ago.
 */
interface DiagnosticsRepository {
    fun observeStorage(): Flow<StorageDiagnostics>
}
