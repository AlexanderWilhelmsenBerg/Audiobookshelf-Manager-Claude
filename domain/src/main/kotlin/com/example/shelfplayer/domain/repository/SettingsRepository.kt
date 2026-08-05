package com.example.shelfplayer.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC SET-001 / SET-002 — the settings a screen may read and write.
 *
 * Only the settings something already honours are declared. PRODUCT_SPEC SET-002 lists a great many
 * more; each arrives here with the behaviour that reads it, because a setting nothing consults is a
 * promise the app does not keep.
 *
 * Reads are [Flow]s so a change applies without the screen that made it having to tell anyone.
 */
interface SettingsRepository {
    /**
     * PRODUCT_SPEC LIB-002 — whether the home screen lists libraries instead of books.
     *
     * `false` is the product default: the app opens on the books. A list of libraries is a hop between
     * the user and their shelf, so it is the opt-in.
     */
    val homeShowsLibraries: Flow<Boolean>

    suspend fun setHomeShowsLibraries(enabled: Boolean)
}
