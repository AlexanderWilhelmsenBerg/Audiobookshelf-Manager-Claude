package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeRememberedBooks(remembered: LibraryItemId? = null, profileId: ProfileId = DEFAULT_PROFILE) :
    RememberedBookRepository {
    private val state = MutableStateFlow<Map<ProfileId, LibraryItemId>>(emptyMap())

    init {
        if (remembered != null) state.value = mapOf(profileId to remembered)
    }

    override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? = state.value[profileId]

    override fun observeRememberedBook(profileId: ProfileId): Flow<LibraryItemId?> =
        state.map { values -> values[profileId] }

    override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> {
        state.value += (profileId to bookId)
        return AppResult.Success(Unit)
    }

    override suspend fun forget(profileId: ProfileId): AppResult<Unit> {
        state.value -= profileId
        return AppResult.Success(Unit)
    }

    fun valueFor(profileId: ProfileId): LibraryItemId? = state.value[profileId]

    private companion object {
        val DEFAULT_PROFILE = ProfileId("profile-1")
    }
}
