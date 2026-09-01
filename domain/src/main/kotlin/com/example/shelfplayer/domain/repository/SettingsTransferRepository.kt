package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.settings.SettingsExport
import com.example.shelfplayer.core.model.settings.SettingsImport

/**
 * PRODUCT_SPEC SET-001 — moving this install's settings to a file and back.
 *
 * ### Text in, text out, and no `Uri`
 *
 * The repository never sees where the file is. The caller reads and writes it through the system document
 * picker, and hands this interface the *content*. Two reasons, and the second is the load-bearing one:
 *
 *  - `android.net.Uri` in a domain interface would make `:domain` an Android module, which PRODUCT_SPEC 9.1
 *    forbids;
 *  - a `String` in and a `String` out is testable on the JVM with no `ContentResolver`, and what is worth
 *    testing here — that no credential leaves, that an unknown field does not throw, that an import keeps
 *    this install's own device id — is exactly the part that has nothing to do with files.
 */
interface SettingsTransferRepository {
    /** This install's settings as a document, with nothing sensitive in it. */
    suspend fun export(): AppResult<SettingsExport>

    /**
     * Applies [document] to this install.
     *
     * Device-wide settings are taken from the file. This install's own identity is **not**: the playback
     * device id, the active profile and the fixture-seed marker stay as they are, because they describe
     * this device rather than the user's preferences. Sending an imported `deviceId` to the server would
     * make two installs look like one device in its listening history.
     *
     * Fails with [com.example.shelfplayer.core.model.AppError.Validation] when the text is not a settings
     * document this build understands. An unrecognised *field* is not that: a file written by a newer
     * build applies the parts this one knows and ignores the rest.
     */
    suspend fun import(document: String): AppResult<SettingsImport>
}
