package com.example.shelfplayer.core.network.gateway

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library

/**
 * PRODUCT_SPEC 10.4 — every Audiobookshelf call goes through this adapter.
 *
 * The gateway returns `:core:model` types and [AppResult], never a raw response body, so that a
 * change in the server's wire format is contained here (PRODUCT_SPEC 2.8).
 *
 * ### What exists and what does not
 *
 * PRODUCT_SPEC 22.4 forbids inventing endpoints, and PRODUCT_SPEC 22.5 requires a contract fixture
 * before relying on a response shape. Only the three sub-APIs whose contracts are captured are declared:
 * [auth], [capabilities] and [library].
 *
 * The remaining sub-APIs listed in PRODUCT_SPEC 10.4 — `PlaybackApi`, `ProgressApi`, `DownloadApi`,
 * `ManagementApi`, `UsersApi`, `EventApi` — are added in the phase that implements them, together
 * with the captured fixtures and MockWebServer contract tests that prove their shape. Declaring them
 * now as empty interfaces would look like coverage the repository does not have.
 */
interface AudiobookshelfGateway {
    val auth: AuthApi

    val capabilities: CapabilityResolver

    val library: LibraryApi
}

/**
 * PRODUCT_SPEC AUTH-001 / AUTH-004 — establishing and renewing a session.
 *
 * Every call takes the server URL explicitly rather than reading it from injected state, because a
 * profile switch must not be able to send one server's credentials to another (PRODUCT_SPEC 5.2).
 * The caller holds the server a credential belongs to; the gateway never guesses.
 *
 * Tokens cross this boundary as [AuthToken], never as `String`, so a credential cannot be logged by
 * a stray interpolation (PRODUCT_SPEC 14.5).
 */
interface AuthApi {
    /**
     * Confirms a URL is an Audiobookshelf server and reports its version.
     *
     * `AUTH-001` needs this before asking for a password: an arbitrary host that answers 200 is not
     * a server, and the user should learn that before typing a credential into it.
     */
    suspend fun probe(serverUrl: String): AppResult<ServerProbe>

    suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<AuthSession>

    /**
     * PRODUCT_SPEC AUTH-004 — renews a session without re-prompting.
     *
     * Only callable with a session whose `isRenewable` is true; a session whose refresh token the
     * server withheld cannot be renewed and the caller must sign in again.
     */
    suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession>

    suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit>
}

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake.
 *
 * Implementations must treat an unreadable or unrecognized probe result as *unsupported*.
 *
 * The server is named explicitly for the same reason every [AuthApi] call names it: a client that
 * holds several profiles on several servers has no single ambient "current server", and resolving one
 * implicitly is how a handshake gets attributed to the wrong connection.
 */
interface CapabilityResolver {
    suspend fun resolve(serverId: ServerId, serverUrl: String): AppResult<ServerCapabilities>
}

/**
 * PRODUCT_SPEC 23 — "Get current user and permissions" is *not* declared here, on purpose.
 *
 * Phase 0 had an `AccountApi` with parameterless `currentServer()` and `currentProfile()`. That suited a
 * gateway serving one fixture profile and cannot serve a real client: there is no ambient "current"
 * account when several profiles on several servers coexist, and a permission refresh attributed to the
 * wrong one would write one account's grant over another's (PRODUCT_SPEC 5.2). Its only consumer was the
 * demo-library bootstrapper, which real sign-in replaces.
 *
 * `POST /api/authorize` is the endpoint a permission refresh will use — PRODUCT_SPEC 5.2 requires one
 * after a `403` — and its response is already captured in `contracts/authorize.json`. It is added when
 * that refresh is implemented, taking an explicit profile. Note for whoever does: the captured response
 * carries `user.token` only, with no `accessToken` and no `refreshToken`, so `AuthMapper.toSession` is the
 * wrong mapping for it — using it would store the legacy, non-refreshable token as the access token.
 */

/** PRODUCT_SPEC 23 — "Get accessible libraries" and "Get library items". */
interface LibraryApi {
    /**
     * The libraries [profileId] may see.
     *
     * The profile is explicit rather than implicit so that a gateway can never return content for
     * the wrong account after a profile switch (PRODUCT_SPEC 5.2).
     */
    suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>>

    suspend fun listBooks(profileId: ProfileId, libraryId: LibraryId): AppResult<List<BookSnapshot>>
}
