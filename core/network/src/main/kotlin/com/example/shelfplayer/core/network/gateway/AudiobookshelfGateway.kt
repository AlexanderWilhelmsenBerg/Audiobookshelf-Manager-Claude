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
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 10.4 — every Audiobookshelf call goes through this adapter.
 *
 * The gateway returns `:core:model` types and [AppResult], never a raw response body, so that a
 * change in the server's wire format is contained here (PRODUCT_SPEC 2.8).
 *
 * ### What exists and what does not
 *
 * PRODUCT_SPEC 22.4 forbids inventing endpoints, and PRODUCT_SPEC 22.5 requires a contract fixture
 * before relying on a response shape. Only the sub-APIs whose contracts are captured are declared:
 * [auth], [capabilities], [library], [playback] and [bookmarks] — the last of those joined the list on
 * 2026-08-13, when a capture finally produced the shape it had been waiting on since wave 2.
 *
 * [downloads] joined on 2026-08-14, when the capture of `/api/items/{id}/file/{fileId}` answered the
 * questions the downloader needed — ranges, validators, and what an unauthenticated request gets.
 *
 * [management] joined on 2026-08-15, once ten management fixtures existed. The remaining sub-APIs listed
 * in PRODUCT_SPEC 10.4 — `ProgressApi`, `UsersApi`, `EventApi` — are added in the phase that implements
 * them, together with the captured fixtures and MockWebServer contract tests that prove their shape.
 * Declaring them now as empty interfaces would look like coverage the repository does not have.
 */
interface AudiobookshelfGateway {
    val auth: AuthApi

    val capabilities: CapabilityResolver

    val library: LibraryApi

    val playback: PlaybackApi

    val bookmarks: BookmarkApi

    /**
     * PRODUCT_SPEC DL-001 — fetching audio files, added in the phase that implements them.
     *
     * The list above says a sub-API arrives "together with the captured fixtures and MockWebServer
     * contract tests that prove their shape". `contracts/item-file.json` was captured on 2026-08-14 and
     * settled the three questions the downloader is built on: ranges are honoured, an `ETag` is sent, and
     * an unauthenticated request is refused.
     */
    val downloads: DownloadApi

    /**
     * PRODUCT_SPEC EPIC MGR — the writes that change somebody else's library, added in Phase 5.
     *
     * The last sub-API to arrive, and deliberately: every other one either reads, or writes something the
     * user can put back. This one can lose a household's metadata.
     */
    val management: ManagementApi
}

/**
 * PRODUCT_SPEC 11.1 — the three bookmark writes. There is no read, and that is not an omission.
 *
 * Audiobookshelf stores bookmarks on the **user**, so `GET /api/me` and `POST /api/authorize` both return
 * every one of them across every book, and both are calls the app already makes. They arrive as
 * [com.example.shelfplayer.core.model.auth.AccountState.bookmarks]; adding a read route here would be a
 * second path to the same data with nothing to gain by it.
 *
 * ### A bookmark is its position
 *
 * There is no id on the wire. `DELETE /api/me/item/{id}/bookmark/{seconds}` is addressed to the number of
 * seconds, so [at] is the identity in every method here — which also means two bookmarks in the same
 * second cannot both exist, and [create] on an occupied second overwrites rather than failing.
 */
interface BookmarkApi {
    /** Creates a bookmark at [at] and returns what the server actually stored. */
    suspend fun create(profileId: ProfileId, bookId: LibraryItemId, at: Duration, title: String): AppResult<Bookmark>

    /**
     * Renames the bookmark at [at]. The position does not move — a bookmark *is* its position, so
     * changing one means deleting and creating.
     */
    suspend fun rename(profileId: ProfileId, bookId: LibraryItemId, at: Duration, title: String): AppResult<Bookmark>

    suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit>
}

/**
 * PRODUCT_SPEC PLAY-001 — opening a playback session.
 *
 * Four routes, all captured. What is **not** here is `POST /api/session/local`: it is captured too, and
 * it is the wrong route for a queue — it answers `200` with an empty body, so a drain cannot tell an
 * accepted session from an ignored one. [syncOfflineSessions] uses the batch route even for one session.
 */
interface PlaybackApi {
    /**
     * Asks the server to open a session for [bookId] and tells us how to play it.
     *
     * The profile is explicit for the reason every gateway call's is: the session is recorded against a
     * user account on a server, and opening one for the wrong profile would attribute a stranger's
     * listening to them (PRODUCT_SPEC 5.2).
     *
     * The returned [com.example.shelfplayer.core.model.library.PlaybackSession] carries **absolute**
     * track URLs. Resolution happens here rather than in the player because the server sends a
     * server-relative path and only the gateway knows which server the profile is on; a relative path
     * escaping this boundary is a path waiting to be resolved against the wrong one.
     */
    suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession>

    /**
     * PRODUCT_SPEC PLAY-004 — sends a position against a session the server opened.
     *
     * `AppResult<Unit>` because the server answers `200` with a `text/plain` `OK` and nothing else. There
     * is no confirmation to reconcile against: success means accepted, and what the server actually
     * *stored* is a separate read of `/api/me`.
     */
    suspend fun syncSession(profileId: ProfileId, sessionId: String, progress: SessionProgress): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-004 — the last position, and the session is finished.
     *
     * Separate from [syncSession] rather than a flag on it: closing is what the server counts as the end
     * of a listening session, and a caller that muddled the two would either never close one or close it
     * on every tick.
     */
    suspend fun closeSession(profileId: ProfileId, sessionId: String, progress: SessionProgress): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-005 — uploads sessions recorded while offline, and reports each one's fate.
     *
     * The batch route even for a single session, because the single-session route reports nothing a queue
     * can act on. Each [OfflineSessionResult] separates "stored" from "position applied", and only the
     * first is what an outbox may drain on.
     */
    suspend fun syncOfflineSessions(
        profileId: ProfileId,
        sessions: List<OfflineSession>,
    ): AppResult<List<OfflineSessionResult>>

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit", in both directions.
     *
     * Not a session. A session records listening; this states a fact about the book, which is what a user
     * ticking or unticking *Finished* is doing. It also has to work on a book that is not playing, and
     * un-marking one must not be recorded as having listened to anything.
     *
     * [position] travels with it because the route is a progress PATCH and takes both. Marking finished
     * sends the book's end; un-marking sends the position the listener is actually at, so a book that comes
     * back from finished does not also come back from the beginning.
     */
    suspend fun setFinished(
        profileId: ProfileId,
        bookId: LibraryItemId,
        isFinished: Boolean,
        position: Duration,
    ): AppResult<Unit>
}

/**
 * PRODUCT_SPEC PLAY-001 / 14.5 — how this install describes itself when it opens a session.
 *
 * The server records the description against the session and shows it back to the user, so the values
 * are the ones a person would use to recognise their own phone — and nothing more. There is no
 * advertising id, no `ANDROID_ID` and no hardware serial here: [PlaybackDevice.deviceId] is a random
 * value this install generated for itself.
 *
 * An interface in `:core:network` implemented in `:data:settings`, for the same reason
 * [ProfileConnectionResolver] is: the id is persisted, and PRODUCT_SPEC 9.3 does not let this module
 * name a store.
 */
fun interface PlaybackDeviceIdentity {
    suspend fun describe(): PlaybackDevice
}

/** @property deviceId a random per-install identifier — see [PlaybackDeviceIdentity]. */
data class PlaybackDevice(
    val clientName: String,
    val clientVersion: String,
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val sdkVersion: Int,
)

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
    /**
     * @param accessToken the credential to run the *authenticated* probes with, or `null` when the
     *   handshake is running before a sign-in. A handshake without a token is not an error and not a
     *   degraded mode: `GET /status` needs no credential, so the version and the authentication modes
     *   still arrive. The probes that do need one simply do not run, and what they would have confirmed
     *   stays unconfirmed — which SYNC-001 already defines as unsupported.
     */
    suspend fun resolve(
        serverId: ServerId,
        serverUrl: String,
        accessToken: AuthToken? = null,
    ): AppResult<ServerCapabilities>
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
     * @param cached what the caller already holds, which decides what is skipped and what is fetched
     *   first. The default knows nothing, which is the safe direction in both respects: everything is
     *   re-fetched, in catalogue order.
     */
    suspend fun listBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        onBatch: suspend (List<BookSnapshot>) -> Unit = {},
        cached: CachedLibrary = CachedLibrary.Unknown,
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

    /**
     * PRODUCT_SPEC MGR-001 / MGR-004 — one item, expanded, after something changed it.
     *
     * The same request the catalogue sync makes per item, addressed at a single book. It exists because
     * every management operation ends with "and now refresh the affected local entity", and the
     * alternative is a whole library sync to observe one edit.
     *
     * The library is not a parameter: an item knows which library it belongs to, and a caller that had to
     * supply one could supply the wrong one. The response's own `libraryId` is used, which is also what
     * makes this safe to call after an edit that moved nothing.
     */
    suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot>
}

/**
 * PRODUCT_SPEC LIB-001 — what the caller already holds, so a sweep can skip work and reorder the rest.
 *
 * One object rather than two lambdas on [LibraryApi.listBooks] because they are one thing: both are
 * answered from the same read of the local cache, both are supplied together or not at all, and a
 * caller that knew one but not the other would be in an incoherent state. It also stops the signature
 * growing a parameter every time the sweep learns to use another local fact.
 *
 * Neither method suspends. Both are answered from collections the caller materialised before the sweep
 * started — a per-item database round trip inside the loop would cost more than the request it saves.
 */
interface CachedLibrary {
    /**
     * Whether the stored copy already matches the server's `updatedAt` **and** already holds its
     * tracks, in which case the expanded fetch is skipped.
     *
     * Both sides must be known. "I cannot tell" has to mean "check", or an item that changed silently
     * stays stale for the life of the cache.
     */
    fun isUpToDate(id: LibraryItemId, updatedAt: Long?): Boolean

    /**
     * Whether this is a book the user has started and not finished — the *Continue listening* shelf.
     *
     * These are expanded first. It is a pure reordering: the same items are fetched, and no request is
     * added or removed, so it cannot make a refresh slower. What it changes is which shelf is correct
     * soonest, and Continue listening is the one a returning user actually opens the app for.
     */
    fun isInProgress(id: LibraryItemId): Boolean

    companion object {
        /** A cache that knows nothing: re-fetch everything, in the order the server listed it. */
        val Unknown = object : CachedLibrary {
            override fun isUpToDate(id: LibraryItemId, updatedAt: Long?): Boolean = false

            override fun isInProgress(id: LibraryItemId): Boolean = false
        }
    }
}

/**
 * PRODUCT_SPEC EPIC MGR — the operations that change a server's library.
 *
 * ### Why the whole updated book comes back
 *
 * MGR-001 requires that "on success, Room updates immediately and then refreshes from server", and the
 * `PATCH` response *is* the refresh: it carries the entire item as the server now holds it. A follow-up
 * `GET` would ask for data already in hand, and would open a window in which the two could disagree — and
 * on this endpoint that window is real, because a save triggers a metadata-file write and a socket
 * broadcast on the server.
 *
 * ### Why permission is not checked here
 *
 * It is checked twice, and neither time is here (PRODUCT_SPEC principle 4). The domain layer refuses the
 * action before it is offered, and the server refuses it again with a `403`. This layer's job is to make
 * the request faithfully and report what came back — a third check here would be a third place for the
 * three to disagree.
 */
interface ManagementApi {
    /**
     * PRODUCT_SPEC MGR-001 — save the changed metadata fields, and return the item the server now holds.
     *
     * @param changed which fields to send. **Not a hint.** `authors` and `series` are replacements on this
     *   endpoint: sending either array removes every entry it does not contain, so a payload built from
     *   anything wider than the user's actual edits would delete data. An empty set is a no-op rather than
     *   a request, because a `PATCH` with an empty body still bumps the item's `updatedAt`.
     */
    suspend fun updateMetadata(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<BookSnapshot>

    /**
     * PRODUCT_SPEC MGR-002 — replace the cover, and return the item the server now holds.
     *
     * The bytes are passed rather than a URI, so that whoever reads the picker owns the decoding, the
     * validation and the memory — and this layer owns only the request. [CoverUpload.mimeType] is what the
     * filename is synthesised from, because the server reads the *extension* and not the content type.
     */
    suspend fun uploadCover(profileId: ProfileId, bookId: LibraryItemId, image: CoverUpload): AppResult<BookSnapshot>

    /** PRODUCT_SPEC MGR-002 — remove the cover. The item keeps everything else. */
    suspend fun removeCover(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot>
}

/**
 * PRODUCT_SPEC MGR-002 — an image the user chose, already validated, ready to send.
 *
 * @property mimeType one of the four the server accepts. Validation happens before this type exists, so a
 *   value here is a promise that the decode succeeded and the dimensions and size were checked.
 */
data class CoverUpload(val bytes: ByteArray, val mimeType: String) {
    /**
     * `equals` and `hashCode` are by identity, deliberately.
     *
     * A data class over a `ByteArray` gets reference equality for free and structural equality never, which
     * surprises readers both ways. Comparing two multi-megabyte images is not something any caller wants;
     * saying so is better than leaving the generated version to imply otherwise.
     */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
