package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 / 5.2 / AUTH-004 — one cheap request that keeps three things current.
 *
 * `POST /api/authorize` answers with the account's permissions, its role, whether it is still enabled,
 * every listening position it has, **and every bookmark**. Captures against a real server confirmed the
 * last two (`contracts/me.json`, `contracts/me-with-bookmark.json`); before the first of them, the app
 * re-read the entire library to find out that one number had changed.
 *
 * So this replaces three separate problems with one call:
 *
 * - **A book played on another device.** Positions arrive here, and the shelf re-sorts. The alternative
 *   was a full sync: 491 requests on the library a device run used, to learn one number.
 * - **A grant changed on the server.** `refreshPermissions` stores it, which is what makes a revoked
 *   library leave the shelf without signing out.
 * - **An account disabled server-side.** The same call marks the profile as needing reauthentication.
 *
 * ### Why it is safe to run often
 *
 * One request, no writes to the server, and every local write it performs declines to overwrite
 * anything newer — see `LibraryRepository.writeProgress`. That is what lets the app run it on resume
 * and on a profile switch without a staleness window to tune.
 *
 * ### What it is not
 *
 * It is not a library sync. A book *added* on the server does not appear from this call, because the
 * account response says nothing about the catalogue. `RefreshLibraryUseCase` is still what finds new
 * books, and still costs what it costs.
 */
class SyncAccountUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    /**
     * PRODUCT_SPEC 11.1 — bookmarks ride on the same response, so they refresh on the same schedule.
     *
     * There is no per-book bookmark read on the server: they live on the user object. So this call is not
     * merely a convenient place to refresh them, it is the *only* place, and a profile switch or a resume
     * is exactly when a listener would notice a bookmark made on another device was missing.
     */
    private val bookmarkRepository: BookmarkRepository,
) {
    /**
     * Returns the number of positions written, or the failure that stopped it.
     *
     * No active profile is a success with nothing done rather than a failure: this runs on app resume,
     * and a first launch with no profile has nothing to sync and nothing to report.
     */
    suspend operator fun invoke(): AppResult<Int> {
        val profileId = profileRepository.activeProfileId() ?: return AppResult.Success(0)
        return invoke(profileId)
    }

    suspend operator fun invoke(profileId: ProfileId): AppResult<Int> {
        val account = authRepository.refreshPermissions(profileId)
        if (account is AppResult.Failure && account.error is AppError.Authentication) {
            return renewAndRetry(profileId, original = account)
        }
        return account.store(profileId)
    }

    /**
     * PRODUCT_SPEC AUTH-004 — an expired access token is renewed, not announced.
     *
     * This runs on every resume, which makes it the call most likely to be the first to meet an expired
     * token. Without the renewal it was also the call that told the user they had been signed out of a
     * profile that was working perfectly well — a device run reported exactly that, triggered by nothing
     * more than adding a second server and coming back to the shelf.
     *
     * Exactly one renewal and at most one retry, which is the same bound `RefreshLibraryUseCase` keeps
     * and for the same reason: "the app never loops login requests". A renewal that cannot happen has
     * already marked the profile inside `renewSession`, so the original failure is what comes back.
     */
    private suspend fun renewAndRetry(profileId: ProfileId, original: AppResult.Failure): AppResult<Int> {
        val renewed = authRepository.renewSession(profileId)
        val active = renewed is AppResult.Success && renewed.value == SessionStatus.Active
        if (!active) return AppResult.Failure(original.error)
        return authRepository.refreshPermissions(profileId).store(profileId)
    }

    /**
     * The permissions were stored by `refreshPermissions` itself; the positions and the bookmarks are left,
     * and each is written by the layer that owns its rows.
     *
     * The **count returned is the positions**, unchanged, because that is what every caller of this use case
     * already reports. A bookmark write that failed would not be visible in it — but it also cannot fail in a
     * way the positions did not, since both come from one response and one profile lookup, and the bookmark
     * write is a local replace with no second network call.
     */
    private suspend fun AppResult<com.example.shelfplayer.core.model.auth.AccountState>.store(
        profileId: ProfileId,
    ): AppResult<Int> = when (this) {
        is AppResult.Failure -> AppResult.Failure(error)
        is AppResult.Success -> {
            bookmarkRepository.writeAccountBookmarks(profileId, value.bookmarks)
            libraryRepository.writeProgress(profileId, value.progress)
        }
    }
}
