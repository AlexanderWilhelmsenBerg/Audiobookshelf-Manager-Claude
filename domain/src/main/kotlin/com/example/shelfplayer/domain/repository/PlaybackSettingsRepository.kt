package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.StartupMode
import kotlinx.coroutines.flow.Flow

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
     * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — which categories may spend cellular data.
     *
     * On this repository rather than a new one because it is the same store and the same screen. A
     * separate `NetworkPolicyRepository` would be one interface, one binding and one more thing for a
     * settings ViewModel to combine, for three booleans.
     */
    fun observeNetworkPolicy(): Flow<NetworkPolicy>

    suspend fun setNetworkPolicy(policy: NetworkPolicy): AppResult<Unit>

    /** PRODUCT_SPEC DL-005 / DL-006 — smart download, and the two ways a download may be removed unasked. */
    fun observeHousekeeping(): Flow<DownloadHousekeeping>

    suspend fun setHousekeeping(housekeeping: DownloadHousekeeping): AppResult<Unit>

    /**
     * PRODUCT_SPEC ROUTE-001 / ROUTE-002 — whether connecting to a car starts the last book.
     *
     * Off unless explicitly chosen. ROUTE-002 will replace this with a policy per device; one global switch
     * is the honest interim, and the setting says so.
     */
    suspend fun setAutoPlayOnCarConnect(enabled: Boolean): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-002 — pause or duck when something interrupts briefly.
     *
     * Applied to the **next** player, like the buffer preset and for the same reason: it is expressed as the
     * audio attributes the player is built with, and Media3 fixes those at construction. Changing it does
     * not disturb a book that is already playing, which is the right way round.
     */
    suspend fun setFocusBehaviour(behaviour: FocusBehaviour): AppResult<Unit>

    /** PRODUCT_SPEC ROUTE-003 — what opening the app does to the player. */
    suspend fun setStartupMode(mode: StartupMode): AppResult<Unit>

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
