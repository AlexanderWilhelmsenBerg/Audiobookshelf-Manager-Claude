package com.example.shelfplayer.testing

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.settings.ProfilePreferences
import com.example.shelfplayer.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * PRODUCT_SPEC SET-001 — an in-memory preference store, shared by the three screens that read one.
 *
 * A fake with real state rather than a stub, because the behaviour under test is the round trip: the
 * screens no longer hold the sort order themselves, so "the chip moved" and "the write landed" are the
 * same assertion, and a stub that returned success without storing anything would pass either way.
 *
 * Shared rather than copied per test file — unlike the older fakes here — because a second copy of a
 * store is a second set of write semantics, and the point of the type is that all three screens see
 * one.
 */
internal class FakePreferences(initial: ProfilePreferences = ProfilePreferences.Empty) : PreferencesRepository {

    private val state = MutableStateFlow(initial)

    /** Makes every write fail, which is how "the chip does not move" gets a test. */
    var refuseWrites: Boolean = false

    val forgotten = mutableListOf<ProfileId>()

    override fun observePreferences(): Flow<ProfilePreferences> = state

    override suspend fun setDefaultLibrary(libraryId: LibraryId?): AppResult<Unit> =
        write { current -> current.copy(defaultLibraryId = libraryId) }

    override suspend fun setSortOrder(libraryId: LibraryId?, order: String): AppResult<Unit> = write { current ->
        if (libraryId == null) {
            current.copy(shelfOrder = order)
        } else {
            current.copy(libraryOrders = current.libraryOrders + (libraryId.value to order))
        }
    }

    override suspend fun forget(profileId: ProfileId): AppResult<Unit> {
        forgotten += profileId
        return AppResult.Success(Unit)
    }

    private fun write(transform: (ProfilePreferences) -> ProfilePreferences): AppResult<Unit> {
        if (refuseWrites) return AppResult.Failure(AppError.Storage(summary = "The store is unwritable."))
        state.update(transform)
        return AppResult.Success(Unit)
    }
}
