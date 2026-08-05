package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken

/**
 * Turns the `/login` envelope into [AuthSession].
 *
 * PRODUCT_SPEC SYNC-001: a field the server did not send is an [AppError.ApiCompatibility], never a
 * crash and never a silently-empty value. Everything this returns has been observed on a real
 * server; nothing is inferred.
 */
internal object AuthMapper {

    fun toSession(user: UserDto?): AppResult<AuthSession> {
        rejectionFor(user)?.let { return AppResult.Failure(it) }

        // Every dereference below is guarded by `rejectionFor`, which has already returned for each
        // of these being absent. Collecting the checks there rather than interleaving them with the
        // mapping keeps one list of everything that makes a sign-in response unusable.
        checkNotNull(user)
        val access = checkNotNull(accessTokenOf(user))
        val username = checkNotNull(user.username)

        return AppResult.Success(
            AuthSession(
                accessToken = AuthToken(access),
                // Absent means "not renewable", which is a real state rather than an error: the
                // caller decides whether to re-prompt. See AuthSession.isRenewable.
                refreshToken = user.refreshToken?.takeIf(String::isNotBlank)?.let(::AuthToken),
                username = username,
                role = toRole(user.type),
                accessibleLibraryIds = user.librariesAccessible.filter(String::isNotBlank).map(::LibraryId),
                hasAllLibraryAccess = user.permissions?.accessAllLibraries ?: false,
            ),
        )
    }

    /**
     * Every reason a sign-in response cannot be used, in one place.
     *
     * `null` means the response is usable. Ordering matters only in which error the caller sees
     * first; each condition is independent.
     */
    private fun rejectionFor(user: UserDto?): AppError? = when {
        user == null -> missingField("user")
        accessTokenOf(user) == null -> missingField("user.accessToken")
        user.username.isNullOrBlank() -> missingField("user.username")
        !user.isActive || user.isLocked ->
            AppError.Authorization(summary = "This account has been disabled on the server.")

        else -> null
    }

    /**
     * `accessToken` first, `token` only as a fallback.
     *
     * A server new enough to send both must be used with `accessToken`: the legacy value is not
     * accepted by `/auth/refresh`, so preferring it would produce a session that works until it
     * expires and then cannot be renewed.
     */
    private fun accessTokenOf(user: UserDto): String? =
        user.accessToken?.takeIf(String::isNotBlank) ?: user.token?.takeIf(String::isNotBlank)

    /**
     * PRODUCT_SPEC 5.1 — an unrecognized type is the *least* privileged bucket, not the most.
     *
     * A future server type this build has never heard of must not be granted management actions by
     * accident, so the fallback is [ProfileRole.Listener] rather than anything higher.
     */
    private fun toRole(type: String?): ProfileRole = when (type?.lowercase()) {
        "root", "admin" -> ProfileRole.Admin
        "user" -> ProfileRole.Listener
        else -> ProfileRole.Listener
    }

    private fun missingField(field: String): AppError = AppError.ApiCompatibility(
        summary = "This server's sign-in response is missing information this app needs.",
        missingField = field,
    )
}
