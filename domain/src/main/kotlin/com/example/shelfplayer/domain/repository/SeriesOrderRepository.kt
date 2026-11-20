package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesSequenceChange
import kotlinx.coroutines.flow.Flow

/** LIB-005. Device-local gaps in a profile's view of a series. */
interface SeriesOrderRepository {
    fun observeMissingBooks(profileId: ProfileId, seriesId: SeriesId): Flow<Set<Int>>

    suspend fun saveMissingBooks(profileId: ProfileId, seriesId: SeriesId, numbers: Set<Int>): AppResult<Unit>
}

/** The server owns the sequence values for real books. */
fun interface SeriesSequenceRepository {
    suspend fun changeSequence(
        profileId: ProfileId,
        seriesId: SeriesId,
        change: SeriesSequenceChange,
        beforeWrite: suspend () -> AppError?,
    ): AppResult<MetadataSaveResult>
}
