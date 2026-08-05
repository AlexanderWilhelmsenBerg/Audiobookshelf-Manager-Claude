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
