package com.example.shelfplayer.diagnostics

/**
 * The restart helper has no business initializing BookWave's ordinary process owners.
 * API 26/27 require manual restart; API 28+ can identify the process directly.
 *
 * Null is allowed for process name because Android can omit process metadata, and
 * suppressing primary app startup would otherwise leave BookWave unusable. The UI
 * never starts the helper automatically on platforms where detection is less reliable.
 */
internal object DiagnosticRestartPolicy {
    fun canUseTrampoline(api: Int): Boolean = api >= 28

    fun shouldInitializeApp(processName: String?, appPackage: String): Boolean =
        processName == null || processName == appPackage
}
