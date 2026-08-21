package com.example.shelfplayer.feature.lock

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * AUTH-005 — the platform's own biometric prompt, and no new dependency.
 *
 * ### Why not `androidx.biometric`
 *
 * Three reasons, and the second is the one that decided it.
 *
 * `androidx.biometric:1.1.0` pulls in the **full** `androidx.appcompat`, which this project does not have —
 * only `appcompat-resources` is on the classpath today. More seriously, its compatibility path for API 26
 * and 27 constructs an `androidx.appcompat.app.AlertDialog`, which throws
 * `IllegalStateException("You need to use a Theme.AppCompat theme")` under this app's platform-parented
 * theme. That is a crash on the two oldest levels this app supports, in a code path no test in this
 * repository can reach, because there is no instrumented tier. Third, `strict` dependency verification is
 * on — 852 components, 1,540 checksums — so adding it means regenerating that metadata for an optional
 * feature.
 *
 * ADR-0023 records the trade. The cost is stated below rather than hidden.
 *
 * ### API 26 and 27 get no biometrics, and are told so
 *
 * `android.hardware.biometrics.BiometricPrompt` starts at API 28. Below that the only platform option is
 * `FingerprintManager`, which requires the *app* to draw the dialog, cannot report whether the sensor is
 * strong, and could not be exercised by this project's tests either. A passcode is the floor on every
 * supported release; two levels get an honest disabled row instead of an unverifiable custom dialog. The
 * row is shown disabled with the reason on it, never hidden — a hidden row was reported from a device run
 * as an unbuilt feature.
 */
class PlatformBiometricGateway @Inject constructor(@param:ApplicationContext private val context: Context) :
    BiometricGateway {

    override fun availability(): BiometricAvailability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> BiometricAvailability.UnsupportedAndroidVersion
        else -> platformAvailability()
    }

    /**
     * `canAuthenticate` distinguishes the three answers the settings row needs.
     *
     * Strength is asked for explicitly from API 30, where `BIOMETRIC_STRONG` exists. On 28 and 29 the
     * platform reports only "some biometric", so strength is **unknown** and is treated as unknown rather
     * than assumed strong — which is why the ADR does not claim a strength guarantee on those levels.
     */
    private fun platformAvailability(): BiometricAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 28 has no BiometricManager. The fingerprint feature flag is the only question available.
            val hasSensor = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            return if (hasSensor) BiometricAvailability.Available else BiometricAvailability.NoHardware
        }
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return BiometricAvailability.NoHardware
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
            else -> BiometricAvailability.NoHardware
        }
    }

    override fun authenticate(activity: Activity, onSuccess: () -> Unit, onFailed: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFailed()
            return
        }
        prompt(activity, onSuccess, onFailed)
    }

    private fun prompt(activity: Activity, onSuccess: () -> Unit, onFailed: () -> Unit) {
        val strings = LockPromptStrings.of(activity)
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(strings.title)
            .setDescription(strings.description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            // The passcode is the fallback, and it is already on screen behind this prompt — so the
            // negative button dismisses rather than offering the device credential. Accepting the device
            // credential would hand the lock to the person it defends against: whoever is holding the
            // already-unlocked phone.
            builder.setNegativeButton(strings.cancel, activity.mainExecutor) { _, _ -> onFailed() }
        } else {
            builder.setNegativeButton(strings.cancel, activity.mainExecutor) { _, _ -> onFailed() }
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onFailed()
                }

                // A single non-matching finger is not a failure of the attempt: the platform keeps the
                // prompt open and lets the user try again. Reporting it would close a prompt the system
                // has not closed.
                override fun onAuthenticationFailed() = Unit
            },
        )
    }
}
