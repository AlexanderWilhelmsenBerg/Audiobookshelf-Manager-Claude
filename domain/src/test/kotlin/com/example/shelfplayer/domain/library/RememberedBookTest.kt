package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RememberedBookTest {

    @Test
    fun `remote recency never replaces the locally remembered book`() {
        val localA = played("a", "2026-08-01T00:00:00Z")
        val remoteB = played("b", "2026-09-12T00:00:00Z")

        val result = rememberedBook(listOf(localA, remoteB), LibraryItemId("a"))

        assertEquals(LibraryItemId("a"), result?.id)
    }

    @Test
    fun `remote rewind or advance changes position but not remembered identity`() {
        val localA = played("a", "2026-08-01T00:00:00Z")
        val remoteB = played("b", "2026-09-12T00:00:00Z")
        val rewoundB = remoteB.copy(progress = remoteB.progress?.copy(position = 2.minutes))
        val advancedB = remoteB.copy(progress = remoteB.progress?.copy(position = 8.hours))

        assertEquals(LibraryItemId("a"), rememberedBook(listOf(localA, rewoundB), LibraryItemId("a"))?.id)
        assertEquals(LibraryItemId("a"), rememberedBook(listOf(localA, advancedB), LibraryItemId("a"))?.id)
    }

    @Test
    fun `no remembered identity does not invent one from server progress`() {
        val remote = played("remote", "2026-09-12T00:00:00Z")

        assertNull(rememberedBook(listOf(remote), rememberedId = null))
    }

    @Test
    fun `a finished remembered book is not resumed and no fallback is chosen`() {
        val finishedA = played("a", "2026-08-01T00:00:00Z", finished = true)
        val remoteB = played("b", "2026-09-12T00:00:00Z")

        assertNull(rememberedBook(listOf(finishedA, remoteB), LibraryItemId("a")))
    }

    private fun played(id: String, at: String, finished: Boolean = false) = book(id).copy(
        progress = MediaProgress(
            serverId = TEST_SERVER,
            profileId = TEST_PROFILE,
            bookId = LibraryItemId(id),
            position = 10.minutes,
            duration = 10.hours,
            isFinished = finished,
            updatedAt = Instant.parse(at),
            hasUnsyncedChanges = false,
        ),
    )
}
