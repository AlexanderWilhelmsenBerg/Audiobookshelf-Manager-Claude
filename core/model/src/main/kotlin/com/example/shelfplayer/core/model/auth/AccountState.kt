package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.ProfileRole

/**
 * PRODUCT_SPEC 5.2 — what the server currently says about an account, asked again later.
 *
 * ### Why this is not an [AuthSession]
 *
 * The two carry almost the same fields and must not be the same type, because one of them carries
 * credentials and this one deliberately does not.
 *
 * `POST /api/authorize` — the endpoint that answers this — returns `user.token` and neither
 * `user.accessToken` nor `user.refreshToken`; that is captured, not assumed (`contracts/authorize.json`).
 * `user.token` is the pre-2.26 credential, and `/auth/refresh` does not accept it. Mapping this response
 * into an [AuthSession] would therefore hand a permission refresh the power to overwrite a good,
 * renewable token with a legacy one that works until it expires and then cannot be renewed — a session
 * that dies quietly, days later, for a reason nobody would connect to a permission check.
 *
 * Leaving tokens off the type makes that impossible rather than merely discouraged.
 *
 * ### What it is for
 *
 * PRODUCT_SPEC 5.2 requires a `403` to invalidate the permission cache and refresh the current user, and
 * the grant a profile was signed in with is otherwise never revisited. Without this, access granted or
 * revoked on the server stays invisible until the user signs out and back in — which a device run found
 * as a library that had been revoked and would not go away.
 */
data class AccountState(
    /** The server's own id for this account, when it sent one. Never invented (PRODUCT_SPEC 22.4). */
    val userId: String?,
    val username: String,
    /** Re-read every time: an account promoted or demoted on the server changes which actions are offered. */
    val role: ProfileRole,
    val access: LibraryAccess,
)
