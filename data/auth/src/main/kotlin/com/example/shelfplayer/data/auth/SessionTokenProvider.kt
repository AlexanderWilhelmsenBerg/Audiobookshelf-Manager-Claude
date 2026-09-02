package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.datastore.security.SessionTokenKind
import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.network.http.ActiveCredential
import com.example.shelfplayer.core.network.http.TokenProvider
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-003 — custody of the active profile's credential.
 *
 * This lives in `:data:auth` rather than in `:app` because `:data:auth` is the only module that sees
 * both `:core:network` (which declares [TokenProvider]) and `:core:datastore` (which stores the token)
 * *and* owns the sign-out that has to invalidate it. It was in `:app` while nothing but the interceptor
 * consumed it; moving it next to [DefaultAuthRepository] puts the in-memory copy and the code
 * responsible for clearing it in the same module rather than in two.
 *
 * ### Why the token is cached in memory
 *
 * [TokenProvider.currentToken] is synchronous because an OkHttp interceptor is, while
 * [SessionTokenStore.load] is `suspend` and does file I/O and a Keystore decryption. Bridging those
 * with `runBlocking` inside an interceptor would put a blocking decrypt on every single request and
 * risk deadlock on a constrained dispatcher. The token is therefore held in memory and refreshed
 * deliberately by the session layer.
 *
 * The cost is that a decrypted token sits in process memory for the life of the session. That is
 * unavoidable for a client that must attach it to every request — it is in memory during the request
 * regardless — but it is why [clear] exists and why sign-out must call it: the in-memory copy has to go
 * at the same moment the stored one does, or the app keeps authenticating after the user believes it
 * stopped.
 *
 * ### What "active" means
 *
 * Exactly one profile's access token is cached, and [activate] chooses which. The cached value is
 * tagged with its profile, so a switch that fails to load the new profile's token cannot leave the
 * previous profile's credential attached to requests the caller believes belong to the new one
 * (PRODUCT_SPEC 5.2, product priority 4).
 *
 * ### Why the server address is a parameter
 *
 * `AuthorizationInterceptor` attaches the ambient bearer only to the origin that issued it, so the cache
 * has to carry that origin. It is passed in rather than looked up here on purpose: this class is a
 * credential cache, and giving it a `ProfileDao` would make it a second place that resolves profiles to
 * servers. Both callers already hold the address — sign-in has just normalised it, and a session restore
 * has just read the profile row. Making it required means a third caller has to supply one rather than
 * silently inheriting whichever origin happened to be cached last.
 */
@Singleton
class SessionTokenProvider @Inject constructor(private val store: SessionTokenStore) : TokenProvider {

    private val cached = AtomicReference<CachedToken?>(null)

    override fun current(): ActiveCredential? = cached.get()?.let { held ->
        ActiveCredential(token = held.token, serverBaseUrl = held.serverBaseUrl, profileId = held.profileId)
    }

    /** The profile whose credential is currently attached to outgoing requests, if any. */
    fun activeProfileId(): ProfileId? = cached.get()?.profileId

    /**
     * Loads [profileId]'s stored access token into memory and makes it the active one.
     *
     * Returns whether a token is now available. `false` means either none was stored or it could no
     * longer be decrypted — the caller cannot tell the two apart, and does not need to: both require
     * signing in again (`AUTH-004`). [SessionTokenStore] has already discarded an undecryptable record
     * by this point.
     *
     * The cache is dropped *before* the load, not after. If the load fails, the process must not keep
     * serving the previously active profile's token to calls the caller now believes are authenticated
     * as somebody else.
     */
    suspend fun activate(profileId: ProfileId, serverBaseUrl: String): Boolean {
        cached.set(null)
        val token = store.load(profileId.value, SessionTokenKind.Access) ?: return false
        cached.set(CachedToken(profileId, token, serverBaseUrl))
        return true
    }

    /**
     * Persists both tokens of [session] for [profileId] and makes its access token active.
     *
     * A session the server declared non-renewable stores no refresh token, and any refresh token left
     * over from an earlier sign-in of the same profile is removed. Keeping a stale one would let
     * `AUTH-004` renewal try a credential the current session never issued.
     */
    suspend fun adopt(profileId: ProfileId, session: AuthSession, serverBaseUrl: String) {
        store(profileId, session)
        cached.set(CachedToken(profileId, session.accessToken.value, serverBaseUrl))
    }

    /**
     * Replaces a renewed profile's stored tokens without stealing another profile's ambient credential.
     *
     * A background sync may renew profile A after the user switched to B. Only an already-active A is
     * updated in memory; B stays active and will never send A's newly rotated bearer by accident.
     */
    suspend fun adoptRenewal(profileId: ProfileId, session: AuthSession, serverBaseUrl: String) {
        store(profileId, session)
        cached.updateAndGet { current ->
            if (current?.profileId == profileId) {
                CachedToken(profileId, session.accessToken.value, serverBaseUrl)
            } else {
                current
            }
        }
    }

    private suspend fun store(profileId: ProfileId, session: AuthSession) {
        store.save(profileId.value, SessionTokenKind.Access, session.accessToken.value)
        val refresh = session.refreshToken
        if (refresh == null) {
            store.clear(profileId.value, SessionTokenKind.Refresh)
        } else {
            store.save(profileId.value, SessionTokenKind.Refresh, refresh.value)
        }
    }

    /** PRODUCT_SPEC AUTH-004 — the credential a renewal needs, or `null` when the session cannot be renewed. */
    suspend fun refreshTokenFor(profileId: ProfileId): AuthToken? =
        store.load(profileId.value, SessionTokenKind.Refresh)?.let(::AuthToken)

    /**
     * The stored access token for [profileId], read from disk rather than from the cache.
     *
     * Needed because a call can legitimately target a profile that is not the active one — signing out
     * a background profile, or syncing one while another is on screen — and such a call must carry
     * *that* profile's credential (PRODUCT_SPEC 5.2).
     */
    suspend fun accessTokenFor(profileId: ProfileId): AuthToken? =
        store.load(profileId.value, SessionTokenKind.Access)?.let(::AuthToken)

    /**
     * Forgets [profileId]'s tokens in memory and on disk.
     *
     * The in-memory copy is dropped *first*: if the disk delete throws, the process must not keep
     * authenticating with a credential the user has asked to be rid of. Another profile's cached token
     * is left alone, because clearing it would sign out an account the user did not ask about.
     */
    suspend fun clear(profileId: ProfileId) {
        val current = cached.get()
        if (current != null && current.profileId == profileId) cached.set(null)
        store.clear(profileId.value)
    }

    private data class CachedToken(val profileId: ProfileId, val token: String, val serverBaseUrl: String)
}
