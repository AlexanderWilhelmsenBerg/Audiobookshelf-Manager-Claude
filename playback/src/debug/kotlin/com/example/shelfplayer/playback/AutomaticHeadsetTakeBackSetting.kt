package com.example.shelfplayer.playback

import android.content.Context
import androidx.core.content.edit

/** Debug comparison control. Default preserves the PR307 behavior; identity/protection are unaffected. */
object AutomaticHeadsetTakeBackSetting {
    private const val PREFERENCES = "system-route-comparison"
    private const val ENABLED = "automatic-headset-take-back"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(ENABLED, enabled) }
    }
}
