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
    /**
     * PRODUCT_SPEC MGR-001 / MGR-002 / MGR-005 — the three management grants, as the server reports them.
     *
     * All `false` until a sign-in or a permission refresh says otherwise, and deliberately so: PRODUCT_SPEC
     * principle 4 enforces permissions twice, and the second enforcement is only meaningful if an unknown
     * grant reads as absent. An offered action the server refuses is a `403` after the user believed they
     * had changed something — much worse than a withheld action they could have had.
     */
    val canUpdate: Boolean = false,
    val canDelete: Boolean = false,
    val canUpload: Boolean = false,
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
    ;

    companion object {
        /**
         * PRODUCT_SPEC 5.1 — the server's account type, as this app's role bucket.
         *
         * Audiobookshelf reports `root`, `admin`, `user` or `guest`. The mapping is *this app's opinion* and
         * not a server concept, which is why the type is stored verbatim and translated here rather than
         * being collapsed on the way in.
         *
         * **A type this app has never heard of maps to [Listener].** A future Audiobookshelf type must not be
         * able to promote anybody by being unrecognised, and the enum is a presentation bucket anyway —
         * every privileged action is gated on the grant and the capability, never on this alone.
         */
        fun ofAccountType(type: String): ProfileRole = when (type.lowercase()) {
            "root", "admin" -> Admin
            // `user` is the ordinary account. What it may actually *do* comes from its permissions, so it
            // starts here and the grants decide; there is no server type that means "editor".
            "user", "guest", "" -> Listener
            else -> Listener
        }
    }
}
