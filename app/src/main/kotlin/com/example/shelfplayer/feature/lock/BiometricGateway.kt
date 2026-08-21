package com.example.shelfplayer.feature.lock

import android.app.Activity
import com.example.shelfplayer.core.model.lock.BiometricAvailability

/**
 * PRODUCT_SPEC AUTH-005 — "Optional profile PIN **or biometric** gate".
 *
 * An interface because the implementation is one of the few things in this app that cannot be tested at
 * all: a fingerprint prompt needs a finger. Everything that *decides* whether to offer it — the
 * availability mapping, the settings row's enabled state, what the curtain shows — is testable against a
 * fake, and that is where the logic lives.
 */
interface BiometricGateway {
    /** Whether this device can authenticate, and if not, which of the four reasons applies. */
    fun availability(): BiometricAvailability

    /**
     * Shows the system prompt.
     *
     * @param onSuccess called only for an authentication the platform accepted. **This is policy, not
     *   cryptography**: the stored verifier is a one-way derivation, so nothing here can produce it, and a
     *   successful prompt is trusted rather than proven. ADR-0023 says so plainly and the product does not
     *   imply otherwise.
     * @param onFailed called when the user cancelled or the platform refused. The curtain stays up either
     *   way; the distinction only decides whether a message appears.
     */
    fun authenticate(activity: Activity, onSuccess: () -> Unit, onFailed: () -> Unit)
}
