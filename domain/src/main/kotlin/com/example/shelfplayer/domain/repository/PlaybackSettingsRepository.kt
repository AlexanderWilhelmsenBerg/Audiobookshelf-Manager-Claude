package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — how the listener has asked playback to behave.
 *
 * Separate from [PreferencesRepository], which holds what a *screen* looks like. These are read by the media
 * service, which has no screens and must keep working with the app process gone — and they are device-wide
 * where the preferences are per profile.
 */
interface PlaybackSettingsRepository {

    fun observeSettings(): Flow<PlaybackSettings>

    suspend fun setDefaultSpeed(speed: PlaybackSpeed): AppResult<Unit>

    suspend fun setSkipIntervals(skips: SkipIntervals): AppResult<Unit>

    suspend fun setAutoRewind(rewind: AutoRewind): AppResult<Unit>

    suspend fun setBufferPreset(preset: BufferPreset): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-004 / SET-002 — how close to the end of a book counts as finished.
     *
     * Clamped into `FinishedThreshold.Range` by the store rather than validated here, so that every route
     * to the setting — this one, and a stored value from a build whose range differed — lands inside it.
     *
     * This is only the listener's half of the rule. A book's library may ask for its own threshold, and
     * `FinishedThreshold` combines the two; there is no setter for that half, because it belongs to the
     * server.
     */
    suspend fun setFinishedThreshold(threshold: Duration): AppResult<Unit>

    /**
     * PRODUCT_SPEC ROUTE-001 / ROUTE-002 — whether connecting to a car starts the last book.
     *
     * Off unless explicitly chosen. ROUTE-002 will replace this with a policy per device; one global switch
     * is the honest interim, and the setting says so.
     */
    suspend fun setAutoPlayOnCarConnect(enabled: Boolean): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-007 — "per-book speed overrides profile default".
     *
     * `null` means no override, which is a different thing from an override of `1.0×`: the first follows the
     * profile default when it changes and the second does not.
     */
    fun observeSpeedFor(bookId: LibraryItemId): Flow<PlaybackSpeed?>

    /**
     * The speed to actually play [bookId] at: its override if it has one, the profile default otherwise.
     *
     * One call rather than two, because the fallback is the part a caller would get wrong — and the media
     * service is a caller that has to get it right with no UI to correct it.
     */
    suspend fun speedFor(bookId: LibraryItemId): PlaybackSpeed

    /** Sets the override, or clears it when [speed] is `null` so the book follows the default again. */
    suspend fun setSpeedFor(bookId: LibraryItemId, speed: PlaybackSpeed?): AppResult<Unit>
}
