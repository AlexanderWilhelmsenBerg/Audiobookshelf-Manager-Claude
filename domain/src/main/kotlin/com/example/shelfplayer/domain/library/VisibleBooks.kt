package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow

/**
 * The rows every browse axis starts from: this profile's visible books, optionally one library's.
 *
 * Four use cases needed the same two-line choice and PRODUCT_SPEC 5.2 makes getting it wrong a
 * permission bug rather than a display one, so it is written once. `null` is every granted library —
 * the state of a profile that has not starred one — and is not the same as a library that happens to
 * hold everything.
 */
internal fun LibraryRepository.visibleBooks(profileId: ProfileId, libraryId: LibraryId?): Flow<List<Book>> =
    if (libraryId == null) observeAccessibleBooks(profileId) else observeBooks(profileId, libraryId)
