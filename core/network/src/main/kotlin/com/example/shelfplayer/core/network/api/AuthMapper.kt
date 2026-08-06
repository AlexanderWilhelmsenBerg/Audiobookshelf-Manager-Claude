package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.auth.LibraryAccess
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

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
                userId = user.id?.takeIf(String::isNotBlank),
                username = username,
                role = toRole(user.type),
                access = LibraryAccess(
                    // Absent permissions mean no grant, not a full one: PRODUCT_SPEC 5.2's safe default
                    // is the restrictive one, and a server that sent no permissions block has told us
                    // nothing about what this account may see.
                    hasAllLibraryAccess = user.permissions?.accessAllLibraries ?: false,
                    accessibleLibraryIds = user.librariesAccessible.filter(String::isNotBlank).map(::LibraryId),
                    // Same safe default, and for a sharper reason: an account wrongly believed to see
                    // every item is an account whose sync is allowed to delete another account's books.
                    hasAllTagAccess = user.permissions?.accessAllTags ?: false,
                ),
            ),
        )
    }

    /**
     * PRODUCT_SPEC 5.2 — the same account, re-read, without its credentials.
     *
     * Shares [rejectionFor]'s account checks minus the token one: `POST /api/authorize` answers with
     * `user.token` and no `user.accessToken`, so requiring an access token here would reject every
     * response this endpoint gives. The caller already holds a working token — that is how it reached
     * this endpoint — and [AccountState] exists so that this response can never replace it.
     *
     * A disabled or locked account still fails, and that is the point of asking: PRODUCT_SPEC AUTH-004
     * wants the profile marked rather than left believing it is signed in.
     */
    fun toAccountState(user: UserDto?): AppResult<AccountState> = when {
        user == null -> AppResult.Failure(missingField("user"))
        user.username.isNullOrBlank() -> AppResult.Failure(missingField("user.username"))
        !user.isActive || user.isLocked -> AppResult.Failure(
            AppError.Authorization(summary = "This account has been disabled on the server."),
        )

        else -> AppResult.Success(
            AccountState(
                userId = user.id?.takeIf(String::isNotBlank),
                username = user.username,
                role = toRole(user.type),
                access = LibraryAccess(
                    hasAllLibraryAccess = user.permissions?.accessAllLibraries ?: false,
                    accessibleLibraryIds = user.librariesAccessible.filter(String::isNotBlank).map(::LibraryId),
                    hasAllTagAccess = user.permissions?.accessAllTags ?: false,
                ),
                progress = user.mediaProgress.mapNotNull(::toProgress),
            ),
        )
    }

    /**
     * `null` for an entry with no item id: a position that names no book cannot be stored against one,
     * and inventing a key for it would attach somebody's listening position to an arbitrary row.
     *
     * The unit conversion is the thing to be careful about. `currentTime` and `duration` are **seconds**
     * — `42.5` is forty-two and a half — while `lastUpdate` is milliseconds. Reading either as the other
     * silently misplaces every position in the library.
     */
    private fun toProgress(dto: MediaProgressDto): AccountProgress? {
        val bookId = dto.libraryItemId?.takeIf(String::isNotBlank) ?: return null
        return AccountProgress(
            bookId = LibraryItemId(bookId),
            position = dto.currentTime.seconds,
            duration = dto.duration.seconds,
            isFinished = dto.isFinished,
            updatedAt = Instant.ofEpochMilli(dto.lastUpdate ?: 0L),
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
