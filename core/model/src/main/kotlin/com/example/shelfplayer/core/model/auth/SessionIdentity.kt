package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import java.security.MessageDigest

/**
 * PRODUCT_SPEC AUTH-002 / 13.1 — where a local [ServerId] and [ProfileId] come from.
 *
 * Both are **derived**, not randomly generated, and that is the whole point. Signing the same account
 * in again — after a session expired, after a reinstall that kept app data, after a reauthentication —
 * has to land on the same [ProfileId], because downloads, progress, bookmarks and settings overrides
 * are all keyed by it. A random id would silently orphan every one of them and present the user with
 * an empty library that used to have their books in it (product priority 2: do not lose progress).
 *
 * The values are hashes rather than the inputs themselves for two reasons. A base URL and a server
 * username are private self-hosted data that PRODUCT_SPEC 14.5 keeps out of logs and file names, and
 * [ProfileId] reaches both: it names the token file and appears in log fields. A hash also cannot
 * contain the entity-key separator that `:core:database` reserves, so a derived id is always a legal
 * key component.
 */
object SessionIdentity {

    /**
     * The local id for the server reachable at [normalizedBaseUrl].
     *
     * The URL must already be normalized (`ServerUrlNormalizer` does that): `https://host` and
     * `https://host/` have to produce the same id, and reconciling that here as well would put the
     * same rule in two places. Case is folded because a host name is case-insensitive, and only the
     * host realistically varies in case — a path that differs in case is a different path on most
     * servers, but treating the whole URL uniformly is the conservative choice: it can merge two
     * spellings of one server, never split one server into two profiles.
     */
    fun serverIdFor(normalizedBaseUrl: String): ServerId {
        require(normalizedBaseUrl.isNotBlank()) { "normalizedBaseUrl must not be blank" }
        return ServerId("$SERVER_PREFIX${digest(normalizedBaseUrl.lowercase())}")
    }

    /**
     * The local id for one account on [serverId].
     *
     * [remoteUserId] is preferred because it survives a rename on the server. When the server did not
     * send one, the username is used instead and the consequence is explicit: renaming that account
     * on the server produces a second profile rather than reusing the first. That is a visible,
     * recoverable outcome, whereas guessing at an identity the server never provided is not
     * (PRODUCT_SPEC 22.4).
     */
    fun profileIdFor(serverId: ServerId, remoteUserId: String?, username: String): ProfileId {
        require(username.isNotBlank()) { "username must not be blank" }
        val account = remoteUserId?.takeIf(String::isNotBlank)
            ?.let { "$REMOTE_ID_PREFIX$it" }
            ?: "$USERNAME_PREFIX${username.lowercase()}"
        return ProfileId("$PROFILE_PREFIX${digest("${serverId.value}$SEPARATOR$account")}")
    }

    /**
     * Truncated to sixteen bytes.
     *
     * These identifiers are collision-resistance requirements, not signatures: 128 bits of SHA-256 is
     * far past the point where two servers or two accounts on one device could collide, and a shorter
     * id keeps log lines and file names readable.
     */
    private fun digest(input: String): String {
        val bytes = MessageDigest.getInstance(ALGORITHM).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(DIGEST_BYTES).joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private const val ALGORITHM = "SHA-256"
    private const val DIGEST_BYTES = 16
    private const val SERVER_PREFIX = "srv_"
    private const val PROFILE_PREFIX = "prf_"

    /** Distinguishes the two account keyings so a user id can never hash to the same value as a name. */
    private const val REMOTE_ID_PREFIX = "id:"
    private const val USERNAME_PREFIX = "name:"
    private const val SEPARATOR = "|"
}
