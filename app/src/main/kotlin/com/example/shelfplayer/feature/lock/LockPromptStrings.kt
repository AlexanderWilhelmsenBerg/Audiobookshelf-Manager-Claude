package com.example.shelfplayer.feature.lock

import android.content.Context
import com.example.shelfplayer.R

/**
 * The three strings the system biometric prompt needs, read from resources.
 *
 * The prompt is a window the platform draws from a `Context`, not a composable, so it cannot use
 * `stringResource`. Reading them here keeps them translated — `values-nb` covers them like everything else
 * — and keeps `PlatformBiometricGateway` free of `R` lookups scattered through its `Build.VERSION` branches.
 *
 * One consequence of PRODUCT_SPEC SET-002's in-app language setting is visible here: this reads the
 * *process* locale, so on API 33 and above it follows the in-app choice, because `AppLocale` hands that
 * choice to `LocaleManager`. On API 26–32 it follows the device instead. ADR-0022 records the split; this is
 * one of the four places it can be seen.
 */
internal data class LockPromptStrings(val title: String, val description: String, val cancel: String) {
    companion object {
        fun of(context: Context) = LockPromptStrings(
            title = context.getString(R.string.lock_biometric_title),
            description = context.getString(R.string.lock_biometric_description),
            cancel = context.getString(R.string.lock_biometric_cancel),
        )
    }
}
