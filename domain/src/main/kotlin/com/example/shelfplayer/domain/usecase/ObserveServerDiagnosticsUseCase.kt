package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PRODUCT_SPEC SYNC-001 — "the compatibility result is visible in diagnostics".
 *
 * The handshake has been persisted since Phase 1 wave 1 and nothing has ever shown it, which made the
 * one part of it that matters unverifiable in the field: the supported set is deliberately **empty**
 * because an unconfirmed capability is an unsupported one, and from outside the app that is
 * indistinguishable from a handshake that never ran. This is what tells those two apart.
 */
class ObserveServerDiagnosticsUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val capabilityRepository: CapabilityRepository,
    private val realtime: RealtimeUpdates,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ServerDiagnostics?> =
        profileRepository.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                combine(
                    profileRepository.observeServers(),
                    capabilityRepository.observeCapabilities(profile.serverId),
                    realtime.status,
                ) { servers, capabilities, socket ->
                    ServerDiagnostics(
                        serverAddress = servers.firstOrNull { it.id == profile.serverId }?.baseUrl,
                        reportedVersion = capabilities?.serverVersion,
                        authMethods = capabilities?.authMethods.orEmpty(),
                        hasHandshake = capabilities != null,
                        confirmed = capabilities?.supported.orEmpty(),
                        socketStatus = socket,
                    )
                }
            }
        }
}

/**
 * What the app has actually learned about the server it is talking to.
 *
 * @property hasHandshake distinguishes "the probe ran and confirmed nothing" from "the probe never
 *   ran". [ServerCapabilities] deliberately has no third state per capability — a third state is what
 *   invites code to guess — so the distinction lives here, where it is presented rather than acted on.
 * @property confirmed only the capabilities a probe confirmed. Everything else is unsupported.
 */
data class ServerDiagnostics(
    val serverAddress: String?,
    val reportedVersion: String?,
    val authMethods: List<String>,
    val hasHandshake: Boolean,
    val confirmed: Set<ServerCapability>,
    val socketStatus: RealtimeStatus,
) {
    /** Every capability the app knows how to ask about, paired with whether this server confirmed it. */
    val allCapabilities: List<Pair<ServerCapability, Boolean>>
        get() = ServerCapability.entries.map { it to (it in confirmed) }
}
