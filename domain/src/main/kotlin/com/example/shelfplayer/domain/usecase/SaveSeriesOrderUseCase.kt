package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesSequenceChange
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SeriesOrderRepository
import com.example.shelfplayer.domain.repository.SeriesSequenceRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SeriesOrderOwner(val profileId: ProfileId, val generation: Long)

data class SeriesOrderSave(
    val applied: Int,
    val total: Int,
    val order: SeriesOrder,
    val error: AppError?,
    val unrefreshedBooks: Set<LibraryItemId>,
)

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
        val unrefreshed = mutableSetOf<LibraryItemId>()
        fun finish(error: AppError?, uncertain: SeriesSequenceChange? = null): SeriesOrderSave = SeriesOrderSave(
            applied,
            changes.size,
            order.recordSave(changes.take(applied), uncertain),
            error,
            unrefreshed,
        )

        for (change in changes) {
            val blocked = errorFor(owner) ?: if (network.isOnline.first()) null else AppError.Network()
            if (blocked != null) return finish(blocked)
            when (val result = sequences.changeSequence(owner.profileId, seriesId, change) { errorFor(owner) }) {
                is AppResult.Failure -> return finish(result.error, change.takeIf { result.error.mayHaveCommitted() })

                is AppResult.Success -> {
                    applied++
                    if (result.value.isLocalCopyStale) unrefreshed += change.bookId
                }
            }
        }
        val blocked = errorFor(owner)
        if (blocked != null) return finish(blocked)
        val gapsError = when (val result = orders.saveMissingBooks(owner.profileId, seriesId, order.missingBooks)) {
            is AppResult.Failure -> result.error
            is AppResult.Success -> null
        }
        return finish(gapsError)
    }

    private fun AppError.mayHaveCommitted(): Boolean =
        isRetryable || this is AppError.ApiCompatibility || this is AppError.Unknown

    private suspend fun errorFor(owner: SeriesOrderOwner): AppError? {
        val active = profiles.observeActiveProfile().first()
        return when {
            active?.id != owner.profileId || profiles.stableActiveProfileGeneration() != owner.generation ->
                AppError.Conflict("The active profile changed while saving.")

            active.requiresReauthentication -> AppError.Authentication()

            !active.canUpdate -> AppError.Authorization("This account cannot edit book metadata.")

            else -> null
        }
    }
}
