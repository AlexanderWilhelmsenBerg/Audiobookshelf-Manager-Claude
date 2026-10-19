package com.example.shelfplayer.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.example.shelfplayer.R

/**
 * Explicit, private restart trampoline running OUTSIDE BookWave's main process.
 *
 * Media3's AudioOutputProvider is fixed when the ExoPlayer is built. A process
 * restart is necessary when capture begins after the player already exists.
 * The unified capture flag is synchronously saved before this Activity starts.
 *
 * Never run automatically, never schedule background starts, and never restart
 * other apps. The confirmation in UnifiedDiagnosticsSheet warns about playback.
 */
class DiagnosticRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(ActivityManager::class.java)
        val oldProcess = manager?.runningAppProcesses
            ?.firstOrNull { it.uid == Process.myUid() && it.processName == packageName }
        if (oldProcess == null || oldProcess.pid == Process.myPid()) {
            Toast.makeText(this, R.string.unified_diag_restart_manual, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, R.string.unified_diag_restart_manual, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // A separate process survives this signal and can relaunch after the old
        // service/player have gone away. The app's normal Hilt entry restores the
        // opt-in recorders BEFORE another playback service creates its next player.
        Process.killProcess(oldProcess.pid)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
        }, RELAUNCH_DELAY_MS)
    }

    private companion object {
        const val RELAUNCH_DELAY_MS = 350L
    }
}
