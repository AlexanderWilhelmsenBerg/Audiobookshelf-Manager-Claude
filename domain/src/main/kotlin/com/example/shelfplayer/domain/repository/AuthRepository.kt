package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus

/**
 * PRODUCT_SPEC AUTH-001…AUTH-004 — establishing, restoring, renewing and ending a session.
 *
 * Nothing here returns a token. A credential enters the process at [signIn], goes straight into
 * encrypted storage, and is thereafter referenced only by [ProfileId] (PRODUCT_SPEC AUTH-003). A
 * ViewModel that could hold an `AuthToken` is a ViewModel that can put one in a saved-state bundle, so
 * the type never crosses this boundary.
 *
 * Every operation names its [ProfileId] explicitly. There is no "current profile" parameterless
 * variant, because the one bug this whole layer exists to prevent is acting on the wrong account
 * (PRODUCT_SPEC 5.2, product priority 4).
 */
interface AuthRepository {

    /**
     * PRODUCT_SPEC AUTH-001 / 6.1 steps 3-4 — normalize the address and ask the server who it is.
     *
     * Read-only and credential-free: this runs before the password prompt so that a user who mistyped
     * a host learns it from a "that is not an Audiobookshelf server" message rather than by sending a
     * password to it.
     */
    suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate>

    /**
     * PRODUCT_SPEC AUTH-001 — exchange credentials for a stored session and a saved profile.
     *
     * On success the server and profile rows exist, the token is encrypted on disk, the profile is the
     * active one, and [password] has been used and dropped — PRODUCT_SPEC AUTH-001 requires it never
     * to be persisted. On failure nothing is written at all: a rejected sign-in must not leave a
     * half-created profile behind for the user to wonder about.
     *
     * Signing the same account in again reuses its existing [ProfileId], so a reauthentication keeps
     * that profile's downloads and progress (PRODUCT_SPEC AUTH-004).
     */
    suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile>

    /**
     * Makes [profileId]'s stored credential the one outgoing requests use.
     *
     * Called on a cold start and on a profile switch. [SessionStatus.ReauthenticationRequired] means
     * there is nothing usable stored — the profile stays saved and browsable from cache
     * (PRODUCT_SPEC AUTH-004, 6.3).
     */
    suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus>

    /**
     * PRODUCT_SPEC AUTH-004 — extends the session without asking the user.
     *
     * Attempts exactly one renewal. [SessionStatus.ReauthenticationRequired] is returned — and the
     * profile marked — when the stored session had no refresh token, when the server refused the one it
     * had, or when the refreshed session came back unusable. There is no retry and no second attempt:
     * "the app never loops login requests" is a requirement, and a renewal that failed once will fail
     * again for the same reason.
     *
     * A failed renewal never removes the profile and never touches its downloads or local progress.
     */
    suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus>

    /**
     * PRODUCT_SPEC AUTH-004 — the one retry after renewal was also rejected.
     *
     * This does not perform another network renewal. It clears the now-proven-unusable local credentials
     * and marks the saved profile so the UI can request sign-in without losing cached user data.
     */
    suspend fun requireReauthentication(profileId: ProfileId): AppResult<Unit>

    /**
     * PRODUCT_SPEC 5.2 — re-reads the account's permissions from the server and stores them.
     *
     * The grant a profile was signed in with is otherwise never revisited, so access granted or revoked
     * on the server stays invisible until the user signs out and back in. The requirement names two
     * moments this must happen — after a `403`, and before a destructive request whose cached permissions
     * are over five minutes old — and a profile switch is a third, because the account whose view is about
     * to fill the screen is exactly the one whose grant should not be stale.
     *
     * Returns everything the server said about the account, which is more than the grant: the same
     * response carries every listening position it has. The caller decides what to do with those — see
     * `SyncAccountUseCase`, which is what makes a book played on another device appear without a
     * 491-request library sync.
     *
     * A failure leaves the stored grant untouched unless the server actively rejected the session,
     * which is marked instead; an unreachable server is not a permission change.
     */
    suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState>

    /**
     * PRODUCT_SPEC AUTH-004 — ends the session but keeps the profile.
     *
     * The stored token is dropped both on disk and in memory, and the profile is marked as requiring
     * reauthentication. The server is told when it can be reached, but a failed `POST /logout` does not
     * keep the local session alive: a user who asked to sign out is signed out locally regardless.
     */
    suspend fun signOut(profileId: ProfileId): AppResult<Unit>

    /**
     * PRODUCT_SPEC AUTH-002 — deletes the profile and everything keyed to it.
     *
     * Distinct from [signOut], and on this interface rather than on [ProfileRepository] because it has
     * to destroy a credential: a profile row removed while its encrypted token stayed on disk is a
     * credential with no owner and no way to reach it.
     */
    suspend fun removeProfile(profileId: ProfileId): AppResult<Unit>
}
