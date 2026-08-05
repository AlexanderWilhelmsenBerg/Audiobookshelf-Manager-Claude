package com.example.shelfplayer.di

import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.network.http.TokenProvider
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-003 — supplies the active profile's token to the HTTP layer.
 *
 * Lives in `:app` because it is the only module that sees both `:core:network` (which declares
 * [TokenProvider]) and `:core:datastore` (which stores the token). `docs/architecture/module-boundaries.md`
 * makes `:app` the place final wiring happens, so this needs no new dependency between core modules.
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
 * regardless — but it is why [clear] exists and why sign-out must call it: the in-memory copy has to
 * go at the same moment the stored one does, or the app keeps authenticating after the user believes
 * it stopped.
 */
@Singleton
class SessionTokenProvider @Inject constructor(private val store: SessionTokenStore) : TokenProvider {

    private val cached = AtomicReference<String?>(null)

    override fun currentToken(): String? = cached.get()

    /**
     * Loads the stored token for [profileId] into memory.
     *
     * Returns whether a token is now available. `false` means either none was stored or it could no
     * longer be decrypted — the caller cannot tell the two apart, and does not need to: both require
     * signing in again (`AUTH-004`). [SessionTokenStore] has already discarded an undecryptable
     * record by this point.
     */
    suspend fun load(profileId: String): Boolean {
        val token = store.load(profileId)
        cached.set(token)
        return token != null
    }

    /** Stores [token] for [profileId] and makes it current. */
    suspend fun adopt(profileId: String, token: String) {
        store.save(profileId, token)
        cached.set(token)
    }

    /**
     * Forgets the token in memory and on disk.
     *
     * The in-memory copy is cleared *first*: if the disk write throws, the process must not keep
     * authenticating with a credential the user has asked to be rid of.
     */
    suspend fun clear(profileId: String) {
        cached.set(null)
        store.clear(profileId)
    }
}
