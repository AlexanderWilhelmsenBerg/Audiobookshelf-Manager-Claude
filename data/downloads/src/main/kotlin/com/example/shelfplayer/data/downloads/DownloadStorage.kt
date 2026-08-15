package com.example.shelfplayer.data.downloads

import android.content.Context
import android.os.storage.StorageManager
import com.example.shelfplayer.core.model.download.DownloadPaths
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.model.resultOf
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

    /** One item's directory under whichever root is in use. */
    fun itemDirectory(serverId: String, itemId: String): File =
        File(context.filesDir, DownloadPaths.itemDirectory(serverId, itemId).joinToString(File.separator))

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

    /**
     * Where an item's cover goes.
     *
     * One name per item, so a re-download replaces the artwork rather than accumulating copies of it, and
     * so the offline player can find it without consulting the manifest.
     */
    fun coverFor(serverId: String, itemId: String, mimeType: String?): File {
        val directory = itemDirectory(serverId, itemId)
        directory.mkdirs()
        return File(directory, DownloadPaths.coverName(mimeType))
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

    /**
     * PRODUCT_SPEC DL-001 — "before queuing, app checks estimated size and free space".
     *
     * **Allocatable** bytes, not free ones. Android keeps a large cache it will evict under pressure, so
     * `File.usableSpace` on a phone that looks full routinely understates what a download can actually have
     * by gigabytes — and refusing a download the device could easily hold is the wrong kind of wrong.
     * `StorageManager.getAllocatableBytes` is the number that accounts for it.
     *
     * Falls back to `usableSpace` if the platform will not answer. Zero when neither can be read, which
     * fails the check closed: better to refuse than to fill somebody's phone.
     */
    fun usableBytes(): Long = resultOf {
        val manager = context.getSystemService(StorageManager::class.java)
        val uuid = manager.getUuidForPath(context.filesDir)
        manager.getAllocatableBytes(uuid)
    }.getOrNull() ?: context.filesDir.usableSpace.coerceAtLeast(0)

    /**
     * PRODUCT_SPEC DL-001 — removes the temporary parts left by downloads that will never finish.
     *
     * A `.part` is deliberately kept after a failure, because it is what a retry resumes from. That is right
     * while the download still exists and wrong once it does not: a cancelled book, an item deleted upstream,
     * or a crash between writing bytes and writing the manifest all leave a part nothing will ever ask for,
     * and nothing else in the app would ever find it.
     *
     * So the caller supplies the set of item directories that still have a manifest, and everything under
     * `offline/` outside that set goes. Directory-level rather than file-level: an item with a manifest may
     * legitimately have parts, and one without cannot.
     *
     * @return how many bytes were reclaimed, for the log and for the storage screen.
     */
    fun sweepOrphans(keep: Set<Pair<String, String>>): Long {
        val root = File(context.filesDir, DownloadPaths.ROOT_DIRECTORY)
        if (!root.isDirectory) return 0
        val live = keep.mapTo(mutableSetOf()) { (serverId, itemId) ->
            DownloadPaths.itemDirectory(serverId, itemId).joinToString(File.separator)
        }
        var reclaimed = 0L
        root.listFiles().orEmpty().forEach { serverDirectory ->
            serverDirectory.listFiles().orEmpty().forEach { itemDirectory ->
                val relative = itemDirectory.relativeTo(context.filesDir).path
                if (relative !in live) {
                    reclaimed += itemDirectory.walkBottomUp().filter(File::isFile).sumOf(File::length)
                    itemDirectory.deleteRecursively()
                }
            }
            // An empty server directory is left behind by the loop above and is pure litter.
            if (serverDirectory.isDirectory && serverDirectory.listFiles().orEmpty().isEmpty()) {
                serverDirectory.delete()
            }
        }
        return reclaimed
    }

    /**
     * The parts belonging to a book whose manifest is still live but whose files are no longer wanted.
     *
     * Used when a download is cancelled *and* the user asked to discard it, which is the one case where a
     * resumable part should not survive. [sweepOrphans] cannot cover it, because the manifest is still there.
     */
    fun deleteParts(serverId: String, itemId: String): Long {
        val directory =
            File(context.filesDir, DownloadPaths.itemDirectory(serverId, itemId).joinToString(File.separator))
        if (!directory.isDirectory) return 0
        return directory.listFiles().orEmpty()
            .filter { file -> file.isFile && DownloadPaths.isPart(file.name) }
            .sumOf { file -> file.length().also { file.delete() } }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
