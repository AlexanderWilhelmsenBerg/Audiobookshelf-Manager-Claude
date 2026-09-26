package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC DL-003 / ADR-0020 / BW-DL-05 — selected future destination and current volume reachability.
 *
 * Selection and availability are deliberately separate. Removing a selected card never rewrites the user's
 * preference; a new copy may fall back internally while existing physical copies keep their own owner.
 */
interface DownloadLocations {

    /** Volumes this app can write to right now, internal first. */
    suspend fun options(): List<StorageVolumeOption>

    /** The chosen future-volume UUID. Empty is internal. This survives temporary volume absence. */
    fun observeSelected(): Flow<String>

    /** Aggregate mounted/reachable UUID set; used to react to card removal/reinsertion without per-row listeners. */
    fun observeAvailableVolumeUuids(): Flow<Set<String>>

    /**
     * Current accessibility of one physical copy's durable owner.
     *
     * null is legacy Unknown, empty is internal/Available, and an absent known removable UUID is Unavailable.
     * Raw UUIDs remain inside the storage layer and must never be surfaced to UI/log copy.
     */
    fun availability(volumeUuid: String?): DownloadStorageState

    /** Chooses where future new physical copies prefer to go; it does not move existing bytes. */
    suspend fun select(uuid: String): AppResult<Unit>
}
