package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * BW-PLAY-01 — the audiobook this physical BookWave install most recently took local playback ownership of.
 *
 * This is deliberately not a view over server progress. Audiobookshelf progress answers where an account
 * has listened, potentially on another phone or the web client; this repository answers which book this
 * device locally chose. The two facts meet only when a consumer later asks the shared resume-freshness
 * owner for a position.
 *
 * The identity is keyed by [ProfileId], contains only the opaque library-item id, and survives process
 * death/offline use. A profile with no trustworthy local ownership evidence emits `null`; remote recency is
 * never used to manufacture a value.
 */
interface RememberedBookRepository {
    fun observe(profileId: ProfileId): Flow<LibraryItemId?>

    /** Records an unambiguous local playback/session ownership decision for [profileId]. */
    suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit>
}
