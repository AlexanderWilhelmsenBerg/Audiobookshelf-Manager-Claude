package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.domain.repository.CapabilityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * PRODUCT_SPEC SYNC-001 / DL-001 — a capability store the download tests can both read and assert on.
 *
 * It keeps real state rather than recording calls, because both directions matter here: the downloader
 * *writes* what it observed, and on the next attempt it *reads* the write back to decide whether to ask
 * for a range at all. A recording mock would prove the first and silently pass the second.
 */
internal class RecordingCapabilities(supported: Set<ServerCapability> = emptySet()) : CapabilityRepository {

    private val stored = MutableStateFlow(supported)

    /** Every observation in order, so a test can assert that nothing was recorded as well as what was. */
    val observations = mutableListOf<Pair<ServerCapability, Boolean>>()

    override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> =
        MutableStateFlow(capabilitiesOf(serverId))

    override suspend fun capabilities(serverId: ServerId): ServerCapabilities = capabilitiesOf(serverId)

    override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> =
        AppResult.Success(capabilitiesOf(ServerId("server-1")))

    override suspend fun record(
        serverId: ServerId,
        capability: ServerCapability,
        isSupported: Boolean,
    ): AppResult<Unit> {
        observations += capability to isSupported
        stored.value = if (isSupported) stored.value + capability else stored.value - capability
        return AppResult.Success(Unit)
    }

    private fun capabilitiesOf(serverId: ServerId) =
        ServerCapabilities(serverId = serverId, serverVersion = null, supported = stored.value)
}
