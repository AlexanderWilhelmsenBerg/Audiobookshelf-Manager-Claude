package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake, against `GET /status`.
 *
 * ### Why this returns an empty capability set
 *
 * It reads what `GET /status` actually reports, which is verified: `app`, `serverVersion`, `isInit` and
 * `authMethods` (see `contracts/status-initialized.json`). None of those is a
 * [com.example.shelfplayer.core.model.ServerCapability]. `/status` says which *server* this is; it says
 * nothing about whether that server supports range downloads, ETags, websocket events or source-file
 * deletion.
 *
 * So the handshake persists the version and the authentication modes, and confirms **nothing**. That is
 * not a placeholder — PRODUCT_SPEC SYNC-001 requires an unknown capability to be treated as
 * unsupported, and reporting `PlaybackSession` as available because a version string looks recent enough
 * would be precisely the guess PRODUCT_SPEC 22.4 forbids. Each capability earns its place in the set
 * when its own endpoint has been captured and a probe for it exists; until then the features it gates
 * stay disabled with an explanation, which is the documented behaviour rather than a degraded one.
 *
 * A version-derived capability map is the obvious alternative and is rejected for a specific reason: a
 * self-hosted Audiobookshelf sits behind reverse proxies that break websockets, on filesystems that
 * break range requests, and behind configurations that disable endpoints the version nominally has.
 * Version is evidence about the software, not about the deployment.
 */
@Singleton
internal class AbsCapabilityResolver @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val errors: NetworkErrorMapper,
) : CapabilityResolver {

    override suspend fun resolve(serverId: ServerId, serverUrl: String): AppResult<ServerCapabilities> {
        val transport = resultOf(onError = errors::fromThrowable) {
            services.authService(serverUrl).status()
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (!response.isSuccessful) {
                    AppResult.Failure(errors.fromStatus(response.code()))
                } else {
                    capabilitiesOf(serverId, response.body())
                }
            }
        }
    }

    /**
     * PRODUCT_SPEC SYNC-001 — "missing expected fields produce a typed compatibility error rather than
     * a crash".
     *
     * The application marker is the one field whose absence is an error: without it there is no evidence
     * this is an Audiobookshelf server at all, and persisting a handshake for a host that might be a
     * captive portal would record a fiction. A missing `serverVersion` is *not* an error — the model
     * already carries it as nullable, because a server that does not report one is still a server.
     */
    private fun capabilitiesOf(serverId: ServerId, body: ServerStatusDto?): AppResult<ServerCapabilities> {
        if (body?.app != AUDIOBOOKSHELF) {
            return AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "That address answered, but it is not an Audiobookshelf server.",
                    missingField = "app",
                ),
            )
        }
        return AppResult.Success(
            ServerCapabilities(
                serverId = serverId,
                serverVersion = body.serverVersion?.takeIf(String::isNotBlank),
                // Deliberately empty. See the class comment: nothing `/status` reports is a capability,
                // and an unconfirmed capability is unsupported.
                supported = emptySet(),
                authMethods = body.authMethods.filter(String::isNotBlank),
            ),
        )
    }

    private companion object {
        const val AUDIOBOOKSHELF = "audiobookshelf"
    }
}
