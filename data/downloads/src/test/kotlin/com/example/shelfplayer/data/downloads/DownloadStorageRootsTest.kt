package com.example.shelfplayer.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.download.DownloadPaths
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-003 / ADR-0020 — the storage layer with more than one root.
 *
 * The case this exists for is a user who downloads a few books, then moves downloads to an SD card. Their
 * old books are still on internal storage and their manifest still points there, so *writes* must go to the
 * new root while *deletes and sweeps* must find the old one. Getting that wrong is not visible as a crash:
 * the storage screen reports a removal that freed nothing, and orphans accumulate somewhere nothing in the
 * app can see.
 *
 * Two real directories stand in for two volumes. Nothing here needs a card — the class under test only ever
 * sees a list of `File` roots, which is exactly why it takes one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadStorageRootsTest {

    private lateinit var context: Context
    private lateinit var oldRoot: File
    private lateinit var newRoot: File

    /** The order `StorageVolumes` produces: the chosen volume first, everything else after it. */
    private lateinit var storage: DownloadStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        oldRoot = File(context.cacheDir, "volume-internal").apply { mkdirs() }
        newRoot = File(context.cacheDir, "volume-card").apply { mkdirs() }
        storage = DownloadStorage(context) { listOf(newRoot, oldRoot) }
    }

    @After
    fun tearDown() {
        oldRoot.deleteRecursively()
        newRoot.deleteRecursively()
    }

    @Test
    fun `a new download is written to the chosen volume`() {
        val part = storage.partFor(SERVER, ITEM, "file-1", "audio/mpeg")

        assertTrue(part.absolutePath.startsWith(newRoot.absolutePath), part.absolutePath)
    }

    /**
     * The removal that would otherwise silently free nothing.
     *
     * A book downloaded before the card went in is on the old root, and its manifest still points there.
     * A `deleteItem` that only looked at the current root would return true, delete nothing, and leave the
     * storage screen's total exactly where it was.
     */
    @Test
    fun `removing a book finds it on a root that is no longer the current one`() {
        val stranded = itemDirectory(oldRoot).apply { mkdirs() }
        File(stranded, "file-1.mp3").writeText("bytes from before the card")

        assertTrue(storage.deleteItem(SERVER, ITEM))

        assertFalse(stranded.exists(), "the book on the old volume was removed")
    }

    @Test
    fun `removing a book still finds it on the current one`() {
        val here = itemDirectory(newRoot).apply { mkdirs() }
        File(here, "file-1.mp3").writeText("bytes")

        storage.deleteItem(SERVER, ITEM)

        assertFalse(here.exists())
    }

    /**
     * PRODUCT_SPEC DL-001 — an orphan on the old volume is invisible to everything else in the app.
     *
     * Every other path into the filesystem starts from a manifest, and an orphan is by definition the thing
     * no manifest points at. If the sweep does not look at the old root, nothing ever will.
     */
    @Test
    fun `the sweep reclaims orphans from every root`() {
        val strandedOrphan = File(oldRoot, "${DownloadPaths.ROOT_DIRECTORY}/$SERVER/vanished").apply { mkdirs() }
        File(strandedOrphan, "file-9.mp3.part").writeText("nobody will ever ask for this")
        val currentOrphan = File(newRoot, "${DownloadPaths.ROOT_DIRECTORY}/$SERVER/also-vanished").apply { mkdirs() }
        File(currentOrphan, "file-9.mp3.part").writeText("nor this")

        val reclaimed = storage.sweepOrphans(keep = emptySet())

        assertFalse(strandedOrphan.exists(), "the orphan on the old volume went")
        assertFalse(currentOrphan.exists(), "and so did the one on the current volume")
        assertTrue(reclaimed > 0, "and their bytes were counted")
    }

    /** A book that still has a manifest is left alone, on whichever volume it happens to be. */
    @Test
    fun `the sweep leaves a claimed book on an old root alone`() {
        val claimed = itemDirectory(oldRoot).apply { mkdirs() }
        File(claimed, "file-1.mp3").writeText("still wanted")

        storage.sweepOrphans(keep = setOf(SERVER to ITEM))

        assertTrue(claimed.exists())
    }

    @Test
    fun `discarding partials clears them from every root`() {
        listOf(oldRoot, newRoot).forEach { root ->
            itemDirectory(root).mkdirs()
            File(itemDirectory(root), "file-1.mp3.part").writeText("half a file")
        }

        val reclaimed = storage.deleteParts(SERVER, ITEM)

        assertEquals(0, listOf(oldRoot, newRoot).count { File(itemDirectory(it), "file-1.mp3.part").exists() })
        assertTrue(reclaimed > 0)
    }

    private fun itemDirectory(root: File) =
        File(root, DownloadPaths.itemDirectory(SERVER, ITEM).joinToString(File.separator))

    private companion object {
        const val SERVER = "server-1"
        const val ITEM = "book-1"
    }
}
