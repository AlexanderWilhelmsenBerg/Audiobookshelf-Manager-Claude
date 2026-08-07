package com.example.shelfplayer.core.network.gateway

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * PRODUCT_SPEC SYNC-002 / LIB-001 — events the server pushes, when it can.
 *
 * Deliberately not a member of [AudiobookshelfGateway]. The gateway is request/response; this is a
 * connection with a lifetime, and giving it the same shape would invite a caller to treat "no events"
 * as a failure. It is not: PRODUCT_SPEC LIB-001 requires REST refresh whenever the websocket is
 * unavailable, so an implementation that never emits anything is a correct one on a deployment whose
 * reverse proxy strips the upgrade.
 */
interface RealtimeConnection {
    /** Coarse state, for a UI that wants to say whether events are arriving. */
    val status: StateFlow<RealtimeStatus>

    /**
     * Connects as [profileId] and emits until the collector is cancelled.
     *
     * The flow is the connection's lifetime. Cancelling closes the socket, which is what keeps "open
     * only while something is listening" true without a stop call anyone could forget.
     */
    fun events(profileId: ProfileId): Flow<RealtimeEvent>
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

    /**
     * PRODUCT_SPEC 5.2 — "Get current user and permissions", over `POST /api/authorize`.
     *
     * Returns an [AccountState] and not an [AuthSession]: the response carries the legacy `user.token`
     * and no refresh token, so it is not a credential this app may adopt. See [AccountState].
     *
     * A `401` here means the token stopped working — revoked, expired, or the account deleted — and a
     * `403` or a disabled account means it works but the account may no longer do what it did. Both are
     * things AUTH-004 wants the profile marked for rather than discovered at the next silent failure.
     */
    suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState>

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
 * PRODUCT_SPEC 23 — "Get current user and permissions" is [AuthApi.currentAccount], and there is no
 * separate `AccountApi`.
 *
 * Phase 0 had one, with parameterless `currentServer()` and `currentProfile()`. That suited a gateway
 * serving one fixture profile and cannot serve a real client: there is no ambient "current" account when
 * several profiles on several servers coexist, and a permission refresh attributed to the wrong one would
 * write one account's grant over another's (PRODUCT_SPEC 5.2). Every replacement takes its server and its
 * credential explicitly.
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

    /**
     * PRODUCT_SPEC LIB-001 — "the home screen can render partial cached content while sync continues".
     *
     * [onBatch] is how that becomes true for a *large* library rather than only for a fast one. The
     * catalogue is minified, so every item needs its own fetch, and a 490-book library that only
     * appeared once all 490 had arrived left the shelf empty for minutes — which a device run duly
     * reported. Batches let the caller store what has arrived so far.
     *
     * It carries books only. Deletions and the profile's item visibility are decided from the *whole*
     * catalogue and stay with the returned snapshot, because a partial view is exactly the thing that
     * must not be allowed to drive a deletion.
     */
    /**
     * @param onBatch called with the catalogue rows first, then with each batch of expanded items, so
     *   the shelf is populated after one request rather than after N+1 (P1-31).
     * @param isUpToDate asked, per item, whether the stored copy already matches the server's
     *   `updatedAt` **and** already holds its tracks. Returning `true` skips that item's expanded
     *   fetch. The default answers `false`, which is the safe direction: a caller that does not know
     *   re-fetches rather than silently keeping a stale book.
     */
    suspend fun listBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        onBatch: suspend (List<BookSnapshot>) -> Unit = {},
        isUpToDate: suspend (LibraryItemId, Long?) -> Boolean = { _, _ -> false },
    ): AppResult<LibrarySnapshot>

    /**
     * PRODUCT_SPEC LIB-002 — "local cached results appear immediately; server search may enrich
     * results".
     *
     * This is the enrichment half. It finds books the cache does not hold — added since the last sync,
     * or matched on a field the local predicate does not index — and returns them as complete snapshots
     * the caller may store: a search hit arrives expanded, tracks included, so no follow-up fetch is
     * needed to make one playable.
     *
     * Books only. The endpoint also answers with author, series, narrator, genre and tag hits, and the
     * capture returned every one of those arrays empty, so their shapes are unverified (PRODUCT_SPEC
     * 22.4). Those axes stay local-only until a capture covers them.
     */
    suspend fun searchBooks(profileId: ProfileId, libraryId: LibraryId, query: String): AppResult<List<BookSnapshot>>
}
