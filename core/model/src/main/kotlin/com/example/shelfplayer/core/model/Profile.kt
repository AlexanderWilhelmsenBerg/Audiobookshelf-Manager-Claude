package com.example.shelfplayer.core.model

import java.time.Instant

/**
 * PRODUCT_SPEC AUTH-002 — a saved server connection.
 *
 * The base URL is stored so the profile switcher can show which server a profile belongs to. No
 * credential material is ever part of this model (PRODUCT_SPEC AUTH-003).
 */
data class Server(
    val id: ServerId,
    val displayName: String,
    val baseUrl: String,
    val detectedVersion: String?,
    val isFixture: Boolean,
)

/**
 * PRODUCT_SPEC AUTH-002 — an account on a server.
 *
 * [id] is a locally generated stable id, deliberately independent of the username, so that renaming
 * an account on the server does not orphan its downloads, progress or settings overrides.
 */
data class Profile(
    val id: ProfileId,
    val serverId: ServerId,
    val username: String,
    val displayName: String,
    val role: ProfileRole,
    val requiresReauthentication: Boolean,
    val lastUsedAt: Instant?,
    val isFixture: Boolean,
    /**
     * PRODUCT_SPEC DL-001 — whether the server lets this account download.
     *
     * Here as well as on [com.example.shelfplayer.core.model.auth.LibraryAccess] because the two have
     * different readers: the grant travels with a sign-in and is what the sync applies, while a screen has a
     * profile and needs to know whether to offer a button. One column, two readers, rather than a screen
     * reaching for the sync's type.
     *
     * `false` until a sign-in or a permission refresh has said otherwise, which is the safe direction: an
     * offered download the server refuses is worse than a withheld one it would allow.
     */
    val canDownload: Boolean = false,
)

/**
 * PRODUCT_SPEC 5.1 — UI-facing role buckets.
 *
 * These are presentation concepts. The actual decision to show or run a privileged action is always
 * made from server-returned permissions and a capability probe, never from this enum alone.
 */
enum class ProfileRole {
    Listener,
    Editor,
    Manager,
    Admin,
}
