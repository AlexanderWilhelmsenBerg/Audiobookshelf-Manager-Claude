package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [AuthApi], against contracts verified on Audiobookshelf 2.36.0.
 *
 * Every response goes through [NetworkErrorMapper] so a status becomes a typed [AppError] rather
 * than an exception: `401` on sign-in is a wrong password, `401` on refresh is an expired session,
 * and the two lead the user to different places (PRODUCT_SPEC AUTH-004).
 *
 * `resultOf` is the single exception boundary here, and it rethrows cancellation.
 */
@Singleton
internal class AbsAuthApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val errors: NetworkErrorMapper,
) : AuthApi {

    override suspend fun probe(serverUrl: String): AppResult<ServerProbe> = call(serverUrl) { service ->
        service.status().toResult { body ->
            AppResult.Success(
                ServerProbe(
                    // A host that answers 200 with something else is not a server. Checking the
                    // marker before trusting the payload is what stops AUTH-001 from accepting an
                    // arbitrary URL that merely responds.
                    isAudiobookshelf = body?.app == AUDIOBOOKSHELF,
                    serverVersion = body?.serverVersion,
                    isInitialized = body?.isInit ?: false,
                    authMethods = body?.authMethods.orEmpty(),
                ),
            )
        }
    }

    /**
     * PRODUCT_SPEC AUTH-001 — a rejected credential is not an expired session.
     *
     * Both are `401`, and [NetworkErrorMapper] cannot tell them apart: it sees a status, not what was
     * asked. Only the call site knows, and here the caller has just typed a username and a password, so
     * the failure is about *those* — not about a profile needing to sign in again, which was the message
     * a device run saw after typing a username that does not exist on the server.
     *
     * `requiresReauthentication = false` for the same reason: there is no profile to mark. A sign-in that
     * is rejected must not leave a mark on the account the user happened to be signed in as before.
     */
    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<AuthSession> =
        call(serverUrl) { service ->
            service.login(LoginRequestDto(username = username, password = password))
                .toResult { body -> AuthMapper.toSession(body?.user) }
                .mapAuthenticationFailure(REJECTED_CREDENTIAL)
        }

    override suspend fun refresh(serverUrl: String, refreshToken: AuthToken): AppResult<AuthSession> =
        call(serverUrl) { service ->
            service.refresh(refreshToken.value).toResult { body -> AuthMapper.toSession(body?.user) }
        }

    /**
     * PRODUCT_SPEC 5.2 — the account, re-read with the token the caller already holds.
     *
     * The bearer is passed explicitly for the same reason sign-out passes it: refreshing profile B's
     * permissions must ask as B, never as whichever session an interceptor considers active.
     */
    override suspend fun currentAccount(serverUrl: String, accessToken: AuthToken): AppResult<AccountState> =
        call(serverUrl) { service ->
            service.authorize(bearerOf(accessToken.value))
                .toResult { body -> AuthMapper.toAccountState(body?.user) }
        }

    override suspend fun signOut(serverUrl: String, accessToken: AuthToken): AppResult<Unit> =
        call(serverUrl) { service ->
            // The token is passed explicitly rather than inherited from an interceptor: signing out
            // profile B must invalidate B's session, never whichever session happens to be active.
            // Sign-out failing must not strand the user in a signed-in state, so a failed call is
            // still reported and the caller drops the local session either way.
            service.logout(bearerOf(accessToken.value)).toResult { AppResult.Success(Unit) }
        }

    private inline fun <T, R> Response<T>.toResult(onSuccess: (T?) -> AppResult<R>): AppResult<R> = if (isSuccessful) {
        onSuccess(body())
    } else {
        AppResult.Failure(errors.fromStatus(code(), retryAfterSeconds = retryAfterSeconds()))
    }

    /** `Retry-After` is seconds or an HTTP date; only the numeric form is honoured. */
    private fun Response<*>.retryAfterSeconds(): Long? = headers()["Retry-After"]?.toLongOrNull()

    /**
     * Re-words an authentication failure for the call that produced it, leaving every other error alone.
     *
     * Deliberately narrow: only [AppError.Authentication] is rewritten, so a timeout or a certificate
     * problem during sign-in still reads as what it is.
     */
    private fun <R> AppResult<R>.mapAuthenticationFailure(summary: String): AppResult<R> = when {
        this is AppResult.Failure && error is AppError.Authentication -> AppResult.Failure(
            AppError.Authentication(summary = summary, requiresReauthentication = false),
        )

        else -> this
    }

    private suspend fun <R> call(serverUrl: String, block: suspend (AuthService) -> AppResult<R>): AppResult<R> {
        val result = resultOf(onError = errors::fromThrowable) {
            block(services.authService(serverUrl))
        }
        // `resultOf` wraps the transport; the block already returns a typed result for the HTTP
        // layer. Flattening keeps one AppResult rather than a result inside a result.
        return when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    private companion object {
        const val AUDIOBOOKSHELF = "audiobookshelf"

        /**
         * Names both possibilities, because the server does not say which.
         *
         * Audiobookshelf answers `401` for an unknown username and for a wrong password alike, and
         * distinguishing them in the UI would tell an attacker which usernames exist.
         */
        const val REJECTED_CREDENTIAL = "That username and password were not accepted by this server."
    }
}
