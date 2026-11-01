package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.BluetoothIdentityCapability
import kotlinx.coroutines.flow.StateFlow

/** Phone-facing capability and refresh boundary. Inventory and route state remain with the router. */
class OutputIdentityAccess internal constructor(
    val capability: StateFlow<BluetoothIdentityCapability>,
    private val refreshInventory: () -> Unit,
) {
    fun refresh() = refreshInventory()
}
