package com.example.shelfplayer.core.datastore.security

import android.content.Context
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.datastore.ProfileLockRecord
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC AUTH-005 — where a profile's passcode lock lives on disk.
 *
 * ### One source of truth, and it is the file
 *
 * There is no boolean anywhere saying "this profile has a passcode". **The record's existence is that
 * fact.** The alternative — a column on `profiles` or a field in `AppSettings` — creates a second
 * place that can disagree with the first, and the disagreement is not symmetrical: a flag saying "no
 * passcode" beside a record that exists means the curtain is never drawn.
 *
 * That also keeps the `profiles` table out of it, which matters for a reason unrelated to tidiness:
 * every other column there is server-derived and is rewritten by `ProfileDao.setAccountState` on each
 * permission refresh. A local security setting in that table would be one careless `UPDATE` from gone.
 *
 * ### Fail closed
 *
 * Every failure to read, unwrap or parse resolves to *this profile is locked*, never to *this profile
 * has no passcode*. [readOrFailClosed] is the single place that decision is made, and
 * [LockedByFailure] is what it returns — a distinct outcome, so the UI can say "this lock cannot be
 * read" rather than silently rejecting every correct passcode.
 *
 * ### The rate limit is inside the encrypted record
 *
 * Not in settings, and not in memory. In memory it would reset on every force-stop; in settings it
 * could be cleared by wiping app data. Inside the record, resetting it requires the Keystore key.
 */
@Singleton
class ProfilePasscodeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: LockCipher,
    private val clock: AppClock,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Serializes every read-modify-write.
     *
     * Two callers incrementing the failure counter concurrently — a passcode submitted twice by a
     * double tap — would otherwise read the same record and write the same increment, so the second
     * attempt would be free. A lock that can be rate-limited by tapping faster is not rate-limited.
     */
    private val mutex = Mutex()

    private val protected = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Which profiles have a passcode, for the settings screen and the profile switcher.
     *
     * A `StateFlow` seeded by [refresh] rather than a directory watch: the set changes only when this
     * class changes it, so there is nothing to observe that is not already a method call here.
     */
    fun observeProtectedProfiles(): Flow<Set<String>> = protected.asStateFlow()

    /** Re-reads the directory. Called once at startup and after every mutation. */
    suspend fun refresh(): Unit = withContext(ioDispatcher) {
        protected.value = directory().listFiles().orEmpty()
            .mapNotNull { file -> file.name.removeSuffix(SUFFIX).takeIf { it != file.name } }
            .toSet()
    }

    /**
     * PRODUCT_SPEC AUTH-005 — whether this would be accepted as a passcode, without storing anything.
     *
     * Exposed here rather than by [PasscodeKdf] directly, which stays `internal`: the derivation, the
     * iteration count and the salt handling are this module's business, and a caller that could reach
     * them could also derive a verifier itself. The policy is the only part a caller needs.
     */
    fun validate(passcode: CharArray): PasscodeRejection? = PasscodeKdf.validate(passcode)

    /** The passcode length this app accepts, for a field's own input filtering. */
    val lengthRange: IntRange get() = PasscodeKdf.MIN_LENGTH..PasscodeKdf.MAX_LENGTH

    /** Whether [profileKey] has a passcode at all. The file's existence is the answer. */
    suspend fun hasPasscode(profileKey: String): Boolean = withContext(ioDispatcher) {
        fileFor(profileKey).exists()
    }

    /**
     * Sets or replaces the passcode.
     *
     * The caller has already proved presence — either by knowing the current passcode or by having
     * just chosen a new one on an unlocked profile — so this does not check anything itself. It also
     * resets the failure counter: a new passcode with an inherited lockout would be unusable.
     */
    suspend fun setPasscode(profileKey: String, passcode: CharArray, existing: LockPreferences?): Unit =
        withContext(ioDispatcher) {
            mutex.withLock {
                val salt = PasscodeKdf.newSalt()
                val record = ProfileLockRecord.newBuilder()
                    .setRecordVersion(RECORD_VERSION)
                    .setKdf(PasscodeKdf.ALGORITHM)
                    .setIterations(PasscodeKdf.ITERATIONS)
                    .setSalt(salt.toByteString())
                    .setVerifier(PasscodeKdf.derive(passcode, salt, PasscodeKdf.ITERATIONS).toByteString())
                    .setBiometricUnlockEnabled(existing?.biometricUnlock ?: false)
                    .setRelockDelaySeconds(existing?.relockDelay?.inWholeSeconds?.toInt() ?: 0)
                    .build()
                write(profileKey, record)
            }
            refresh()
        }

    /** Removes the passcode. The caller is responsible for having proved presence first. */
    suspend fun removePasscode(profileKey: String): Unit = withContext(ioDispatcher) {
        mutex.withLock { fileFor(profileKey).delete() }
        refresh()
    }

    /**
     * Checks [passcode] and records the outcome.
     *
     * The counter is written **before** the answer is returned, and on the success path it is cleared
     * in the same critical section. A verification that returned first and persisted afterwards could
     * be interrupted between the two by process death, which is a free guess.
     */
    suspend fun verify(profileKey: String, passcode: CharArray): PasscodeVerdict = withContext(ioDispatcher) {
        mutex.withLock {
            val record = readOrFailClosed(profileKey) ?: return@withLock PasscodeVerdict.Unreadable
            val now = clock.now().toEpochMilli()
            if (record.retryNotBeforeEpochMillis > now) {
                return@withLock PasscodeVerdict.BackingOff(record.retryNotBeforeEpochMillis - now)
            }
            if (record.consecutiveFailures >= MAX_FAILURES) return@withLock PasscodeVerdict.Exhausted
            val ok = PasscodeKdf.matches(
                passcode = passcode,
                salt = record.salt.toByteArray(),
                iterations = record.iterations,
                verifier = record.verifier.toByteArray(),
            )
            write(profileKey, record.afterAttempt(ok, now))
            if (ok) PasscodeVerdict.Correct else verdictFor(record.consecutiveFailures + 1)
        }
    }

    /** The lock's own preferences, or `null` when this profile has no passcode. */
    suspend fun preferences(profileKey: String): LockPreferences? = withContext(ioDispatcher) {
        mutex.withLock { readOrFailClosed(profileKey) }?.let { record ->
            LockPreferences(
                biometricUnlock = record.biometricUnlockEnabled,
                relockDelay = record.relockDelaySeconds.seconds,
            )
        }
    }

    /** Changes a preference without touching the verifier. A no-op when there is no passcode. */
    suspend fun updatePreferences(profileKey: String, preferences: LockPreferences): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val record = readOrFailClosed(profileKey) ?: return@withLock
            write(
                profileKey,
                record.toBuilder()
                    .setBiometricUnlockEnabled(preferences.biometricUnlock)
                    .setRelockDelaySeconds(preferences.relockDelay.inWholeSeconds.toInt())
                    .build(),
            )
        }
    }

    /**
     * Reads a record, or `null` for **any** reason at all.
     *
     * The caller must treat `null` as locked. That is why this function does not distinguish "no file"
     * from "unreadable file" in its return type: the two have different *causes* and the same
     * *consequence*, and a caller given the distinction would eventually branch on it wrongly.
     * [hasPasscode] is where the existence question is asked, and it is asked separately.
     */
    private fun readOrFailClosed(profileKey: String): ProfileLockRecord? {
        val file = fileFor(profileKey)
        if (!file.exists()) return null
        val wrapped = try {
            file.readBytes()
        } catch (_: IOException) {
            // Narrow and deliberate: an unreadable file is a locked profile, and the alternative to
            // catching here is a crash on every launch for a user whose disk hiccuped once.
            return null
        }
        val plain = cipher.unwrap(wrapped) ?: return null
        return try {
            ProfileLockRecord.parseFrom(plain)
        } catch (_: InvalidProtocolBufferException) {
            null
        }
    }

    private fun write(profileKey: String, record: ProfileLockRecord) {
        val target = fileFor(profileKey)
        val staging = File(target.parentFile, "${target.name}.tmp")
        target.parentFile?.mkdirs()
        try {
            staging.writeBytes(cipher.wrap(record.toByteArray()))
            // Atomic within a filesystem, so a reader sees the old record or the new one and never a
            // half-written verifier — which would fail closed and lock somebody out permanently.
            if (!staging.renameTo(target)) throw IOException("could not commit the lock record")
        } finally {
            staging.delete()
        }
    }

    private fun ProfileLockRecord.afterAttempt(correct: Boolean, now: Long): ProfileLockRecord {
        if (correct) {
            return toBuilder().setConsecutiveFailures(0).setRetryNotBeforeEpochMillis(0).build()
        }
        val failures = consecutiveFailures + 1
        return toBuilder()
            .setConsecutiveFailures(failures)
            .setRetryNotBeforeEpochMillis(now + backoffFor(failures).inWholeMilliseconds)
            .build()
    }

    private fun directory(): File = File(context.filesDir, DIRECTORY)

    /**
     * The file for a profile, named by a hash.
     *
     * The same reasoning as `SessionTokenStore.fileFor`: a file name reaches logcat on an I/O error and
     * `adb shell ls` always, so a server-derived identifier does not go in one. The caller passes an
     * already-opaque key — see `DefaultProfileLockRepository`, which hashes the profile id.
     */
    private fun fileFor(profileKey: String): File {
        require(profileKey.isNotBlank()) { "profileKey must not be blank" }
        return File(directory(), "$profileKey$SUFFIX")
    }

    private fun ByteArray.toByteString() = com.google.protobuf.ByteString.copyFrom(this)

    internal companion object {
        const val DIRECTORY = "locks"
        private const val SUFFIX = ".lock.bin"
        private const val RECORD_VERSION = 1

        /** Attempts before the passcode field is disabled and only re-authentication works. */
        const val MAX_FAILURES = 10

        /** Free attempts before any delay. Four typos is a plausible accident; a fifth is a pattern. */
        const val FREE_ATTEMPTS = 4

        private val FIRST_BACKOFF = 30.seconds
        private val MAX_BACKOFF = Duration.parse("15m")

        /** Doubling from thirty seconds, capped. Below [FREE_ATTEMPTS] there is no delay at all. */
        fun backoffFor(failures: Int): Duration {
            if (failures <= FREE_ATTEMPTS) return Duration.ZERO
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(MAX_DOUBLINGS)
            val scaled = FIRST_BACKOFF * (1 shl doublings)
            return if (scaled > MAX_BACKOFF) MAX_BACKOFF else scaled
        }

        private const val MAX_DOUBLINGS = 20

        private fun verdictFor(failures: Int): PasscodeVerdict = when {
            failures >= MAX_FAILURES -> PasscodeVerdict.Exhausted
            else -> PasscodeVerdict.Wrong(
                remainingBeforeBackoff = (FREE_ATTEMPTS - failures).coerceAtLeast(0),
                backoff = backoffFor(failures),
            )
        }
    }
}

/** The lock's own settings, which are stored beside the verifier so enabling a lock is one write. */
data class LockPreferences(val biometricUnlock: Boolean = false, val relockDelay: Duration = Duration.ZERO)

/**
 * What happened when a passcode was checked.
 *
 * [Unreadable] is separate from [Wrong] on purpose. A record that cannot be decrypted rejects every
 * passcode, including the right one, and telling somebody "wrong passcode" in that situation would
 * send them to try harder at something that cannot work. The honest answer names the state and offers
 * the recovery path.
 */
sealed interface PasscodeVerdict {
    data object Correct : PasscodeVerdict
    data class Wrong(val remainingBeforeBackoff: Int, val backoff: Duration) : PasscodeVerdict
    data class BackingOff(val remainingMillis: Long) : PasscodeVerdict
    data object Exhausted : PasscodeVerdict
    data object Unreadable : PasscodeVerdict
}
