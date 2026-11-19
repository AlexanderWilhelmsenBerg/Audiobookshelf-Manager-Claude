package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SeriesOrderRepository
import com.example.shelfplayer.domain.repository.SeriesSequenceRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SeriesOrderOwner(val profileId: ProfileId, val generation: Long)

data class SeriesOrderSave(val applied: Int, val total: Int, val error: AppError? = null)

/** The server saves one book at a time. A retry checks each item's current sequence before writing. */
class SaveSeriesOrderUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val network: NetworkMonitor,
    private val orders: SeriesOrderRepository,
    private val sequences: SeriesSequenceRepository,
) {
    suspend operator fun invoke(owner: SeriesOrderOwner, seriesId: SeriesId, order: SeriesOrder): SeriesOrderSave {
        val changes = order.changes
        var applied = 0
        for (change in changes) {
            val blocked = errorFor(owner) ?: if (network.isOnline.first()) null else AppError.Network()
            if (blocked != null) return SeriesOrderSave(applied, changes.size, blocked)
            when (val result = sequences.changeSequence(owner.profileId, seriesId, change)) {
                is AppResult.Failure -> return SeriesOrderSave(applied, changes.size, result.error)
                is AppResult.Success -> applied++
            }
        }
        val blocked = errorFor(owner)
        if (blocked != null) return SeriesOrderSave(applied, changes.size, blocked)
        val gapsError = when (val result = orders.saveMissingBooks(owner.profileId, seriesId, order.missingBooks)) {
            is AppResult.Failure -> result.error
            is AppResult.Success -> null
        }
        return SeriesOrderSave(applied, changes.size, gapsError)
    }

    private suspend fun errorFor(owner: SeriesOrderOwner): AppError? {
        val active = profiles.observeActiveProfile().first()
        return when {
            active?.id != owner.profileId || profiles.activeProfileGeneration() != owner.generation ->
                AppError.Conflict("The active profile changed while saving.")

            active.requiresReauthentication -> AppError.Authentication()

            !active.canUpdate -> AppError.Authorization("This account cannot edit book metadata.")

            else -> null
        }
    }
}
