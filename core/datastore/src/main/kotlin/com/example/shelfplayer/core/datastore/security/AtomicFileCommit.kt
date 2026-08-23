package com.example.shelfplayer.core.datastore.security

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Commits this staging file over [target] without first creating a window in which no target exists.
 *
 * `File.renameTo` does not define whether an existing destination is replaced. Android's Linux-backed
 * implementation does replace it, while the Windows JVM used by the local verification tier returns
 * `false`. `Files.move` makes replacement explicit. The atomic option is attempted first because these
 * callers protect credentials and passcode records from interruption; the documented non-atomic fallback
 * is reserved for a filesystem provider that cannot perform an atomic move at all.
 */
internal fun File.commitReplacing(target: File) {
    try {
        Files.move(toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(toPath(), target.toPath(), REPLACE_EXISTING)
    }
}
