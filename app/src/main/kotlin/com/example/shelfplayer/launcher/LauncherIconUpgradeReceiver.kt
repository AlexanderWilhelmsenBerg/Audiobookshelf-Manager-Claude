package com.example.shelfplayer.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reconciles the one-time Indigo-to-BookWave default migration immediately after an app update.
 *
 * The same [LauncherIcons.current] call runs when Settings opens, but waiting for that would let an
 * explicitly chosen legacy Indigo icon display as BookWave until the next app launch. The package-replaced
 * broadcast is explicit to this installed package and requires no exported receiver.
 */
@AndroidEntryPoint
class LauncherIconUpgradeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var launcherIcons: LauncherIcons

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) launcherIcons.current()
    }
}
