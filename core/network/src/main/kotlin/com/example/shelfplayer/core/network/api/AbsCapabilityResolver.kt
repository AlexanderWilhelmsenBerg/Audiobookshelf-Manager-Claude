package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake, against `GET /status`.
 *
 * ### Why this confirms almost nothing
 *
 * It reads what `GET /status` actually reports, which is verified: `app`, `serverVersion`, `isInit` and
 * `authMethods` (see `contracts/status-initialized.json`). None of those is a
 * [com.example.shelfplayer.core.model.ServerCapability]. `/status` says which *server* this is; it says
 * nothing about whether that server supports range downloads, ETags, websocket events or source-file
 * deletion.
 *
 * So the handshake persists the version and the authentication modes, and confirms nothing *from
 * `/status`*. That is not a placeholder — PRODUCT_SPEC SYNC-001 requires an unknown capability to be
 * treated as unsupported, and reporting `PlaybackSession` as available because a version string looks
 * recent enough would be precisely the guess PRODUCT_SPEC 22.4 forbids. Each capability earns its place
 * in the set when its own endpoint has been captured and a probe for it exists; until then the features
 * it gates stay disabled with an explanation, which is the documented behaviour rather than a degraded
 * one.
 *
 * [ServerCapability.Websocket] is the first to earn it. It has its own probe — engine.io's opening
 * handshake — because the answer is a property of the deployment rather than of the version, which is
 * the same reason the rest are not inferred.
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
                    capabilitiesOf(serverId, response.body(), websocketOffered(serverUrl))
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
    /**
     * PRODUCT_SPEC SYNC-001 — the one capability this handshake can now actually confirm.
     *
     * engine.io's opening frame is `0{"sid":…,"upgrades":["websocket"],…}` and the `upgrades` list is
     * the answer (`contracts/socket-handshake.json`, observed 2026-08-06). It is a property of the
     * *deployment* rather than of the version, which is precisely why it is probed: a reverse proxy that
     * strips the upgrade runs the same Audiobookshelf as one that does not.
     *
     * Any failure means `false`, not an error. An unreachable socket endpoint is a server without the
     * capability as far as this app is concerned, and it must not stop a handshake that has already
     * learned the version and the authentication modes.
     */
    private suspend fun websocketOffered(serverUrl: String): Boolean {
        val transport = resultOf(onError = errors::fromThrowable) {
            services.authService(serverUrl).socketHandshake()
        }
        val body = (transport as? AppResult.Success)?.value?.takeIf { it.isSuccessful }?.body() ?: return false
        val frame = body.use { it.string() }
        // The frame is a numeric type followed by JSON. Only the JSON is of interest, and only one key
        // in it — parsing the envelope properly would mean modelling a transport this app does not yet
        // speak (PRODUCT_SPEC 22.4).
        return frame.substringAfter('{', "").contains(WEBSOCKET_UPGRADE)
    }

    private fun capabilitiesOf(
        serverId: ServerId,
        body: ServerStatusDto?,
        websocket: Boolean,
    ): AppResult<ServerCapabilities> {
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
                // Only what a probe confirmed. `/status` confirms nothing — see the class comment — so
                // the set is empty except for the websocket, which has its own handshake to ask.
                supported = if (websocket) setOf(ServerCapability.Websocket) else emptySet(),
                authMethods = body.authMethods.filter(String::isNotBlank),
            ),
        )
    }

    private companion object {
        const val AUDIOBOOKSHELF = "audiobookshelf"
        const val WEBSOCKET_UPGRADE = "websocket"
    }
}
