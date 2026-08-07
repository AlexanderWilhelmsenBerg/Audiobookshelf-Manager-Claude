package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.settings.ProfilePreferences
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC SET-001 / AUTH-002 — the active profile's view preferences.
 *
 * Scoped to the active profile by the implementation rather than by the caller. Every screen that has
 * a preference already knows which one it wants; none of them should have to remember to ask for the
 * right account's copy, because forgetting once is how one person's arrangement lands on another's
 * shelf (PRODUCT_SPEC 5.2).
 *
 * Writes take [AppResult] because the store is a file: a write can fail, and a sort chip that silently
 * did nothing is worse than one that reports it could not.
 */
interface PreferencesRepository {
    /** Emits [ProfilePreferences.Empty] while no profile is active, and re-emits on a switch. */
    fun observePreferences(): Flow<ProfilePreferences>

    /**
     * PRODUCT_SPEC 6.1 step 9 — `null` clears the choice and returns the profile to every library it
     * is granted, which is not the same as choosing one that happens to hold everything.
     */
    suspend fun setDefaultLibrary(libraryId: LibraryId?): AppResult<Unit>

    /**
     * PRODUCT_SPEC LIB-002 — persists a sort order.
     *
     * `libraryId == null` is the home shelf, which spans libraries and so has no id to key on. One
     * function rather than two because the caller's difference *is* that argument.
     */
    suspend fun setSortOrder(libraryId: LibraryId?, order: String): AppResult<Unit>

    /** PRODUCT_SPEC AUTH-002 — removing a profile takes its preferences with it. */
    suspend fun forget(profileId: ProfileId): AppResult<Unit>
}
