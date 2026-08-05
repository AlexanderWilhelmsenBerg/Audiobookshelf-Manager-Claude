package com.example.shelfplayer.core.network.gateway

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess

/**
 * PRODUCT_SPEC 9.3 / 5.2 — how the gateway learns which server a profile talks to, and with what.
 *
 * ### Why this exists at all
 *
 * `LibraryApi.listLibraries(profileId)` has to reach a server. The profile knows which one; the gateway
 * does not, and must not guess. `TokenProvider` cannot answer it either — it supplies a token for the
 * *active* profile and no address at all — so the base URL was always going to need a seam. Returning
 * the credential and the grant through the same seam is then free, and it removes the ambient state that
 * a second seam would have left in place.
 *
 * It is an interface here and implemented in `:data:auth` for the same reason
 * [com.example.shelfplayer.core.database.DatabaseTransactionRunner] is: the storage lives on the far side
 * of a boundary that PRODUCT_SPEC 9.3 keeps closed, and widening the dependency for a lookup would open
 * it. `:core:network` names no database and no credential-store type.
 */
interface ProfileConnectionResolver {
    /**
     * The connection for [profileId], or `null` when there is no usable one.
     *
     * `null` covers a profile that is not saved, one whose session has expired, and one whose stored
     * token could no longer be decrypted. The caller cannot tell them apart and does not need to: each
     * one means "this profile cannot make a request right now", and `AUTH-004` has already marked the
     * profile in the two cases where that matters.
     */
    suspend fun connectionFor(profileId: ProfileId): ProfileConnection?
}

/**
 * Everything a request on one profile's behalf needs.
 *
 * @property access the server's library grant, carried alongside the credential rather than fetched
 *   separately, so a call cannot be made with the right token and the wrong permissions. PRODUCT_SPEC 5.2
 *   requires the check; having the grant here is what lets the gateway apply it before a single row
 *   reaches Room.
 */
data class ProfileConnection(
    val profileId: ProfileId,
    val serverId: ServerId,
    val serverUrl: String,
    val accessToken: AuthToken,
    val access: LibraryAccess,
)
