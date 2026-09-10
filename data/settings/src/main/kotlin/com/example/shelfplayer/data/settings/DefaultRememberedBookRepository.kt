package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** BW-PLAY-01 — Proto-backed, per-profile local playback identity. */
@Singleton
class DefaultRememberedBookRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val logger: Logger,
) : RememberedBookRepository {
    override fun observe(profileId: ProfileId): Flow<LibraryItemId?> = settings.rememberedBookId(profileId)

    override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
        resultOf(onError = ::storeFailure) {
            settings.setRememberedBookId(profileId, bookId)
        }

    private fun storeFailure(throwable: Throwable): AppError {
        logger.warn(
            LogCategory.Playback,
            "The local remembered audiobook identity could not be written",
            throwable = throwable,
        )
        return AppError.Storage(summary = "The last played audiobook could not be remembered on this device.")
    }
}
