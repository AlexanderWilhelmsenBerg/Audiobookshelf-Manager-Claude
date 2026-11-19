package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.SeriesOrderRepository
import javax.inject.Inject

class DefaultSeriesOrderRepository @Inject constructor(private val settings: AppSettingsDataSource) :
    SeriesOrderRepository {
    override fun observeMissingBooks(profileId: ProfileId, seriesId: SeriesId) =
        settings.observeMissingSeriesBooks(profileId, seriesId)

    override suspend fun saveMissingBooks(
        profileId: ProfileId,
        seriesId: SeriesId,
        numbers: Set<Int>,
    ): AppResult<Unit> = resultOf(onError = { AppError.Storage("Missing books could not be saved on this device.") }) {
        if (!settings.setMissingSeriesBooks(profileId, seriesId, numbers)) {
            return AppResult.Failure(AppError.Conflict("The active profile changed while saving."))
        }
    }
}
