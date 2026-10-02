package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.Flow

/** BW-PLAY-01 — one opaque, device-local remembered audiobook identity per profile. */
interface RememberedBookRepository {
    suspend fun rememberedBook(profileId: ProfileId): LibraryItemId?

    /** Emits the current remembered identity, then every change, so Android Auto's resume tile can refresh. */
    fun observeRememberedBook(profileId: ProfileId): Flow<LibraryItemId?>

    suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit>

    suspend fun forget(profileId: ProfileId): AppResult<Unit>
}
