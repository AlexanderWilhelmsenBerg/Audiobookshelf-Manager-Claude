package com.example.shelfplayer.core.datastore.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * AUTH-005 — turns a passcode into something that can be checked but not read back.
 *
 * ### Hashed, not encrypted, and the distinction matters
 *
 * A passcode is never stored. What is stored is a value derived from it that can confirm a later guess
 * and cannot produce the original. That is why this file uses a KDF rather than [TokenCipher]: a token
 * has to be *recovered* to be sent to a server, so it must be reversible; a passcode only has to be
 * *compared*, so making it reversible would be strictly worse with no benefit.
 *
 * ### Why this is the one part of AUTH-005 that CI can prove
 *
 * `SecretKeyFactory` is a platform class with a JVM implementation, so every rule in here — the
 * derivation, the salting, the constant-time comparison, the policy — runs in an ordinary unit test on
 * the build machine. The Keystore wrap around the record and the biometric prompt cannot be, which is
 * stated where those live. This file is therefore where the load-bearing correctness goes.
 *
 * ### How much this is worth, stated honestly
 *
 * [ITERATIONS] makes one guess cost real work, but a six-digit passcode is a million possibilities.
 * An attacker holding the record file and a GPU exhausts that. What the Keystore wrap around the
 * record buys is that obtaining the file requires running code on the device rather than copying it
 * off — and that is the whole of the claim. ADR-0023 says so in as many words, and the product does
 * not imply more.
 */
internal object PasscodeKdf {

    /**
     * The JCA name, spelled exactly.
     *
     * `PBKDF2WithHmacSHA256` is available from API 26, which is this project's `minSdk` exactly — one
     * level lower and this file would have to fall back to SHA-1. It is stored in each record rather
     * than assumed at read time, so raising the cost later cannot invalidate existing records.
     */
    const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * Deliberately expensive, and deliberately a companion constant so it can be raised.
     *
     * OWASP's 2023 guidance for PBKDF2-HMAC-SHA256 is 600,000 for a password. This is lower on
     * purpose: a passcode is checked on a phone that may be five years old, and a device that takes
     * three seconds to reject a typo is a device whose owner turns the lock off. The number is a
     * trade, it is written down, and the record carries the value it was derived with.
     */
    const val ITERATIONS = 210_000

    private const val SALT_BYTES = 16
    private const val VERIFIER_BITS = 256

    /** Minimum and maximum digits. Six is the floor because four is guessable by shoulder-surfing. */
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    /** A fresh salt. Per record, so the same passcode on two profiles derives two different verifiers. */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)

    /**
     * Derives the verifier for [passcode].
     *
     * Takes a `CharArray` rather than a `String` because [PBEKeySpec.clearPassword] can wipe the copy
     * it holds, and a `String` could not be wiped at all — it would sit in the heap until the garbage
     * collector felt like moving it, and into any heap dump taken in between. This is a small
     * mitigation and it is the only one available at this layer; the caller is expected to clear its
     * own array too.
     */
    fun derive(passcode: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passcode, salt, iterations, VERIFIER_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Whether [passcode] derives [verifier], compared in constant time.
     *
     * `MessageDigest.isEqual` rather than `contentEquals`: the array comparison short-circuits on the
     * first differing byte, and the time it takes is therefore a measurement of how many leading bytes
     * were right. That is a timing oracle, and while exploiting one across an on-device UI is
     * far-fetched, writing the comparison correctly costs one function call.
     */
    fun matches(passcode: CharArray, salt: ByteArray, iterations: Int, verifier: ByteArray): Boolean =
        MessageDigest.isEqual(derive(passcode, salt, iterations), verifier)

    /**
     * AUTH-005 — what this app will accept as a passcode.
     *
     * Digits only, and the rejections are the three shapes that are chosen precisely because they are
     * easy to type: one repeated digit, a run upwards, a run downwards. There is no word list and no
     * strength meter — this is a local presence check, not an account password, and a policy strict
     * enough to be annoying is a policy that makes people write the code on the case.
     */
    fun validate(passcode: CharArray): PasscodeRejection? = when {
        passcode.size < MIN_LENGTH || passcode.size > MAX_LENGTH -> PasscodeRejection.Length
        !passcode.all(Char::isDigit) -> PasscodeRejection.NotDigits
        passcode.all { it == passcode[0] } -> PasscodeRejection.TooSimple
        isRun(passcode, step = 1) || isRun(passcode, step = -1) -> PasscodeRejection.TooSimple
        else -> null
    }

    private fun isRun(passcode: CharArray, step: Int): Boolean =
        passcode.indices.drop(1).all { i -> passcode[i] - passcode[i - 1] == step }
}

/**
 * Why a passcode was refused.
 *
 * An enum rather than a message, because the string that explains it is a localised resource in `:app`
 * and this module has no access to one — and because a reason a test can assert on is worth more than
 * a sentence it has to match.
 */
enum class PasscodeRejection { Length, NotDigits, TooSimple }
