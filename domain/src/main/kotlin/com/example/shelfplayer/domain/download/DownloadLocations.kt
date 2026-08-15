package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC DL-003 / ADR-0018 decision 4 / ADR-0020 — where downloads are written, and where they could be.
 *
 * ### Changing it moves nothing
 *
 * The manifest records each file's **absolute** location, so a book already downloaded stays exactly where
 * it is and keeps playing. The choice applies to the next download. That is not a limitation worked around
 * — it is what makes the setting safe to change: nothing is copied, nothing is deleted, and a card pulled
 * out afterwards costs the books on it and nothing else.
 *
 * ### Removing the card is a handled case, not an error
 *
 * A volume that is gone resolves to internal storage for new downloads, and the books that were on it fail
 * the start-up check and offer a retry — the same handling PLAY-003 already requires for any unreadable
 * local file. Nothing is deleted on the strength of a missing card.
 */
interface DownloadLocations {

    /**
     * The volumes this app can actually write to, internal first.
     *
     * A device fact, read fresh: a card inserted while the screen is open should appear the next time
     * somebody looks, and one removed should stop being offered.
     */
    suspend fun options(): List<StorageVolumeOption>

    /** The chosen volume's UUID. Empty is internal storage, which is the default. */
    fun observeSelected(): Flow<String>

    /**
     * Chooses where the *next* download goes.
     *
     * @return failure only when the setting could not be written. Choosing a volume that has since been
     *   removed is not a failure here — it is stored, and resolves to internal storage until the volume
     *   comes back.
     */
    suspend fun select(uuid: String): AppResult<Unit>
}
