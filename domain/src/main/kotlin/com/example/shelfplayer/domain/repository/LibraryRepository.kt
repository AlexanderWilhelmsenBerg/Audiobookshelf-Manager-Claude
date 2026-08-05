package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC 9.1 / LIB-001 — Room is the read source; the network only writes into it.
 *
 * Every `observe*` function is backed by a database query, so a refresh that fails leaves the last
 * cached content on screen. Every `refresh*` function returns [AppResult] instead of throwing, and
 * takes an explicit [ProfileId] because unauthorized content must never leak across a profile
 * boundary (PRODUCT_SPEC 5.2).
 */
interface LibraryRepository {
    fun observeLibraries(profileId: ProfileId): Flow<List<Library>>

    fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?>

    fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>>

    fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?>

    fun observeSyncState(profileId: ProfileId): Flow<SyncState>

    /**
     * Fetches the accessible libraries and their items and writes them into Room.
     *
     * Returns [AppResult.Success] with the number of books written. A failure never clears cached
     * content (PRODUCT_SPEC LIB-001).
     */
    suspend fun refresh(profileId: ProfileId): AppResult<Int>
}
