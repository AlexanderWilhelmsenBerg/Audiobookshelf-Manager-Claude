package com.example.shelfplayer.core.network.gateway

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library

/**
 * PRODUCT_SPEC 10.4 — every Audiobookshelf call goes through this adapter.
 *
 * The gateway returns `:core:model` types and [AppResult], never a raw response body, so that a
 * change in the server's wire format is contained here (PRODUCT_SPEC 2.8).
 *
 * ### What exists in Phase 0 and what does not
 *
 * PRODUCT_SPEC 22.4 forbids inventing endpoints, and PRODUCT_SPEC 22.5 requires a contract fixture
 * before relying on a response shape. Phase 0 has no server to contract-test against, so it declares
 * only the sub-APIs the fake gateway genuinely implements: [capabilities], [account] and [library].
 *
 * The remaining sub-APIs listed in PRODUCT_SPEC 10.4 — `PlaybackApi`, `ProgressApi`, `DownloadApi`,
 * `ManagementApi`, `UsersApi`, `EventApi` — are added in the phase that implements them, together
 * with the captured fixtures and MockWebServer contract tests that prove their shape. Declaring them
 * now as empty interfaces would look like coverage the repository does not have.
 */
interface AudiobookshelfGateway {
    val capabilities: CapabilityResolver

    val account: AccountApi

    val library: LibraryApi
}

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake.
 *
 * Implementations must treat an unreadable or unrecognized probe result as *unsupported*.
 */
interface CapabilityResolver {
    suspend fun resolve(): AppResult<ServerCapabilities>
}

/**
 * PRODUCT_SPEC 23 — "Get current user and permissions".
 *
 * Signing in is AUTH-001 and lands in Phase 1; this interface only reads the account a connection is
 * already established for, which is why Phase 0 can implement it against fixtures without touching
 * credentials.
 */
interface AccountApi {
    suspend fun currentServer(): AppResult<Server>

    suspend fun currentProfile(): AppResult<Profile>
}

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
