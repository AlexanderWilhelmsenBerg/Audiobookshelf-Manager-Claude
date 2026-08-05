package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileRole

/**
 * PRODUCT_SPEC AUTH-003 — a credential, never a `String`.
 *
 * `toString` is overridden because the default for a value class prints the wrapped value, and a
 * token reaches a log the moment anything interpolates the object holding it. PRODUCT_SPEC 14.5
 * forbids that, and the redacting logger cannot help with a string that was already built.
 */
@JvmInline
value class AuthToken(val value: String) {
    init {
        require(value.isNotBlank()) { "AuthToken must not be blank" }
    }

    override fun toString(): String = "AuthToken(<redacted>)"
}

/**
 * The result of a successful sign-in (PRODUCT_SPEC AUTH-001).
 *
 * ### Why two tokens and a third legacy one
 *
 * Verified against Audiobookshelf 2.36.0: `POST /login` returns `user.accessToken` for requests and
 * `user.refreshToken` to renew it. It *also* returns `user.token`, the pre-2.26 token, alongside
 * them. Servers old enough to answer only with `token` still exist, so [accessToken] falls back to it
 * during mapping — but a current server must use `accessToken`, because the legacy value is not
 * refreshable.
 *
 * [refreshToken] is nullable for a reason that is easy to get wrong: the server only puts it in the
 * response body when the request carries `x-return-tokens: true`. Otherwise it is `null` in the body
 * and set as an `HttpOnly` cookie, which a native client cannot read. A null here therefore means
 * "this session cannot be renewed", and `AUTH-004` has to send the user back to sign-in rather than
 * pretend a refresh is possible.
 */
data class AuthSession(
    val accessToken: AuthToken,
    val refreshToken: AuthToken?,
    val username: String,
    val role: ProfileRole,
    val accessibleLibraryIds: List<LibraryId>,
    val hasAllLibraryAccess: Boolean,
) {
    /** PRODUCT_SPEC AUTH-004 — a session with no refresh token dies at expiry and cannot be renewed. */
    val isRenewable: Boolean get() = refreshToken != null

    /**
     * PRODUCT_SPEC 5.2 — permission checks are answered from the server's grant, never inferred from
     * the role. An account can be an `admin` with every library, or a `user` restricted to two.
     */
    fun canAccess(libraryId: LibraryId): Boolean = hasAllLibraryAccess || libraryId in accessibleLibraryIds
}
