package com.example.shelfplayer.data.library.mapper

import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AccountProgress

/**
 * PRODUCT_SPEC PLAY-004 — positions, from the shape the server reports to the shape Room stores.
 *
 * Its own file rather than another member of `EntityMappers`, which had reached the point where one
 * more function tripped detekt — and the split is the right one anyway: everything here is about a
 * *profile's* position in a book, while `EntityMappers` is about the catalogue, which belongs to the
 * server and is shared between profiles.
 */
internal object ProgressMappers {
    /**
     * PRODUCT_SPEC PLAY-004 — a server-reported position, stored against the profile that asked for it.
     *
     * `hasUnsyncedChanges = false` by construction: this row *came from* the server, so there is
     * nothing of the device's left in it to upload. Only a caller that has already declined to overwrite
     * a pending local row may use this — see `DefaultLibraryRepository.writeProgress`.
     */
    fun toEntity(
        profileId: ProfileId,
        serverId: ServerId,
        bookKey: String,
        progress: AccountProgress,
    ): MediaProgressEntity = MediaProgressEntity(
        progressKey = EntityKey.scoped(profileId.value, bookKey),
        profileId = profileId.value,
        bookKey = bookKey,
        serverId = serverId.value,
        positionMillis = progress.position.inWholeMilliseconds,
        durationMillis = progress.duration.inWholeMilliseconds,
        isFinished = progress.isFinished,
        updatedAt = progress.updatedAt.toEpochMilli(),
        hasUnsyncedChanges = false,
    )
}
