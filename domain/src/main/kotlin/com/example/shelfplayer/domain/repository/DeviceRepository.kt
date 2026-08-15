package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC ROUTE-002 / SET-002 (Devices) — the output devices this app has seen, and what each may do.
 *
 * Its own repository rather than three more methods on [PlaybackSettingsRepository], because the thing it
 * stores is a *set of rows the user manages* — added by connecting a device, removed by forgetting one —
 * and not a handful of switches. The settings screen lists them; the playback service reads one at a time.
 */
interface DeviceRepository {

    /** Every device this app has seen, most recently connected first. */
    fun observeDevices(): Flow<List<KnownDevice>>

    /**
     * Records a connection, adding the device with the default policy if it is new.
     *
     * Never overwrites an existing policy. This runs on *every* connection, and a device somebody set to
     * `Never` last week must not quietly become `Arm only` because they plugged it in again.
     */
    suspend fun remember(device: KnownDevice): AppResult<Unit>

    suspend fun setPolicy(deviceId: String, policy: DevicePolicy): AppResult<Unit>

    /** Forgets a device somebody no longer owns. It comes back as new, with the default policy, if it returns. */
    suspend fun forget(deviceId: String): AppResult<Unit>

    /**
     * The policy for one device, for the connection callback to act on.
     *
     * A point read rather than a flow: the question is asked once, at the moment something connects, and
     * the answer decides what happens in the next few milliseconds.
     */
    suspend fun policyFor(deviceId: String): DevicePolicy
}
