package com.example.shelfplayer.feature.settings.transfer

import android.content.ContentResolver
import android.net.Uri
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf

/**
 * PRODUCT_SPEC SET-001 — the settings document, through the system file picker.
 *
 * ### Why this is in `:app` and not in a repository
 *
 * A `Uri` from the Storage Access Framework is a UI fact. The user picked a location; this app has a
 * grant to that one document and to nothing else, which is exactly the point — there is no storage
 * permission anywhere in the manifest, and adding one to save the user a tap would be trading a
 * device-wide grant for a convenience.
 *
 * That is also why **nothing detects the file automatically at startup**. The owner asked whether the app
 * could find the settings file on its own; it cannot, and the reason is structural rather than an
 * omission. An app-private directory is deleted with the app, so a file there would not survive the
 * reinstall it exists for; anywhere else is either a SAF grant the user gives per file — which is the
 * browse button — or `READ_EXTERNAL_STORAGE`/`MANAGE_EXTERNAL_STORAGE`, a permission over every document
 * on the device, asked for so the app could read one. SAF grants do not survive an uninstall either, so
 * remembering last time's location would not help the case that matters.
 */
object SettingsFile {

    /** The MIME type the export is created with, and the first thing the import filters for. */
    const val MIME_TYPE = "application/json"

    /**
     * The whole text of [uri].
     *
     * Through `resultOf`, the project's single exception boundary (ADR-0003): a provider can throw for a
     * document the user moved or revoked between picking and reading, and that is a message rather than a
     * crash. The cap is what stops somebody picking a video by mistake and the app trying to hold it in
     * memory as a string — a settings file is a few kilobytes and nothing legitimate approaches this.
     */
    fun read(resolver: ContentResolver, uri: Uri): AppResult<String> = resultOf(
        onError = { AppError.Storage(summary = "That file could not be read.") },
    ) {
        resolver.openInputStream(uri).use { stream ->
            val reader = requireNotNull(stream) { "the picker returned a uri nothing can open" }.bufferedReader()
            // Bounded at the *read*, not after it. `readText()` on a picked video would allocate the whole
            // file before anything could object, which is the failure this guard exists to prevent.
            //
            // One char past the cap, so that "filled the buffer" and "is too large" are the same fact. The
            // loop is not decoration: `Reader.read` is allowed to return fewer characters than asked for
            // whenever it likes, so a single call would silently truncate a perfectly ordinary file.
            val buffer = CharArray(MAX_CHARACTERS + 1)
            var read = 0
            while (read < buffer.size) {
                val chunk = reader.read(buffer, read, buffer.size - read)
                if (chunk < 0) break
                read += chunk
            }
            require(read <= MAX_CHARACTERS) { "that file is far too large to be settings" }
            String(buffer, 0, read)
        }
    }

    /** Writes [document] over [uri], truncating whatever was there. */
    fun write(resolver: ContentResolver, uri: Uri, document: String): AppResult<Unit> = resultOf(
        onError = { AppError.Storage(summary = "The settings could not be saved to that file.") },
    ) {
        // "wt" — write, truncate. Without the `t` a provider may leave the tail of a longer previous
        // export behind, producing a file that is valid JSON followed by rubbish.
        resolver.openOutputStream(uri, "wt").use { stream ->
            requireNotNull(stream) { "the picker returned a uri nothing can write" }
                .write(document.toByteArray())
        }
    }

    /** Two megabytes of text. A real export is a few kilobytes; this is a guard, not a limit to design to. */
    private const val MAX_CHARACTERS = 2 * 1024 * 1024
}
