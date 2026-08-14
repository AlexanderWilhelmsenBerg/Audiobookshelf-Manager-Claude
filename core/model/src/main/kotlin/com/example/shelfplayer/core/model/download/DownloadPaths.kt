package com.example.shelfplayer.core.model.download

/**
 * PRODUCT_SPEC DL-003 — the storage layout, and the reason a hostile filename cannot escape it.
 *
 * ```
 * files/offline/<server-id>/<item-id>/<file-id>.<extension>
 * ```
 *
 * ### The design is that server strings are never path components
 *
 * The usual way this goes wrong is sanitising: take what the server sent, strip the characters that look
 * dangerous, hope the list was complete. It never is — `..`, `%2e%2e`, a trailing dot on Windows-derived
 * media, a NUL, a name that normalises to a different one — and each miss is a file written outside the
 * app's directory.
 *
 * So nothing here strips anything. Every component is **rebuilt** from an allowed alphabet: any character
 * that is not `[A-Za-z0-9._-]` becomes `_`, and the result is then checked against the small set of names
 * that are still dangerous once every character is safe (`.`, `..`, the empty string). A component is
 * therefore safe by construction rather than by exhaustion, and the property a test can state is total:
 * *for any input, the output contains no separator and is not a traversal.*
 *
 * Uniqueness survives it. The identity is the server's opaque id — a UUID in every Audiobookshelf capture —
 * and two ids that differ only in a character this replaces would already have been the same row in Room,
 * whose keys are those same strings. Where an id is not opaque, [component] appends a short digest so two
 * different inputs cannot collapse onto one directory.
 *
 * ### Extensions are chosen, not accepted
 *
 * A file's extension decides what the media container is *taken* to be, so it comes from the server's MIME
 * type through a fixed table, not from the filename. A server that says `audio/mpeg` gets `.mp3` whatever the
 * file was called upstream; anything unrecognised gets `.bin`, which is honest and unplayable rather than a
 * guess that puts an executable extension on somebody's SD card.
 */
object DownloadPaths {

    /** The directory, under whichever root is in use, that holds everything this app has downloaded. */
    const val ROOT_DIRECTORY: String = "offline"

    private const val MAX_COMPONENT = 64
    private val ALLOWED = Regex("[A-Za-z0-9._-]")

    /** Names that are still dangerous when every character in them is safe. */
    private val RESERVED = setOf("", ".", "..")

    private const val FALLBACK = "unnamed"

    /**
     * The relative directory for one item: `offline/<server>/<item>`.
     *
     * Relative because the root is not this module's business — it is an app-private `File` or a document
     * tree the user picked, and both are resolved where the platform types live.
     */
    fun itemDirectory(serverId: String, itemId: String): List<String> =
        listOf(ROOT_DIRECTORY, component(serverId), component(itemId))

    /** The file name for one audio file: the server's file id, with an extension chosen from its MIME type. */
    fun fileName(remoteFileId: String, mimeType: String?): String =
        "${component(remoteFileId)}.${extensionFor(mimeType)}"

    /**
     * PRODUCT_SPEC DL-001 — the name a file has while it is still arriving.
     *
     * `.part` as the requirement asks, and appended rather than substituted so the final name is recoverable
     * and the temporary one is never a playable extension. A media scanner that indexes the download folder —
     * possible once decision 4's user-chosen folder is in play — will not offer a half file as a track.
     */
    fun partName(fileName: String): String = "$fileName.part"

    /** Whether a name is a temporary part, for a cleanup pass that has only the filesystem to go on. */
    fun isPart(fileName: String): Boolean = fileName.endsWith(".part")

    /** The cover's name. Fixed, because there is one per item and its identity is the directory it is in. */
    fun coverName(mimeType: String?): String = "cover.${imageExtensionFor(mimeType)}"

    /**
     * One path component, rebuilt from an allowed alphabet.
     *
     * The three guarantees, in order of how easily each is otherwise lost:
     *
     * 1. **No separator survives.** `/` and `\` are not in the alphabet, so they become `_`; a component can
     *    never become two.
     * 2. **No traversal survives.** `..` is spelled entirely in allowed characters, which is exactly why the
     *    alphabet alone is not enough and [RESERVED] exists.
     * 3. **No collision is introduced.** A component that had to be changed carries a digest of the original,
     *    so two different ids cannot land in one directory. An id that needed no change gets no digest, which
     *    keeps the common case — an opaque UUID — readable in a file manager.
     */
    fun component(raw: String): String {
        val rebuilt = buildString(raw.length) {
            raw.forEach { character ->
                append(if (ALLOWED.matches(character.toString())) character else '_')
            }
        }
        val safe = if (rebuilt in RESERVED) FALLBACK else rebuilt
        val truncated = safe.take(MAX_COMPONENT)
        // Unchanged and short enough: the id itself, which is what somebody looking at the folder wants.
        if (truncated == raw) return truncated
        return "$truncated-${digest(raw)}"
    }

    /**
     * A short, stable discriminator for an input that had to be rewritten.
     *
     * `hashCode` rather than a cryptographic digest on purpose: this is a collision *avoidance* aid between
     * two names in one directory, not a security boundary — the security is the alphabet — and pulling in a
     * hash implementation for eight hex characters would be ceremony. Kotlin's `String.hashCode` is specified
     * by the language, so the value is stable across runs and platforms, which is what matters: a path that
     * changed between app versions would orphan every downloaded file.
     */
    private fun digest(raw: String): String = Integer.toHexString(raw.hashCode())

    /**
     * The extension for an audio MIME type.
     *
     * A fixed table covering what Audiobookshelf serves. The capture records `audio/mpeg`
     * (`contracts/item-file.json`); the rest are the formats the server's own transcoder and scanner accept.
     * Anything unknown is `.bin` — unplayable and honest — rather than a guess taken from the server's
     * filename, which is the input this whole object exists to distrust.
     */
    private fun extensionFor(mimeType: String?): String = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/m4b", "audio/x-m4b" -> "m4b"
        "audio/aac" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/webm" -> "webm"
        else -> "bin"
    }

    private fun imageExtensionFor(mimeType: String?): String =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
}
