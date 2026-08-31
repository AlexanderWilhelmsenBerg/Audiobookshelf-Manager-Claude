package com.example.shelfplayer.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Whether resume selection may follow Audiobookshelf activity from other clients. */
interface ResumePolicyRepository {
    fun observeCrossDeviceResumeEnabled(): Flow<Boolean>

    suspend fun isCrossDeviceResumeEnabled(): Boolean

    suspend fun setCrossDeviceResumeEnabled(enabled: Boolean)

    /** Keeps manually constructed test collaborators source-compatible until they opt into policy testing. */
    object AlwaysEnabled : ResumePolicyRepository {
        override fun observeCrossDeviceResumeEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun isCrossDeviceResumeEnabled(): Boolean = true

        override suspend fun setCrossDeviceResumeEnabled(enabled: Boolean) = Unit
    }
}
