package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AuthToken
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
 * [ServerCapability.MatchProvider] is the second, and the only one from EPIC MGR that will ever be here.
 * The rest of the management endpoints cannot be probed: asking whether metadata may be edited means
 * editing it, and asking whether an item may be deleted means deleting it. What gates those is the
 * account's grant rather than the server's capability, which is a different question with a different
 * owner — see [com.example.shelfplayer.core.model.ManagementAction].
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

    override suspend fun resolve(
        serverId: ServerId,
        serverUrl: String,
        accessToken: AuthToken?,
    ): AppResult<ServerCapabilities> {
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
                    capabilitiesOf(
                        serverId = serverId,
                        body = response.body(),
                        websocket = websocketOffered(serverUrl),
                        matchProviders = matchProvidersOffered(serverUrl, accessToken),
                    )
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

    /**
     * PRODUCT_SPEC MGR-003 — the only management capability a probe can honestly answer.
     *
     * `GET /api/search/providers` is read-only, has no side effects and needs no privilege beyond a
     * session. Every other management endpoint either performs the operation it would be asked about, or
     * refuses on a permission this app already reads from `me.json` — which is why the rest of EPIC MGR
     * is gated by [com.example.shelfplayer.core.model.ManagementAction] on the *profile's* grants rather
     * than appearing in this set. A capability is a property of the server; a grant is a property of the
     * account, and conflating them would give two accounts on one server the same answer.
     *
     * The bar is a provider with a usable `value`. A `404` from an older server, a `401`, an unparseable
     * body and an empty list all mean the same thing here — not confirmed — which is what lets this ship
     * ahead of its fixture (PRODUCT_SPEC 22.5): the only shape that enables anything is the exact one
     * `docs/api-compatibility.md` records, and everything else fails closed.
     *
     * No token means no probe. That is the pre-sign-in handshake, not a failure.
     */
    private suspend fun matchProvidersOffered(serverUrl: String, accessToken: AuthToken?): Boolean {
        val bearer = accessToken?.value?.takeIf(String::isNotBlank)?.let(::bearerOf) ?: return false
        val transport = resultOf(onError = errors::fromThrowable) {
            services.authService(serverUrl).metadataProviders(bearer)
        }
        val body = (transport as? AppResult.Success)?.value?.takeIf { it.isSuccessful }?.body() ?: return false
        return body.providers?.books.orEmpty().any { !it.value.isNullOrBlank() }
    }

    private fun capabilitiesOf(
        serverId: ServerId,
        body: ServerStatusDto?,
        websocket: Boolean,
        matchProviders: Boolean,
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
                // the set holds exactly the two capabilities that have a probe of their own to ask.
                supported = buildSet {
                    if (websocket) add(ServerCapability.Websocket)
                    if (matchProviders) add(ServerCapability.MatchProvider)
                },
                authMethods = body.authMethods.filter(String::isNotBlank),
            ),
        )
    }

    private companion object {
        const val AUDIOBOOKSHELF = "audiobookshelf"
        const val WEBSOCKET_UPGRADE = "websocket"
    }
}
