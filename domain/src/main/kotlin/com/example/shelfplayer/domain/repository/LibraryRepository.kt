package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
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

    /**
     * PRODUCT_SPEC LIB-002 / 5.2 — every book this profile is granted, across all of its libraries.
     *
     * The grant is applied on read as well as on write. Writes already refuse an unauthorized library,
     * but a grant can *shrink* after rows have been stored, and nothing enumerates a library the server
     * has stopped offering — so its books would otherwise stay in this list for as long as the cache
     * lives. Filtering here is what makes "unauthorized libraries never appear" hold across that change
     * rather than only at the moment of the sync.
     */
    fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>>

    fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?>

    fun observeSyncState(profileId: ProfileId): Flow<SyncState>

    /**
     * Fetches the accessible libraries and their items and writes them into Room.
     *
     * Returns [AppResult.Success] with the number of books written. A failure never clears cached
     * content (PRODUCT_SPEC LIB-001).
     */
    suspend fun refresh(profileId: ProfileId): AppResult<Int>

    /**
     * PRODUCT_SPEC LIB-001 — stores positions the server reported, without re-reading the library.
     *
     * The cheap half of keeping up to date. A full [refresh] is an N+1 over every item — 491 requests
     * on the library a device run used — and a book played on another device changes one number. The
     * positions arrive with the account state the app already fetches, so this costs no request at all.
     *
     * Takes them rather than fetching them: the credential lives in the auth layer and the rows live
     * here, and a use case owning the composition is what keeps either from reaching into the other.
     *
     * Returns how many rows were written, which is not the number offered — see the implementation for
     * which positions are declined and why.
     */
    suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int>
}
