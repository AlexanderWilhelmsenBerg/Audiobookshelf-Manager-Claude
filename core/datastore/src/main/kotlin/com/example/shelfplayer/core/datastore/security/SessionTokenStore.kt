package com.example.shelfplayer.core.datastore.security

import android.content.Context
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-003 — where an encrypted session token lives.
 *
 * ### Why a file rather than the settings DataStore
 *
 * The stored value is an opaque ciphertext, so a typed proto field buys nothing: there is no schema to
 * validate and no field to migrate. Putting it in the settings proto would also mean every settings
 * read carries the credential in memory, and every settings dump — a diagnostics export, a debug
 * screen — would have to remember to exclude it. A separate file in the app's private storage keeps
 * credentials off the settings surface entirely, which is the property `PRODUCT_SPEC 14.5` needs.
 *
 * Written through a temporary file and renamed, so an interrupted write cannot leave a half-token
 * that would decrypt to nothing and log the user out (priority 2: do not lose progress — a lost
 * session means a lost sync).
 *
 * One file per profile *and per token kind*: the file name is derived from both, so signing a second
 * account in cannot overwrite the first (`PRODUCT_SPEC AUTH-002`) and a refresh token cannot overwrite
 * the access token it renews (`PRODUCT_SPEC AUTH-004`).
 *
 * ### Why two files rather than one record
 *
 * A single encrypted blob holding both tokens would need a format, and a format needs a version, a
 * parser and a decision about what to do with a record it cannot read. Two files need none of that:
 * each holds one opaque string, either decrypts or does not, and the failure mode of a missing refresh
 * token is already a state the app models (`AuthSession.isRenewable`).
 */
@Singleton
class SessionTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: TokenCipher,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * An [IOException] here propagates rather than being caught.
     *
     * ADR-0003 makes `resultOf` at the repository boundary the single exception boundary, so catching
     * an I/O failure at this depth would either swallow it or duplicate that boundary. The staging
     * file is cleaned up on the way out, which is the only thing this layer actually knows how to do
     * about it.
     */
    suspend fun save(profileId: String, kind: SessionTokenKind, token: String): Unit = withContext(ioDispatcher) {
        val target = fileFor(profileId, kind)
        val staging = File(target.parentFile, "${target.name}.tmp")
        target.parentFile?.mkdirs()
        try {
            staging.writeBytes(cipher.encrypt(token))
            // renameTo is atomic within a filesystem, so a reader sees either the old token or the
            // new one, never a partial write. Its Boolean result must be checked: a silent false
            // would leave the caller believing a session was stored when it was not, and the user
            // signed out at the next cold start with no indication why.
            if (!staging.renameTo(target)) {
                throw IOException("could not commit the session token file")
            }
        } finally {
            staging.delete()
        }
        Unit
    }

    /**
     * Returns the stored token, or `null` if there is none or it can no longer be decrypted.
     *
     * A `null` from an existing file means the key is gone — a lock-screen change or a restore onto a
     * new device. The unreadable file is removed rather than left to fail on every future read, and
     * the caller treats this as "sign in again" (`AUTH-004`).
     */
    suspend fun load(profileId: String, kind: SessionTokenKind): String? = withContext(ioDispatcher) {
        val file = fileFor(profileId, kind)
        if (!file.exists()) return@withContext null
        val decrypted = cipher.decrypt(file.readBytes())
        if (decrypted == null) file.delete()
        decrypted
    }

    /** Removes every token kind for one profile: signing out must not leave a renewable half-session. */
    suspend fun clear(profileId: String): Unit = withContext(ioDispatcher) {
        SessionTokenKind.entries.forEach { kind -> fileFor(profileId, kind).delete() }
    }

    /**
     * Removes a single token kind.
     *
     * Used when a new session is non-renewable: the access token is replaced while the refresh token
     * from a previous session has to go, or `AUTH-004` would try to renew with a credential this
     * session never issued.
     */
    suspend fun clear(profileId: String, kind: SessionTokenKind): Unit = withContext(ioDispatcher) {
        fileFor(profileId, kind).delete()
        Unit
    }

    /**
     * Removes every stored token and the key itself.
     *
     * Dropping the key is what makes this irreversible: any ciphertext that survived on disk, in a
     * backup, or in a filesystem snapshot becomes permanently unreadable rather than merely deleted.
     */
    suspend fun clearAll(): Unit = withContext(ioDispatcher) {
        directory().listFiles()?.forEach { it.delete() }
        cipher.clear()
    }

    /**
     * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — how many **accounts** have a credential on disk.
     *
     * Accounts, not files. Each profile stores one file per [SessionTokenKind], so counting files reported
     * six for three signed-in accounts — a device run duly asked why. The file *stem* is the hashed profile
     * id, so counting distinct stems answers the question the label asks.
     *
     * A count and nothing else. It exists so that signing out can be *seen* to have deleted something,
     * which is otherwise invisible; a store that could describe its contents to a screen would be a worse
     * store, and the names are hashed for the same reason (PRODUCT_SPEC AUTH-003).
     */
    suspend fun storedCredentialCount(): Int = withContext(ioDispatcher) {
        directory().listFiles().orEmpty()
            .map { it.name.substringBefore('.') }
            .distinct()
            .size
    }

    private fun directory(): File = File(context.filesDir, DIRECTORY)

    /**
     * The profile id is hashed rather than used directly.
     *
     * A file name is not a secret store: it appears in logcat on an I/O error, in `adb shell ls`, and
     * in crash reports. Hashing keeps a server-derived identifier out of all of them.
     */
    private fun fileFor(profileId: String, kind: SessionTokenKind): File {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val name = profileId.hashCode().toUInt().toString(RADIX)
        return File(directory(), "$name.${kind.suffix}.bin")
    }

    private companion object {
        const val DIRECTORY = "sessions"
        const val RADIX = 16
    }
}

/**
 * The two credentials a session consists of (`PRODUCT_SPEC AUTH-004`).
 *
 * [suffix] is short and opaque on purpose: a file name is visible in `adb shell ls` and in an I/O
 * error message, and "this file holds a refresh token" is more than a bystander needs to know.
 */
enum class SessionTokenKind(val suffix: String) {
    Access("a"),
    Refresh("r"),
}
