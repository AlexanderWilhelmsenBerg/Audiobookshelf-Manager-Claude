package com.example.shelfplayer.playback

import android.net.Uri
import com.example.shelfplayer.core.network.http.ActiveCredential
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.domain.usecase.RenewProfileSessionUseCase
import com.example.shelfplayer.domain.usecase.RequireProfileReauthenticationUseCase
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Media3's blocking loader thread to AUTH-004's suspending, exactly-once renewal.
 *
 * The raw token is used only as an in-process generation marker: when two range requests receive 401 at
 * once, the first rotates the token and the second sees that its rejected token is no longer current, so it
 * retries with the new credential rather than rotating the refresh token a second time.
 */
@Singleton
internal class PlaybackCredentialRenewer @Inject constructor(
    private val tokens: TokenProvider,
    private val renewSession: RenewProfileSessionUseCase,
    private val requireReauthentication: RequireProfileReauthenticationUseCase,
) : PlaybackCredentialRecovery {

    /** The credential that would sign [uri], or null when that URI is outside the active server origin. */
    override fun tokenFor(uri: Uri): String? = credentialFor(uri)?.token

    /**
     * Recovers one rejected media request.
     *
     * Returns true only when retrying the same request is meaningful: either another request already
     * replaced the token, or this call renewed the active profile successfully. A URI on another origin is
     * never allowed to cause credential renewal, matching AuthorizationInterceptor's bearer boundary.
     */
    override fun recoverAfterUnauthorized(uri: Uri, rejectedToken: String?): Boolean {
        val current = credentialFor(uri) ?: return false
        if (rejectedToken != null && current.token != rejectedToken) return true
        val profileId = current.profileId ?: return false
        return runBlocking { renewSession(profileId) }
    }

    override fun rejectRenewedCredential(uri: Uri) {
        val profileId = credentialFor(uri)?.profileId ?: return
        runBlocking { requireReauthentication(profileId) }
    }

    private fun credentialFor(uri: Uri): ActiveCredential? {
        val credential = tokens.current() ?: return null
        val request = uri.toString().toHttpUrlOrNull() ?: return null
        val issuer = credential.serverBaseUrl.toHttpUrlOrNull() ?: return null
        return credential.takeIf {
            request.scheme == issuer.scheme && request.host == issuer.host && request.port == issuer.port
        }
    }
}

/** Testable seam for the bounded Media3 retry; the implementation delegates coordination to AUTH-004. */
internal interface PlaybackCredentialRecovery {
    fun tokenFor(uri: Uri): String?

    fun recoverAfterUnauthorized(uri: Uri, rejectedToken: String?): Boolean

    fun rejectRenewedCredential(uri: Uri)
}
