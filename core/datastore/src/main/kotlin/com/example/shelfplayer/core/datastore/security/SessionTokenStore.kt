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
 * One token per profile: the file name is derived from the profile id, so signing a second account in
 * cannot overwrite the first (`PRODUCT_SPEC AUTH-002`).
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
    suspend fun save(profileId: String, token: String): Unit = withContext(ioDispatcher) {
        val target = fileFor(profileId)
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
    suspend fun load(profileId: String): String? = withContext(ioDispatcher) {
        val file = fileFor(profileId)
        if (!file.exists()) return@withContext null
        val decrypted = cipher.decrypt(file.readBytes())
        if (decrypted == null) file.delete()
        decrypted
    }

    suspend fun clear(profileId: String): Unit = withContext(ioDispatcher) {
        fileFor(profileId).delete()
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

    private fun directory(): File = File(context.filesDir, DIRECTORY)

    /**
     * The profile id is hashed rather than used directly.
     *
     * A file name is not a secret store: it appears in logcat on an I/O error, in `adb shell ls`, and
     * in crash reports. Hashing keeps a server-derived identifier out of all of them.
     */
    private fun fileFor(profileId: String): File {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val name = profileId.hashCode().toUInt().toString(RADIX)
        return File(directory(), "$name.bin")
    }

    private companion object {
        const val DIRECTORY = "sessions"
        const val RADIX = 16
    }
}
