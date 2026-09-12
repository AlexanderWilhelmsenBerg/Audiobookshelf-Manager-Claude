package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.RememberedBookRepository

internal class FakeRememberedBooks(
    remembered: LibraryItemId? = null,
    profileId: ProfileId = DEFAULT_PROFILE,
) : RememberedBookRepository {
    private val values = mutableMapOf<ProfileId, LibraryItemId>()

    init {
        if (remembered != null) values[profileId] = remembered
    }

    override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? = values[profileId]

    override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> {
        values[profileId] = bookId
        return AppResult.Success(Unit)
    }

    override suspend fun forget(profileId: ProfileId): AppResult<Unit> {
        values.remove(profileId)
        return AppResult.Success(Unit)
    }

    fun valueFor(profileId: ProfileId): LibraryItemId? = values[profileId]

    private companion object {
        val DEFAULT_PROFILE = ProfileId("profile-1")
    }
}
