package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC SYNC-001 — the persisted capability handshake.
 *
 * Two ideas that look alike and are not:
 *
 * - a capability **absent** from [ServerCapabilities.supported] is unsupported. There is no third
 *   state, because a third state is what lets code decide to try anyway.
 * - a **`null`** from [observeCapabilities] means no handshake has ever run for that server. That is a
 *   different fact, and the UI needs it: "this server does not support downloads" and "we have not
 *   asked yet" call for different words (PRODUCT_SPEC SYNC-001 requires features to be disabled *with
 *   an explanation*).
 */
interface CapabilityRepository {
    /** The stored handshake for [serverId], or `null` when none has run. */
    fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?>

    suspend fun capabilities(serverId: ServerId): ServerCapabilities?

    /**
     * Runs the handshake for the server [profileId] belongs to and persists the result.
     *
     * PRODUCT_SPEC SYNC-001 runs this on login and after a server upgrade is detected. It takes a
     * profile rather than a server because the profile is what knows which address to talk to, and
     * because a handshake attributed to the wrong connection is worse than none (PRODUCT_SPEC 5.2).
     *
     * Read-only: PRODUCT_SPEC SYNC-001 requires capability probes not to change server state.
     */
    suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities>
}
