package com.example.shelfplayer.core.model.settings

import java.time.Instant

/**
 * PRODUCT_SPEC SET-001 — the settings of this install, as a file the user keeps.
 *
 * ### Why this exists
 *
 * Uninstalling the app takes its settings with it, and a debug install used to require an uninstall every
 * time (see `DebugSigning`). The owner's words were *"I need a way to export the settings"* — a file they
 * choose the location of, and can hand back to a fresh install.
 *
 * ### What is deliberately not in it
 *
 * **No credential of any kind.** Not the bearer token, not the passcode verifier, not a password. Those live
 * in the Keystore-backed secret store precisely so that they cannot be copied off the device, and a settings
 * export that carried them would be a way around that (PRODUCT_SPEC AUTH-003, `docs/risks.md` R-20). The
 * file names servers; signing in to them is still a sign-in.
 *
 * The document is JSON rather than an opaque blob for the same reason: a user of a self-hosted app can open
 * it and see for themselves that nothing sensitive is in it.
 *
 * @property document the file's whole text.
 * @property suggestedFileName what to offer the system file picker. Carries the date so that two exports do
 *   not silently overwrite one another.
 */
data class SettingsExport(val document: String, val suggestedFileName: String)

/**
 * What an import actually did, so the UI can say so rather than claiming a blanket success.
 *
 * @property serverUrls the addresses the file named, in the order it named them. The importer does **not**
 *   create server rows from these — a server this device has never reached is not a server it knows — so
 *   they are offered to the sign-in screen to fill its address field.
 * @property exportedAt when the file was written, or `null` when it did not say.
 * @property profilePreferencesApplied how many accounts' view preferences were restored.
 * @property profilePreferencesSkipped how many were in the file with no matching account signed in on this
 *   device. Not an error: the common case is a fresh install, where every one of them is skipped, and the
 *   UI has to be able to say that instead of implying they arrived.
 */
data class SettingsImport(
    val serverUrls: List<String>,
    val exportedAt: Instant?,
    val profilePreferencesApplied: Int,
    val profilePreferencesSkipped: Int,
)
