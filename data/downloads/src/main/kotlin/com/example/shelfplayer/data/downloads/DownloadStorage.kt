package com.example.shelfplayer.data.downloads

import android.content.Context
import com.example.shelfplayer.core.model.download.DownloadPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-003 — where a downloaded file actually goes, on the app-private root.
 *
 * `files/offline/<server-id>/<item-id>/<file-id>.<extension>`, exactly as the requirement writes it. Every
 * path component comes from [DownloadPaths], which rebuilds it from an allowed alphabet rather than
 * stripping characters out of it — so criterion 2, "path traversal is impossible", holds by construction
 * and not by this class remembering to check.
 *
 * ### Only the app-private root, for now
 *
 * Decision 4 also asks for a folder the user picks, including an SD card. That is reached through the
 * Storage Access Framework, where paths do not exist at all and a `DocumentFile` tree replaces `File`.
 * The manifest already stores locations as URIs so both fit, and this class is the `file://` half. The
 * SAF half is a second implementation of the same three operations, not a change to this one.
 *
 * ### Nothing here is exposed to other apps
 *
 * `Context.filesDir` is app-private (DL-003 criterion 3), which is what the default is *for*. When a user
 * chooses their own folder they give that up deliberately, and ADR-0018 records it as a deviation.
 */
@Singleton
class DownloadStorage @Inject constructor(@param:ApplicationContext private val context: Context) {

    /**
     * The `.part` file for one audio file, with its directory created.
     *
     * The part rather than the final name, because nothing else should ever be written to: a file under
     * its real name is a file the verifier and the player are entitled to trust.
     */
    fun partFor(serverId: String, itemId: String, fileId: String, mimeType: String?): File {
        val directory =
            File(context.filesDir, DownloadPaths.itemDirectory(serverId, itemId).joinToString(File.separator))
        directory.mkdirs()
        return File(directory, DownloadPaths.partName(DownloadPaths.fileName(fileId, mimeType)))
    }

    /** Where [partFor]'s file will end up once it has been verified. */
    fun finalFor(part: File): File = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))

    /**
     * Opens [part] for writing, appending when [append] is true.
     *
     * The flag is the caller's decision and not this class's: only the caller knows whether the server
     * honoured the range it asked for. Appending after a declined range would splice two different files
     * together, and the result would pass every length check there is.
     */
    fun sink(part: File, append: Boolean): OutputStream = FileOutputStream(part, append)

    /**
     * PRODUCT_SPEC DL-001 — the atomic commit.
     *
     * A rename within one filesystem is atomic, so a crash leaves either the `.part` or the finished file
     * and never a half-named one. That is what "atomic commit prevents a crash from creating a false
     * complete state" means at the file level; the book-level half is
     * [com.example.shelfplayer.domain.repository.DownloadRepository.markComplete], which refuses to
     * complete a book whose files are not all committed.
     *
     * An existing file at the destination is removed first, because `File.renameTo` is allowed to fail
     * rather than replace on some Android filesystems, and a redownload that silently did nothing would
     * leave the old bytes with a new manifest describing them.
     */
    fun commit(part: File): File? {
        val destination = finalFor(part)
        if (destination.exists() && !destination.delete()) return null
        return destination.takeIf { part.renameTo(it) }
    }

    /** Removes a file, reporting whether anything was there. Absent is not a failure — it is the goal. */
    fun delete(file: File): Boolean = !file.exists() || file.delete()

    /**
     * Everything under one item's directory, and the directory itself.
     *
     * Used when a copy loses its last claim. Bounded to the item's own directory rather than walking from
     * the root: a delete that started higher up would be one path-construction bug away from removing
     * somebody else's book.
     */
    fun deleteItem(serverId: String, itemId: String): Boolean {
        val directory =
            File(context.filesDir, DownloadPaths.itemDirectory(serverId, itemId).joinToString(File.separator))
        return !directory.exists() || directory.deleteRecursively()
    }

    /** How many bytes of [part] are already on disk, which is where a resume asks the server to continue. */
    fun bytesOnDisk(part: File): Long = if (part.exists()) part.length() else 0

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
