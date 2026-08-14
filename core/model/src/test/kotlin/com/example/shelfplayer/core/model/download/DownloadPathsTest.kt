package com.example.shelfplayer.core.model.download

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-003 criteria 1–2 — "filenames from the server are sanitized and never used as untrusted
 * paths" and "path traversal is impossible".
 *
 * The second is a claim about *every* input, not about the handful anybody thought to try, so the cases here
 * are split in two. The named ones are the attacks that have actually shipped in other clients. The last one
 * is the property itself, over a generated corpus: whatever goes in, what comes out is one component and is
 * not a traversal.
 */
class DownloadPathsTest {

    /** The ordinary case, and the one worth keeping readable: an opaque server id passes through unchanged. */
    @Test
    fun `an opaque id is left alone`() {
        val id = "f5199769-37a0-4dfe-8200-0792a9dbf417"

        assertEquals(id, DownloadPaths.component(id))
        assertEquals(
            listOf("offline", "server-1", id),
            DownloadPaths.itemDirectory(serverId = "server-1", itemId = id),
        )
    }

    /**
     * The attacks, each with the mechanism named, because a reader has to be able to tell that the list is
     * about categories rather than about strings somebody remembered.
     */
    @Test
    fun `nothing escapes the directory`() {
        val hostile = mapOf(
            "../../etc/passwd" to "climbing out with separators",
            "..\\..\\windows" to "the same on a Windows-derived name",
            ".." to "the traversal on its own, spelled entirely in allowed characters",
            "." to "the current directory, which would collapse two levels into one",
            "" to "an empty component, which would make the path one level shorter than intended",
            "%2e%2e%2fetc" to "percent-encoding, in case something decodes downstream",
            "a/b" to "a single separator, which would silently create a subdirectory",
            "\u0000truncated" to "a NUL, which some filesystem layers treat as end-of-string",
            "\u202Egnp.exe" to "a right-to-left override, which hides the real extension in a file manager",
            "  " to "whitespace only, which is a name on some filesystems and not on others",
        )

        hostile.forEach { (raw, mechanism) ->
            val component = DownloadPaths.component(raw)

            assertFalse(component.contains('/'), "$mechanism: produced a separator")
            assertFalse(component.contains('\\'), "$mechanism: produced a Windows separator")
            assertNotEquals("..", component, "$mechanism: produced a traversal")
            assertNotEquals(".", component, mechanism)
            assertTrue(component.isNotBlank(), "$mechanism: produced nothing")
        }
    }

    /**
     * The property, over every byte value and a corpus built from them.
     *
     * This is the assertion that makes criterion 2 true rather than tested: it does not depend on anybody
     * having thought of the right attack. A character not in the allowed alphabet cannot survive, so no
     * combination of them can.
     */
    @Test
    fun `no input of any kind produces a separator or a traversal`() {
        val corpus = buildList {
            (0..0x2FF).forEach { code -> add(code.toChar().toString()) }
            (0..0x2FF).forEach { code -> add("a${code.toChar()}b") }
            (0..0x2FF).forEach { code -> add("..${code.toChar()}") }
            addAll(listOf("../".repeat(40), "/".repeat(40), ".".repeat(200), "😀".repeat(10)))
        }

        corpus.forEach { raw ->
            val component = DownloadPaths.component(raw)

            assertTrue(
                component.all { it.isLetterOrDigit() && it.code < 0x80 || it in "._-" },
                "produced a character outside the alphabet for ${raw.map(Char::code)}",
            )
            assertFalse(component in setOf("", ".", ".."), "produced a reserved name for ${raw.map(Char::code)}")
        }
    }

    /**
     * Two ids that differ only in a rewritten character must not land in the same directory.
     *
     * The alphabet is what makes the path safe; this is what keeps it *correct*. Without the digest,
     * `a b` and `a/b` would both become `a_b` and one book's files would overwrite another's.
     */
    @Test
    fun `two ids cannot collapse onto one directory`() {
        assertNotEquals(DownloadPaths.component("a b"), DownloadPaths.component("a/b"))
        assertNotEquals(DownloadPaths.component("café"), DownloadPaths.component("cafe_"))
    }

    /** And the rewriting is stable, or every downloaded file would be orphaned by an app update. */
    @Test
    fun `a rewritten component is the same on every run`() {
        assertEquals(DownloadPaths.component("a/b"), DownloadPaths.component("a/b"))
    }

    /**
     * PRODUCT_SPEC DL-003 criterion 1 — the extension comes from the MIME type, never from the server's
     * filename.
     *
     * The filename is the untrusted input this whole object exists to distrust, and the extension is what
     * decides how a file is *treated* — by the media scanner, by another app, by whatever opens it if the
     * user chose a folder outside the sandbox. `audio/mpeg` was the type the capture recorded.
     */
    @Test
    fun `the extension comes from the mime type`() {
        assertEquals("file-1.mp3", DownloadPaths.fileName("file-1", "audio/mpeg"))
        assertEquals("file-1.m4b", DownloadPaths.fileName("file-1", "audio/m4b"))
        assertEquals("file-1.mp3", DownloadPaths.fileName("file-1", "audio/mpeg; charset=binary"))
    }

    /** An unknown type is `.bin`: unplayable and honest, rather than a guess taken from the server. */
    @Test
    fun `an unknown mime type does not become a guess`() {
        assertEquals("file-1.bin", DownloadPaths.fileName("file-1", null))
        assertEquals("file-1.bin", DownloadPaths.fileName("file-1", "application/x-msdownload"))
        assertEquals("file-1.bin", DownloadPaths.fileName("file-1", "text/html"))
    }

    /** PRODUCT_SPEC DL-001 — a temporary part is named as one and is not a playable extension. */
    @Test
    fun `a part is named as one`() {
        val part = DownloadPaths.partName(DownloadPaths.fileName("file-1", "audio/mpeg"))

        assertEquals("file-1.mp3.part", part)
        assertTrue(DownloadPaths.isPart(part))
        assertFalse(DownloadPaths.isPart("file-1.mp3"))
    }
}
