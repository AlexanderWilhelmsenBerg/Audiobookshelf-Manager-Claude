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
import javax.inject.Inject
import javax.inject.Singleton

/** BW-PLAY-01 — Proto DataStore-backed local playback ownership. */
@Singleton
class DefaultRememberedBookRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val logger: Logger,
) : RememberedBookRepository {

    override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? =
        settings.rememberedBook(profileId)

    override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
        resultOf(onError = ::storeFailure) {
            settings.setRememberedBook(profileId, bookId)
        }

    override suspend fun forget(profileId: ProfileId): AppResult<Unit> =
        resultOf(onError = ::storeFailure) {
            settings.clearRememberedBook(profileId)
        }

    private fun storeFailure(throwable: Throwable): AppError {
        logger.warn(
            LogCategory.Playback,
            "The locally played book could not be remembered",
            throwable = throwable,
        )
        return AppError.Storage(summary = "The last played book could not be saved on this device.")
    }
}
